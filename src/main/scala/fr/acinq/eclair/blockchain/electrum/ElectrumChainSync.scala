package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.blockchain.electrum.Blockchain.RETARGETING_PERIOD
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{
  ElectrumDisconnected,
  ElectrumReady,
  HeaderSubscriptionResponse
}
import fr.acinq.eclair.blockchain.electrum.db.HeaderDb

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
) extends castor.SimpleActor[HeaderSubscriptionResponse] { self =>

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
      Try {
        Blockchain.addHeadersChunk(
          blockchain,
          blockchain.checkpoints.size * RETARGETING_PERIOD,
          headers
        )
      } getOrElse blockchain
    } else
      Blockchain.fromGenesisBlock(
        Block.RegtestGenesisBlock.hash,
        Block.RegtestGenesisBlock.header
      )

  EventStream.subscribe {
    case ElectrumDisconnected                   => state = DISCONNECTED
    case ElectrumReady if state == DISCONNECTED => onElectrumReady()
  }

  def onElectrumReady(): Unit = {
    pool.subscribeToHeaders(self)
    state = WAITING_FOR_TIP
  }

  pool.getReady match {
    case None    =>
    case Some(_) => onElectrumReady()
  }

  var state: State = DISCONNECTED
  def stay = state

  def run(msg: HeaderSubscriptionResponse): Unit = {
    state = (state, msg) match {
      case (
            WAITING_FOR_TIP,
            response: HeaderSubscriptionResponse
          ) if response.height < blockchain.height =>
        DISCONNECTED

      case (
            WAITING_FOR_TIP,
            response: HeaderSubscriptionResponse
          ) if blockchain.bestchain.isEmpty => {
        EventStream.publish(
          ElectrumChainSync.ChainSyncStarted(
            blockchain.height,
            response.height
          )
        )
        getHeaders(
          blockchain.checkpoints.size * RETARGETING_PERIOD,
          RETARGETING_PERIOD
        )
        SYNCING
      }

      case (
            WAITING_FOR_TIP,
            response: HeaderSubscriptionResponse
          ) if Some(response.header) == blockchain.tip.map(_.header) => {
        EventStream.publish(
          ElectrumChainSync.ChainSyncEnded(
            blockchain.height
          )
        )
        System.err.println("publishing blockchain alt")
        EventStream.publish(blockchain)
        RUNNING
      }

      case (
            WAITING_FOR_TIP,
            response: HeaderSubscriptionResponse
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
            HeaderSubscriptionResponse(_, height, header)
          ) => {
        System.err.println(
          s"[debug] ignoring header $header at $height while syncing"
        )
        stay
      }

      case (
            RUNNING,
            HeaderSubscriptionResponse(source, height, header)
          ) if blockchain.tip.map(_.header) != Some(header) => {
        val difficultyOk = Blockchain
          .getDifficulty(blockchain, height, headerDb)
          .forall(header.bits.==)

        Try(Blockchain.addHeader(blockchain, height, header)) match {
          case Success(bc) if difficultyOk => {
            val (blockchain2, chunks) = Blockchain.optimize(bc)
            headerDb.addHeaders(chunks.map(_.header), chunks.head.height)
            System.err.println("publishing blockchain2")
            EventStream.publish(blockchain2)
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

      case _ => stay
    }
  }

  def getHeaders(startHeight: Int, count: Int): Unit =
    pool
      .request[ElectrumClient.Response](
        ElectrumClient.GetHeaders(startHeight, count)
      )
      .onComplete {
        case Success(
              ElectrumClient.GetHeadersResponse(source, start, headers, _)
            ) =>
          if (headers.isEmpty) {
            if (state == SYNCING) {
              EventStream.publish(
                ElectrumChainSync.ChainSyncEnded(
                  blockchain.height
                )
              )
              System.err.println("publishing blockchain")
              EventStream.publish(blockchain)
              state = RUNNING
            }
          } else {
            Try(Blockchain.addHeaders(blockchain, start, headers)) match {
              case Success(bc) =>
                state match {
                  case SYNCING => {
                    val (obc, chunks) = Blockchain.optimize(bc)
                    headerDb.addHeaders(
                      chunks.map(_.header),
                      chunks.head.height
                    )
                    System.err.println(
                      s"[info] got new headers chunk at ${obc.height}, requesting next chunk"
                    )
                    getHeaders(obc.height + 1, RETARGETING_PERIOD)
                    blockchain = obc
                    state = SYNCING
                  }

                  case RUNNING => {
                    headerDb.addHeaders(headers, start)
                    System.err.println("publishing bc")
                    EventStream.publish(bc)
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

  def getHeaders(req: ElectrumClient.GetHeaders): Unit = {
    val ElectrumClient.GetHeaders(start, count, _) = req
    getHeaders(start, count)
  }

  def getChain = blockchain
}
