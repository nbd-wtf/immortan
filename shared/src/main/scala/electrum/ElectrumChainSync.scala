package immortan.electrum

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}
import scoin.{Block, ByteVector32}

import immortan.electrum.Blockchain.RETARGETING_PERIOD
import immortan.electrum.ElectrumClient.{
  HeaderSubscriptionResponse,
  GetHeaders,
  GetHeadersResponse
}
import immortan.electrum.db.HeaderDb

object ElectrumChainSync {
  case class ChainSyncStarted(localTip: Long, remoteTip: Long)
      extends ElectrumEvent
  case class ChainSyncProgress(localTip: Long, remoteTip: Long)
      extends ElectrumEvent
  case class ChainSyncEnded(localTip: Long) extends ElectrumEvent

  sealed trait State
  case object DISCONNECTED extends State
  case object SYNCING extends State
  case object RUNNING extends State
  case object WAITING_FOR_TIP extends State
}

case class BlockchainReady(bc: Blockchain) extends ElectrumEvent

class ElectrumChainSync(
    pool: ElectrumClientPool,
    headerDb: HeaderDb,
    chainHash: ByteVector32
) {
  import ElectrumChainSync._

  var blockchain: Blockchain = chainHash match {
    case Block.RegtestGenesisBlock.hash =>
      Blockchain.fromGenesisBlock(
        Block.RegtestGenesisBlock.hash,
        Block.RegtestGenesisBlock.header
      )
    case Block.SignetGenesisBlock.hash =>
      Blockchain.fromGenesisBlock(
        Block.SignetGenesisBlock.hash,
        Block.SignetGenesisBlock.header
      )
    case _ =>
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
  }

  EventStream.subscribe { case ElectrumDisconnected =>
    state = DISCONNECTED
  }

  var state: State = DISCONNECTED

  // keep track here to prevent multiple wallet sync calls to request the same chunk again and again
  private val headerChunkBeingRequested =
    scala.collection.mutable.Map.empty[Int, Promise[Unit]]
  def getHeaders(startHeight: Int, count: Int): Future[Unit] =
    headerChunkBeingRequested.get(startHeight) match {
      case Some(p) => p.future
      case None =>
        val p = Promise[Unit]()
        synchronized {
          headerChunkBeingRequested += (startHeight -> p)
        }
        def resolve = synchronized {
          headerChunkBeingRequested.remove(startHeight)
        }.foreach(_.success(()))

        System.err.println(
          s"[debug][chain-sync] requesting headers from $startHeight to ${startHeight + count}"
        )
        pool
          .request[GetHeadersResponse](GetHeaders(startHeight, count))
          .onComplete {
            case Success(GetHeadersResponse(source, start, headers, max)) =>
              if (headers.isEmpty) {
                System.err.println(
                  "[debug][chain-sync] no more headers, sync done"
                )
                EventStream.publish(
                  ChainSyncEnded(blockchain.height)
                )
                EventStream.publish(BlockchainReady(blockchain))
                state = RUNNING
                resolve
              } else {
                System.err.println(
                  s"[debug][chain-sync] got headers from $start to ${start + max}"
                )

                EventStream.publish(
                  ChainSyncProgress(start + max, blockchain.height)
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
                        getHeaders(obc.height + 1, RETARGETING_PERIOD)
                        blockchain = obc
                        state = SYNCING
                        resolve
                      }

                      case RUNNING => {
                        headerDb.addHeaders(headers, start)
                        EventStream.publish(BlockchainReady(bc))
                        blockchain = bc
                        resolve
                      }

                      case _ =>
                        System.err.println(
                          s"[error][chain-sync] can't add headers while in state $state"
                        )
                        resolve
                    }
                  case Failure(err) => {
                    System.err
                      .println(
                        s"[warn][chain-sync] electrum peer sent bad headers: $err"
                      )
                    resolve
                  }
                }
              }

            case Failure(err) =>
              System.err.println(
                s"[warn][chain-sync] failed to get headers: $err"
              )
              resolve
          }

        p.future
    }

  def getSyncedBlockchain = if (state == RUNNING) Some(blockchain) else None

  // sync process starts right away
  state = WAITING_FOR_TIP
  System.err.println(
    s"[debug][chain-sync] subscribing to headers so we can get the chain tip"
  )
  pool.subscribeToHeaders("chain-sync") {
    case tip: HeaderSubscriptionResponse =>
      state match {
        case WAITING_FOR_TIP =>
          System.err
            .println(s"[debug][chain-sync] got the chain tip: ${tip.height}")

          if (tip.height < blockchain.height) {
            System.err.println(
              s"[warn][chain-sync] ${tip.source.address} is out of sync, its tip is ${tip.height}, disconnecting"
            )
            pool.killClient(tip.source)
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

        case SYNCING => // ignore new tip header since we are syncing
        case RUNNING if blockchain.tip.map(_.header) != Some(tip.header) =>
          val difficultyOk = Blockchain
            .getDifficulty(blockchain, tip.height, headerDb)
            .forall(tip.header.bits == _)

          if (!difficultyOk) {
            System.err.println(
              s"[warn][chain-sync] difficulty not ok from header subscription"
            )
            pool.killClient(tip.source)
          } else
            Try(
              Blockchain.addHeader(blockchain, tip.height, tip.header)
            ) match {
              case Success(bc) => {
                val (nextBlockchain, chunks) = Blockchain.optimize(bc)
                headerDb.addHeaders(chunks.map(_.header), chunks.head.height)
                EventStream.publish(BlockchainReady(nextBlockchain))
                blockchain = nextBlockchain
              }
              case Failure(MissingParent(header, height)) =>
                System.err.println(
                  s"[warn][chain-sync] missing parent for header $header at $height, going back into syncing mode"
                )
                EventStream.publish(ChainSyncStarted(height - 1, tip.height))
                getHeaders(height - 1, RETARGETING_PERIOD)
                state = SYNCING
              case Failure(err) => {
                System.err.println(
                  s"[warn][chain-sync] bad headers from subscription: $err"
                )
                pool.killClient(tip.source)
              }
            }

        case _ =>
      }
  }
}
