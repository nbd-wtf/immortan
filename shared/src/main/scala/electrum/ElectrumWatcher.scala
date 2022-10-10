package immortan.electrum

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.collection.immutable.{Queue, SortedMap}
import scala.util.{Try, Success, Failure}
import scodec.bits.ByteVector
import scoin._
import scoin.Crypto.PublicKey

import immortan.blockchain._
import immortan.electrum.ElectrumClient.{
  computeScriptHash,
  HeaderSubscriptionResponse,
  BroadcastTransactionResponse,
  GetScriptHashHistory,
  GetScriptHashHistoryResponse,
  ScriptHashSubscriptionResponse,
  TransactionHistoryItem,
  GetTransaction,
  GetTransactionResponse,
  GetMerkle,
  GetMerkleResponse
}
import immortan.channel.{
  Scripts,
  BitcoinEvent,
  BITCOIN_FUNDING_DEPTHOK,
  BITCOIN_PARENT_TX_CONFIRMED
}

class ElectrumWatcher(blockCount: AtomicLong, pool: ElectrumClientPool) {
  sealed trait State

  case object Disconnected extends State
  case object Running extends State

  var state: State = Disconnected
  val scriptHashStatus: scala.collection.mutable.Map[ByteVector32, String] =
    scala.collection.mutable.Map.empty
  val watchSpents: scala.collection.mutable.Map[
    WatchSpent,
    Function[WatchEventSpent, Unit]
  ] =
    scala.collection.mutable.Map.empty
  val watchConfirmeds: scala.collection.mutable.Map[
    WatchConfirmed,
    Function[WatchEventConfirmed, Unit]
  ] =
    scala.collection.mutable.Map.empty
  var block2tx: SortedMap[Long, Seq[Transaction]] = SortedMap.empty
  var publishQueue: Queue[Transaction] = Queue.empty
  var published: Queue[Transaction] = Queue.empty

  EventStream.subscribe { case ElectrumDisconnected =>
    state match {
      case Disconnected =>
      case Running      =>
        // we remember the txes that we previously sent but hadn't yet received the confirmation to resend
        publishQueue = publishQueue ++ published
        state = Disconnected
    }
  }

  def watch(w: WatchSpent)(cb: Function[WatchEventSpent, Unit]): Unit = {
    watchSpents += (w -> cb)
    val scriptHash = computeScriptHash(w.publicKeyScript)
    System.err.println(
      s"[info][watcher] added watch-spent on output=${w.txId}:${w.outputIndex} scriptHash=$scriptHash"
    )
    trackScriptHash(scriptHash)
  }

  def watch(
      w: WatchConfirmed
  )(cb: Function[WatchEventConfirmed, Unit]): Unit = {
    watchConfirmeds += (w -> cb)
    val scriptHash = computeScriptHash(w.publicKeyScript)
    System.err.println(
      s"[info][watcher] added watch-confirmed on txid=${w.txId} scriptHash=$scriptHash"
    )
    trackScriptHash(scriptHash)
  }

  def trackScriptHash(scriptHash: ByteVector32): Unit = {
    pool.subscribeToScriptHash("watcher", scriptHash) {
      case ScriptHashSubscriptionResponse(sh, status) => {
        if (scriptHashStatus.get(sh) == Some(status)) {
          System.err.println(
            s"[debug][watcher] scripthash ${sh.toHex} has changed"
          )
          scriptHashStatus += (sh -> status)
          inspectScriptHash(sh)
        }
      }
    }
  }

