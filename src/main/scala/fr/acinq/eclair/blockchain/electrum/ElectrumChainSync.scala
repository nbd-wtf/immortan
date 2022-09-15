package fr.acinq.eclair.blockchain.electrum

import scala.util.{Failure, Success, Try}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.blockchain.electrum.Blockchain.RETARGETING_PERIOD
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{
  GetHeadersResponse,
  HeaderSubscriptionResponse
}
import fr.acinq.eclair.blockchain.electrum.db.HeaderDb

object ElectrumChainSync {
  case class ChainSyncStarted(localTip: Long, remoteTip: Long)
      extends ElectrumEvent
  case class ChainSyncEnded(localTip: Long) extends ElectrumEvent

  sealed trait State
  case object DISCONNECTED extends State
  case object SYNCING extends State
  case object RUNNING extends State
  case object WAITING_FOR_CHAIN_TIP extends State
}

case class BlockchainReady(bc: Blockchain) extends ElectrumEvent

class ElectrumChainSync(
    pool: ElectrumClientPool,
    headerDb: HeaderDb,
    chainHash: ByteVector32
) {
  import ElectrumChainSync._

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

  def onElectrumReady(): Unit = if (state == DISCONNECTED) {
    // do this so this won't run twice
    state = WAITING_FOR_CHAIN_TIP

    System.err.println(
      s"[debug][chain-sync] subscribing to headers so we can get the chain tip ($state)"
    )
    pool.subscribeToHeaders("chain-sync", onHeader).onComplete {
      case Success(tip: HeaderSubscriptionResponse) =>
        System.err
          .println(s"[debug][chain-sync] got the chain tip: ${tip.height}")

        if (tip.height < blockchain.height) {
          System.err.println(
            s"[warn][chain-sync] ${tip.source.address} is out of sync, its tip is ${tip.height}, disconnecting"
          )
          tip.source.send(PoisonPill)
          state = DISCONNECTED
        } else if (blockchain.bestchain.isEmpty) {
          System.err.println("[debug][chain-sync] starting sync from scratch")
          EventStream.publish(ChainSyncStarted(blockchain.height, tip.height))
          getHeaders(
            blockchain.checkpoints.size * RETARGETING_PERIOD,
            RETARGETING_PERIOD
          )
          state = SYNCING
        } else if (Some(tip.header) == blockchain.tip.map(_.header)) {
          System.err.println("[debug][chain-sync] we're synced already")
          EventStream.publish(ChainSyncEnded(blockchain.height))
          EventStream.publish(BlockchainReady(blockchain))
          state = RUNNING
        } else {
          System.err.println("[debug][chain-sync] starting sync")
          EventStream.publish(ChainSyncStarted(blockchain.height, tip.height))
          getHeaders(blockchain.height + 1, RETARGETING_PERIOD)
          state = SYNCING
        }

      case Failure(err) =>
        pool.master match {
          case Some(master) =>
            System.err.println(
              s"[warn][chain-sync] HeaderSubscription to ${pool.master.get.address} has failed, disconnecting"
            )
            pool.master.get.send(PoisonPill)
            state = DISCONNECTED

          case None =>
            System.err.println(
              s"[warn][chain-sync] we were told electrum was ready, but apparently it's not"
            )
            state = DISCONNECTED
        }
    }
  }

  pool.getReady match {
    case None    =>
    case Some(_) => onElectrumReady()
  }

  EventStream.subscribe {
    case _: ElectrumDisconnected => state = DISCONNECTED
    case _: ElectrumReady        => onElectrumReady()
  }

  var state: State = DISCONNECTED
  def stay = state

  def onHeader(response: HeaderSubscriptionResponse): Unit = {
    val HeaderSubscriptionResponse(source, height, header) = response

    state = state match {
      case SYNCING => {
        System.err.println(
          s"[info][chain-sync] ignoring header for $height since we are syncing"
        )
        stay
      }

      case RUNNING if blockchain.tip.map(_.header) != Some(header) => {
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
            System.err.println(
              "[error][chain-sync] electrum peer sent bad headers"
            )
            source.send(PoisonPill)
            stay
          }
        }
      }

      case _ => stay
    }
  }

  def getHeaders(startHeight: Int, count: Int): Future[Unit] = {
    System.err.println(
      s"[debug][chain-sync] requesting headers from $startHeight to ${startHeight + count}"
    )
    pool
      .request[GetHeadersResponse](
        ElectrumClient.GetHeaders(startHeight, count)
      )
      .andThen {
        case Success(GetHeadersResponse(source, start, headers, max)) =>
          if (headers.isEmpty) {
            System.err.println(
              "[debug][chain-sync] no more headers, sync done"
            )
            if (state == SYNCING) {
              EventStream.publish(
                ElectrumChainSync.ChainSyncEnded(blockchain.height)
              )
              EventStream.publish(BlockchainReady(blockchain))
              state = RUNNING
            }
          } else {
            System.err.println(
              s"[debug][chain-sync] got headers from $start to ${start + max}"
            )
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
                      s"[info][chain-sync] got new headers chunk at ${obc.height}, requesting next chunk"
                    )
                    getHeaders(obc.height + 1, RETARGETING_PERIOD)
                    blockchain = obc
                    state = SYNCING
                  }

                  case RUNNING => {
                    headerDb.addHeaders(headers, start)
                    EventStream.publish(BlockchainReady(bc))
                    blockchain = bc
                  }

                  case _ =>
                    System.err.println(
                      s"[error][chain-sync] can't add headers while in state $state"
                    )
                }
              case Failure(err) => {
                System.err
                  .println(
                    s"[error][chain-sync] electrum peer sent bad headers: $err"
                  )
                source.send(PoisonPill)
                state = DISCONNECTED
              }
            }
          }

        case Failure(err) =>
          System.err.println(
            s"[warn][chain-sync] failed to get headers, disconnecting from master"
          )
          pool.master.foreach { _.send(PoisonPill) }
          state = DISCONNECTED
      }
      .map(_ => ())
  }

  def getSyncedBlockchain = if (state == RUNNING) Some(blockchain) else None
}
