package immortan.blockchain.electrum

import scoin.{Block, ByteVector32}
import immortan.blockchain.electrum.Blockchain.RETARGETING_PERIOD
import immortan.blockchain.electrum.ElectrumClient
import immortan.blockchain.electrum.db.HeaderDb

import scala.util.{Failure, Success, Try}

object ElectrumChainSync {
  case class ChainSyncStarted(localTip: Long, remoteTip: Long)
  case class ChainSyncEnded(localTip: Long)
}

class ElectrumChainSync(
    pool: ElectrumClientPool,
    headerDb: HeaderDb,
    chainHash: ByteVector32
)(implicit
    ac: castor.Context
) extends castor.SimpleActor[Any] { self =>
  sealed trait State
  case object DISCONNECTED extends State
  case object WAITING_FOR_TIP extends State
  case object SYNCING extends State
  case object RUNNING extends State

  var blockchain: Blockchain =
    if (chainHash != Block.RegtestGenesisBlock.hash) {
      // In case if anything at all goes wrong we just use an initial blockchain and resync it from checkpoint
      val blockchain = Blockchain.fromCheckpoints(
        checkpoints = CheckPoint.load(chainHash, headerDb),
        chainhash = chainHash
      )
      val headers = headerDb.getHeaders(
        startHeight = blockchain.checkpoints.size * RETARGETING_PERIOD,
        maxCount = Int.MaxValue
      )
      Try apply Blockchain.addHeadersChunk(
        blockchain,
        blockchain.checkpoints.size * RETARGETING_PERIOD,
        headers
      ) getOrElse blockchain
    } else
      Blockchain.fromGenesisBlock(
        Block.RegtestGenesisBlock.hash,
        Block.RegtestGenesisBlock.header
      )

  pool.addStatusListener(self)

  var state: State = DISCONNECTED
  def stay = state

  def run(msg: Any): Unit = {
    state = (state, msg) match {
      case (DISCONNECTED, _: ElectrumClient.ElectrumReady) => {
        pool.subscribeToHeaders(self)
        WAITING_FOR_TIP
      }

      case (
            WAITING_FOR_TIP,
            response: ElectrumClient.HeaderSubscriptionResponse
          ) if response.height < blockchain.height =>
        DISCONNECTED

      case (
            WAITING_FOR_TIP,
            response: ElectrumClient.HeaderSubscriptionResponse
          ) if blockchain.bestchain.isEmpty => {
        EventStream publish ElectrumChainSync.ChainSyncStarted(
          blockchain.height,
          response.height
        )
        getHeaders(
          blockchain.checkpoints.size * RETARGETING_PERIOD,
          RETARGETING_PERIOD
        )
        SYNCING
      }

      case (
            WAITING_FOR_TIP,
            response: ElectrumClient.HeaderSubscriptionResponse
          ) if Some(response.header) == blockchain.tip.map(_.header) => {
        EventStream publish ElectrumChainSync.ChainSyncEnded(
          blockchain.height
        )
        EventStream publish blockchain
        RUNNING
      }

      case (
            WAITING_FOR_TIP,
            response: ElectrumClient.HeaderSubscriptionResponse
          ) => {
        EventStream publish ElectrumChainSync.ChainSyncStarted(
          blockchain.height,
          response.height
        )
        getHeaders(blockchain.height + 1, RETARGETING_PERIOD)
        SYNCING
      }

      case (
            SYNCING,
            ElectrumClient.HeaderSubscriptionResponse(_, height, header)
          ) => {
        System.err.println(
          s"[debug] ignoring header $header at $height while syncing"
        )
        stay
      }

      case (
            RUNNING,
            ElectrumClient.HeaderSubscriptionResponse(source, height, header)
          ) if blockchain.tip.map(_.header) != Some(header) => {
        val difficultyOk = Blockchain
          .getDifficulty(blockchain, height, headerDb)
          .forall(header.bits.==)

        Try(Blockchain.addHeader(blockchain, height, header)) match {
          case Success(bc) if difficultyOk => {
            val (blockchain2, chunks) = Blockchain.optimize(bc)
            headerDb.addHeaders(chunks.map(_.header), chunks.head.height)
            EventStream publish blockchain2
            blockchain = blockchain2
            stay
          }

          case _ => {
            System.err.println("[error] electrum peer sent bad headers")
            source.send(PoisonPill)
            stay
          }
        }
      }

      case (_, ElectrumClient.ElectrumDisconnected) =>
        DISCONNECTED

      case _ => stay
    }
  }

  def getHeaders(startHeight: Int, count: Int): Unit =
    pool.request(ElectrumClient.GetHeaders(startHeight, count)).onComplete {
      case Success(
            ElectrumClient.GetHeadersResponse(source, start, headers, _)
          ) =>
        if (headers.isEmpty) {
          if (state == SYNCING) {
            EventStream publish ElectrumChainSync.ChainSyncEnded(
              blockchain.height
            )
            EventStream publish blockchain
            state = RUNNING
          }
        } else {
          Try(Blockchain.addHeaders(blockchain, start, headers)) match {
            case Success(bc) =>
              state match {
                case SYNCING => {
                  val (obc, chunks) = Blockchain.optimize(bc)
                  headerDb.addHeaders(chunks.map(_.header), chunks.head.height)
                  System.err.println(
                    s"[info] got new headers chunk at ${obc.height}, requesting next chunk"
                  )
                  getHeaders(obc.height + 1, RETARGETING_PERIOD)
                  blockchain = obc
                  state = SYNCING
                }

                case RUNNING => {
                  headerDb.addHeaders(headers, start)
                  EventStream publish bc
                  blockchain = bc
                }

                case _ => {}
              }
            case Failure(err) => {
              System.err
                .println(s"[error] electrum peer sent bad headers: $err")
              source.send(PoisonPill)
              state = DISCONNECTED
            }
          }
        }

      case _ => {}
    }

  def getChain = blockchain
}