  def maybePublish(tx: Transaction): Unit = state match {
    case Disconnected =>
      // add this to queue
      publishQueue = publishQueue :+ tx

    case Running =>
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeouts = Scripts.csvTimeouts(tx)
      if (csvTimeouts.nonEmpty) {
        // watcher supports txs with multiple csv-delayed inputs:
        //   we watch all delayed parents and try to publish every
        // time a parent's relative delays are satisfied, so we will eventually succeed.
        csvTimeouts.foreach { case (parentTxId, csvTimeout) =>
          System.err.println(
            s"[info][watcher] txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parentTxId=$parentTxId tx=$tx"
          )
          val parentPublicKeyScript = WatchConfirmed.extractPublicKeyScript(
            tx.txIn.find(_.outPoint.txid == parentTxId).get.witness
          )

          // wait until the parent is confirmed
          watch(
            WatchConfirmed(
              parentTxId,
              parentPublicKeyScript,
              minDepth = csvTimeout,
              BITCOIN_PARENT_TX_CONFIRMED(tx)
            )
          ) {
            case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), _, _) => {
              System.err.println(
                s"[info][watcher] parent tx of txid=${tx.txid} has been confirmed"
              )
              val cltvTimeout = Scripts.cltvTimeout(tx)
              if (cltvTimeout > blockCount.get) {
                System.err.println(
                  s"[info][watcher] delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=${blockCount.get})"
                )
                val block2tx1 = block2tx.updated(
                  cltvTimeout,
                  block2tx.getOrElse(cltvTimeout, Seq.empty) :+ tx
                )
              } else
                publish(tx)
            }
            case _ =>
              System.err.println("[error][watcher] this should never happen")
          }
        }
      } else if (cltvTimeout > blockCount.get) {
        System.err.println(
          s"[info][watcher] delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=${blockCount.get})"
        )
        block2tx = block2tx.updated(
          cltvTimeout,
          block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx
        )
      } else {
        System.err.println(
          s"[info][watcher] publishing txid=${tx.txid}"
        )
        publish(tx)
      }
  }

  def inspectScriptHash(scriptHash: ByteVector32): Unit = for {
    history <- pool
      .request[GetScriptHashHistoryResponse](GetScriptHashHistory(scriptHash))
      .map { case GetScriptHashHistoryResponse(_, items) =>
        items
      }

    transactions <- Future
      .sequence(
        // we retrieve the transaction before checking watches
        // NB: height=-1 means that the tx is unconfirmed and at least one of its inputs is also unconfirmed.
        //   we need to take them into consideration if we want to handle unconfirmed txes
        //   (which is the case for turbo channels)
        history
          .filter(_.height >= -1)
          .map(item =>
            pool
              .request[GetTransactionResponse](
                GetTransaction(item.txHash, Some(item))
              )
              .andThen {
                case Success(txr) if txr.tx.txid != item.txHash =>
                  throw new BadTxIdMismatched(item.txHash, txr.tx.txid)
              }
          )
      )
  } yield transactions.foreach {
    case GetTransactionResponse(tx, Some(item: TransactionHistoryItem)) =>
      // this is for WatchSpent/WatchSpentBasic
      watchSpents --= tx.txIn
        .map(_.outPoint)
        .flatMap(outPoint =>
          watchSpents.collect {
            case (WatchSpent(txid, pos, _, event, _), cb)
                if txid == outPoint.txid && pos == outPoint.index.toInt => {
              // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
              // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
              System.err.println(
                s"[info][watcher] output $txid:$pos spent by transaction ${tx.txid}"
              )
              cb(WatchEventSpent(event, tx))
              None
            }
          }
        )
        .flatten

      // this is for WatchConfirmed
      watchConfirmeds --= watchConfirmeds.collect {
        case (
              w @ WatchConfirmed(
                txid,
                _,
                minDepth,
                BITCOIN_FUNDING_DEPTHOK
              ),
              cb
            ) if txid == tx.txid && minDepth == 0 =>
          // special case for mempool watches (min depth = 0)
          val (dummyHeight, dummyTxIndex) =
            ElectrumWatcher.makeDummyShortChannelId(txid)
          cb(
            WatchEventConfirmed(
              BITCOIN_FUNDING_DEPTHOK,
              TxConfirmedAt(dummyHeight, tx),
              dummyTxIndex
            )
          )
          Some(w)
        case (WatchConfirmed(txid, _, minDepth, _), _)
            if txid == tx.txid && minDepth > 0 && item.height > 0 =>
          // min depth > 0 here, i.e. it's in a block
          val txheight = item.height
          val confirmations = pool.blockHeight - txheight + 1
          System.err.println(
            s"[info][watcher] txid=$txid was confirmed at height=$txheight and now has confirmations=$confirmations (currentHeight=${pool.blockHeight})"
          )
          if (confirmations >= minDepth) {
            // we need to get the tx position in the block
            pool
              .request[GetMerkleResponse](GetMerkle(item.txHash, item.height))
              .onComplete {
                case Success(merkle: GetMerkleResponse) =>
                  watchConfirmeds --= watchConfirmeds.collect {
                    case (
                          w @ WatchConfirmed(
                            txid,
                            _,
                            minDepth,
                            event
                          ),
                          cb
                        ) if txid == merkle.txid =>
                      cb(
                        WatchEventConfirmed(
                          event,
                          TxConfirmedAt(txheight.toInt, tx),
                          merkle.pos
                        )
                      )
                      w
                  }
                case Failure(err) =>
                  System.err.println(
                    s"[error][watcher] failed to call electrum server for GetMerkle: $err"
                  )
              }
          }

          None
      }.flatten

    case GetTransactionResponse(tx, _) =>
      System.err.println("[error][watcher] tx response with wrong context?")
  }

  // as the class is instantiated, begin watching for headers
  pool.subscribeToHeaders("watcher") { case tip: HeaderSubscriptionResponse =>
    state match {
      case Disconnected =>
        // we got a blockchain header, means we're up and running now

        // restart the watchers
        watchConfirmeds.foreach { case (w: WatchConfirmed, cb) => watch(w)(cb) }
        watchSpents.foreach { case (w: WatchSpent, cb) => watch(w)(cb) }

        state = Running
        // publish all pending transactions (after changing the state to Running)
        publishQueue.foreach(maybePublish(_))

      case Running =>
        // a new block was found
        // ~
        // for all transactions we are watching to be confirmed, try to get their statuses
        watchConfirmeds.collect { case (w: WatchConfirmed, _) =>
          inspectScriptHash(computeScriptHash(w.publicKeyScript))
        }

        // publish all txs that we must have published at this point
        val toPublish = block2tx.view.filterKeys(_ <= tip.height)
        toPublish.values.flatten.foreach(publish(_))

        // and remove them from the list
        block2tx = block2tx -- toPublish.keys
    }
  }

  private def publish(tx: Transaction): Unit = {
    // keep track of this until we're sure it was published
    published = published :+ tx

    pool
      .request[ElectrumClient.BroadcastTransactionResponse](
        ElectrumClient.BroadcastTransaction(tx)
      )
      .onComplete {
        case Success(
              BroadcastTransactionResponse(tx, error_opt)
            ) =>
          error_opt match {
            case None =>
              System.err.println(
                s"[info][watcher] broadcast succeeded for txid=${tx.txid} tx=$tx"
              )
            case Some(error)
                if error.message
                  .contains("transaction already in block chain") =>
              System.err.println(
                s"[info][watcher] broadcast ignored for txid=${tx.txid} tx=$tx (tx was already in blockchain)"
              )
            case Some(error) =>
              System.err.println(
                s"[error][watcher] broadcast failed for txid=${tx.txid} tx=$tx with error=$error"
              )
          }

          // remove it from the list of published since it is already in the blockchain
          // TODO: query other random electrum servers after a while to be sure they have seen this transaction
          published = published.diff(Seq(tx))

        case Failure(err) =>
          System.err.println(
            s"[error][watcher] failed to call electrum server for BroadcastTransaction: $err"
          )
      }
  }
}

