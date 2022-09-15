package fr.acinq.eclair.blockchain.electrum

import scala.util.{Failure, Success, Try}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.blockchain.electrum.Blockchain.RETARGETING_PERIOD
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.HeaderSubscriptionResponse
import fr.acinq.eclair.blockchain.electrum.db.HeaderDb

object ElectrumChainSync {
  case class ChainSyncStarted(localTip: Long, remoteTip: Long)
      extends ElectrumEvent
  case class ChainSyncEnded(localTip: Long) extends ElectrumEvent
}

case class BlockchainReady(bc: Blockchain) extends ElectrumEvent

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
    case _: ElectrumDisconnected => state = DISCONNECTED
    case _: ElectrumReady        => onElectrumReady()
  }

  def onElectrumReady(): Unit = if (state == DISCONNECTED) {
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
        System.err.println("[debug][chain-sync] starting sync from scratch")
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
        System.err.println("[debug][chain-sync] we're synced already")
        EventStream.publish(
          ElectrumChainSync.ChainSyncEnded(
            blockchain.height
          )
        )
        EventStream.publish(BlockchainReady(blockchain))
        RUNNING
      }

      case (
            WAITING_FOR_TIP,
            response: HeaderSubscriptionResponse
          ) => {
        System.err.println("[debug][chain-sync] starting sync")
        EventStream.publish(
          ElectrumChainSync.ChainSyncStarted(
            blockchain.height,
            response.height
          )
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
            EventStream.publish(BlockchainReady(blockchain2))
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

  def getHeaders(startHeight: Int, count: Int): Future[Unit] =
    pool
      .request[ElectrumClient.GetHeadersResponse](
        ElectrumClient.GetHeaders(startHeight, count)
      )
      .andThen {
        case Success(
              ElectrumClient.GetHeadersResponse(source, start, headers, _)
            ) =>
          if (headers.isEmpty) {
            System.err.println("[debug][chain-sync] no more headers, sync done")
            if (state == SYNCING) {
              EventStream.publish(
                ElectrumChainSync.ChainSyncEnded(
                  blockchain.height
                )
              )
              EventStream.publish(BlockchainReady(blockchain))
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
                    System.err.println("new block")
                    headerDb.addHeaders(headers, start)
                    EventStream.publish(BlockchainReady(bc))
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
      .map(_ => ())

  def getSyncedBlockchain = if (state == RUNNING) Some(blockchain) else None
}