object ElectrumWatcher {
  // A (blockHeight, txIndex) tuple that is extracted from the input source
  def makeDummyShortChannelId(txid: ByteVector32): (Int, Int) = {
    val txIndex = txid.bits.sliceToInt(0, 16, signed = false)
    (0, txIndex)
  }
}

final case class WatchConfirmed(
    txId: ByteVector32,
    publicKeyScript: ByteVector,
    minDepth: Long,
    event: BitcoinEvent
)

object WatchConfirmed {
  // if we have the entire transaction, we can get the publicKeyScript from any of the outputs
  def apply(
      tx: Transaction,
      event: BitcoinEvent,
      minDepth: Long
  ): WatchConfirmed =
    WatchConfirmed(
      tx.txid,
      tx.txOut.map(_.publicKeyScript).headOption.getOrElse(ByteVector.empty),
      minDepth,
      event
    )

  def extractPublicKeyScript(witness: ScriptWitness): ByteVector = Try(
    PublicKey.fromBin(witness.stack.last)
  ) match {
    case Success(pubKey) =>
      // if last element of the witness is a public key, then this is a p2wpkh
      Script.write(Script.pay2wpkh(pubKey))
    case _ =>
      // otherwise this is a p2wsh
      Script.write(Script pay2wsh witness.stack.last)
  }
}

final case class WatchSpent(
    txId: ByteVector32,
    outputIndex: Int,
    publicKeyScript: ByteVector,
    event: BitcoinEvent,
    hints: Set[ByteVector32] = Set.empty
)

object WatchSpent {
  // if we have the entire transaction, we can get the publicKeyScript from the relevant output
  def apply(
      tx: Transaction,
      outputIndex: Int,
      event: BitcoinEvent
  ): WatchSpent =
    WatchSpent(
      tx.txid,
      outputIndex,
      tx.txOut(outputIndex).publicKeyScript,
      event
    )
}

sealed trait WatchEvent

final case class TxConfirmedAt(blockHeight: Int, tx: Transaction)

final case class WatchEventConfirmed(
    event: BitcoinEvent,
    txConfirmedAt: TxConfirmedAt,
    txIndex: Int
) extends WatchEvent

final case class WatchEventSpent(event: BitcoinEvent, tx: Transaction)
    extends WatchEvent
