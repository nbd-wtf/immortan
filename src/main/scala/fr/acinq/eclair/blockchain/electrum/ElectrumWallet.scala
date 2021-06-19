package fr.acinq.eclair.blockchain.electrum

import ElectrumWallet._
import fr.acinq.bitcoin._
import immortan.crypto.Tools._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.eclair.blockchain.EclairWallet._
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._

import scala.util.{Failure, Success, Try}
import akka.actor.{ActorRef, FSM, PoisonPill}
import fr.acinq.eclair.blockchain.{EclairWallet, TxAndFee}
import fr.acinq.eclair.blockchain.electrum.db.WalletDb
import fr.acinq.eclair.blockchain.bitcoind.rpc.Error
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.bitcoin.Crypto.PublicKey
import Blockchain.RETARGETING_PERIOD
import scala.annotation.tailrec
import scodec.bits.ByteVector


class ElectrumWallet(seed: ByteVector, client: ActorRef, params: ElectrumWallet.WalletParameters, ewt: ElectrumWalletType) extends FSM[ElectrumWallet.State, ElectrumData] {

  val master: ExtendedPrivateKey = DeterministicWallet.generate(seed)

  val accountMaster: ExtendedPrivateKey = ewt.accountKey(master, params.chainHash)

  val changeMaster: ExtendedPrivateKey = ewt.changeKey(master, params.chainHash)

  client ! ElectrumClient.AddStatusListener(self)

  def persistAndNotify(data: ElectrumData): ElectrumData = {
    val newReadyMessage: WalletReady = data.generateReadyMessage
    val isSame = data.lastReadyMessage.contains(newReadyMessage)

    if (isSame) data else {
      params.walletDb.persist(data.toPersitent, ewt.tag)
      context.system.eventStream.publish(newReadyMessage)
      data.copy(lastReadyMessage = newReadyMessage.asSome)
    }
  }

  def loadData: ElectrumData = {
    val blockchain = params.chainHash match {
      case Block.RegtestGenesisBlock.hash => Blockchain.fromGenesisBlock(Block.RegtestGenesisBlock.hash, Block.RegtestGenesisBlock.header)
      case _ => Blockchain.fromCheckpoints(checkpoints = CheckPoint.load(params.chainHash, params.walletDb), chainhash = params.chainHash)
    }

    val headers = params.walletDb.getHeaders(blockchain.checkpoints.size * RETARGETING_PERIOD, Int.MaxValue)
    val blockchain1 = Blockchain.addHeadersChunk(blockchain, blockchain.checkpoints.size * RETARGETING_PERIOD, headers)

    params.walletDb.readPersistentData(ewt.tag).map { persisted =>
      val firstAccountKeys = for (idx <- 0 until persisted.accountKeysCount) yield derivePrivateKey(accountMaster, idx)
      val firstChangeKeys = for (idx <- 0 until persisted.changeKeysCount) yield derivePrivateKey(changeMaster, idx)

      ElectrumData(ewt, blockchain1, firstAccountKeys.toVector, firstChangeKeys.toVector, persisted.status, persisted.transactions,
        persisted.heights, persisted.history, persisted.proofs, pendingHistoryRequests = Set.empty, pendingHeadersRequests = Set.empty,
        pendingTransactionRequests = Set.empty, pendingTransactions = persisted.pendingTransactions)
    } getOrElse {
      val firstAccountKeys = for (idx <- 0 until params.swipeRange) yield derivePrivateKey(accountMaster, idx)
      val firstChangeKeys = for (idx <- 0 until params.swipeRange) yield derivePrivateKey(changeMaster, idx)
      ElectrumData(ewt, blockchain1, firstAccountKeys.toVector, firstChangeKeys.toVector)
    }
  }

  startWith(DISCONNECTED, loadData)

  when(DISCONNECTED) {
    case Event(ElectrumClient.ElectrumReady(_, _, _), data) =>
      // Subscribe to headers stream, server will reply with current tip
      client ! ElectrumClient.HeaderSubscription(self)
      goto(WAITING_FOR_TIP) using data
  }

  when(WAITING_FOR_TIP) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), data) =>
      if (height < data.blockchain.height) {
        log.info(s"electrum server is behind at $height we're at ${data.blockchain.height}, disconnecting")
        sender ! PoisonPill
        goto(DISCONNECTED) using data
      } else if (data.blockchain.bestchain.isEmpty) {
        log.info("performing full sync")
        // now ask for the first header after our latest checkpoint
        client ! ElectrumClient.GetHeaders(data.blockchain.checkpoints.size * RETARGETING_PERIOD, RETARGETING_PERIOD)
        goto(SYNCING) using data
      } else if (header == data.blockchain.tip.header) {
        // nothing to sync
        data.accountKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(ewt.computeScriptHashFromPublicKey(key.publicKey), self))
        data.changeKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(ewt.computeScriptHashFromPublicKey(key.publicKey), self))
        goto(RUNNING) using persistAndNotify(data)
      } else {
        client ! ElectrumClient.GetHeaders(data.blockchain.tip.height + 1, RETARGETING_PERIOD)
        log.info(s"syncing headers from ${data.blockchain.height} to $height, ready=${data.isReady(params.swipeRange)}")
        goto(SYNCING) using data
      }
  }

  when(SYNCING) {
    case Event(ElectrumClient.GetHeadersResponse(start, headers, _), data) =>
      if (headers.isEmpty) {
        // ok, we're all synced now
        log.info(s"headers sync complete, tip=${data.blockchain.tip}")
        data.accountKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(ewt.computeScriptHashFromPublicKey(key.publicKey), self))
        data.changeKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(ewt.computeScriptHashFromPublicKey(key.publicKey), self))
        goto(RUNNING) using persistAndNotify(data)
      } else {
        Try(Blockchain.addHeaders(data.blockchain, start, headers)) match {
          case Success(blockchain1) =>
            val (blockchain2, saveme) = Blockchain.optimize(blockchain1)
            saveme.grouped(RETARGETING_PERIOD).foreach(chunk => params.walletDb.addHeaders(chunk.head.height, chunk.map(_.header)))
            log.info(s"requesting new headers chunk at ${blockchain2.tip.height}")
            client ! ElectrumClient.GetHeaders(blockchain2.tip.height + 1, RETARGETING_PERIOD)
            goto(SYNCING) using data.copy(blockchain = blockchain2)
          case Failure(error) =>
            log.error("electrum server sent bad headers, disconnecting", error)
            sender ! PoisonPill
            goto(DISCONNECTED) using data
        }
      }

    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), _) =>
      // we can ignore this, we will request header chunks until the server has nothing left to send us
      log.debug("ignoring header {} at {} while syncing", header, height)
      stay()
  }

  when(RUNNING) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(_, header), data) if data.blockchain.tip.header == header => stay

    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), data) =>
      log.info(s"got new tip ${header.blockId} at $height")

      val difficulty = Blockchain.getDifficulty(data.blockchain, height, params.walletDb)

      if (!difficulty.forall(target => header.bits == target)) {
        log.error(s"electrum server send bad header (difficulty is not valid), disconnecting")
        sender ! PoisonPill
        stay()
      } else {
        Try(Blockchain.addHeader(data.blockchain, height, header)) match {
          case Success(blockchain1) =>
            val (blockchain2, saveme) = Blockchain.optimize(blockchain1)
            saveme.grouped(RETARGETING_PERIOD).foreach(chunk => params.walletDb.addHeaders(chunk.head.height, chunk.map(_.header)))
            stay using persistAndNotify(data.copy(blockchain = blockchain2))
          case Failure(error) =>
            log.error(error, s"electrum server sent bad header, disconnecting")
            sender ! PoisonPill
            stay() using data
        }
      }

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if data.status.get(scriptHash).contains(status) =>
      val missing = data.history.getOrElse(scriptHash, Nil).map(_.txHash).filterNot(data.transactions.contains).toSet -- data.pendingTransactionRequests
      val data1 = data.copy(pendingHistoryRequests = data.pendingTransactionRequests ++ missing)
      for (txid <- missing) client ! GetTransaction(txid)
      stay using persistAndNotify(data1)

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if !data.accountKeyMap.contains(scriptHash) && !data.changeKeyMap.contains(scriptHash) =>
      log.warning(s"received status=$status for scriptHash=$scriptHash which does not match any of our keys")
      stay

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if status.isEmpty =>
      val data1 = data.copy(status = data.status + (scriptHash -> status)) // empty status, nothing to do
      stay using persistAndNotify(data1)

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) =>
      val isChange = data.changeKeyMap.contains(scriptHash)

      // let's retrieve the tx history for this key
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)

      val (newAccountKeys, newChangeKeys) = data.status.get(Try(ByteVector32.fromValidHex(status)).getOrElse(ByteVector32.Zeroes)) match {
        case None =>
          // first time this script hash is used, need to generate a new key
          val newKey = if (isChange) derivePrivateKey(changeMaster, data.changeKeys.last.path.lastChildNumber + 1) else derivePrivateKey(accountMaster, data.accountKeys.last.path.lastChildNumber + 1)
          val newScriptHash = ewt.computeScriptHashFromPublicKey(newKey.publicKey)
          // listens to changes for the newly generated key
          client ! ElectrumClient.ScriptHashSubscription(newScriptHash, self)
          if (isChange) (data.accountKeys, data.changeKeys :+ newKey) else (data.accountKeys :+ newKey, data.changeKeys)
        case Some(_) => (data.accountKeys, data.changeKeys)
      }

      val data1 = data.copy(
        accountKeys = newAccountKeys,
        changeKeys = newChangeKeys,
        status = data.status + (scriptHash -> status),
        pendingHistoryRequests = data.pendingHistoryRequests + scriptHash)

      stay using persistAndNotify(data1)

    case Event(ElectrumClient.GetScriptHashHistoryResponse(scriptHash, items), data) =>
      log.debug("scriptHash={} has history={}", scriptHash, items)
      val shadow_items = data.history.get(scriptHash) match {
        case Some(existing_items) => existing_items.filterNot(item => items.exists(_.txHash == item.txHash))
        case None => Nil
      }
      shadow_items.foreach(item => log.warning(s"keeping shadow item for txid=${item.txHash}"))
      val items0 = items ++ shadow_items

      val pendingHeadersRequests1 = collection.mutable.HashSet.empty[GetHeaders]
      pendingHeadersRequests1 ++= data.pendingHeadersRequests

      /**
        * If we don't already have a header at this height, or a pending request to download the header chunk it's in,
        * download this header chunk.
        * We don't have this header because it's most likely older than our current checkpoint, downloading the whole header
        * chunk (2016 headers) is quick and they're easy to verify.
        */
      def downloadHeadersIfMissing(height: Int): Unit = {
        if (data.blockchain.getHeader(height).orElse(params.walletDb.getHeader(height)).isEmpty) {
          // we don't have this header, probably because it is older than our checkpoints
          // request the entire chunk, we will be able to check it efficiently and then store it
          val start = (height / RETARGETING_PERIOD) * RETARGETING_PERIOD
          val request = GetHeaders(start, RETARGETING_PERIOD)
          // there may be already a pending request for this chunk of headers
          if (!pendingHeadersRequests1.contains(request)) {
            client ! request
            pendingHeadersRequests1.add(request)
          }
        }
      }

      val (heights1, pendingTransactionRequests1) = items0.foldLeft((data.heights, data.pendingTransactionRequests)) {
        case ((heights, hashes), item) if !data.transactions.contains(item.txHash) && !data.pendingTransactionRequests.contains(item.txHash) =>
          // we retrieve the tx if we don't have it and haven't yet requested it
          client ! GetTransaction(item.txHash)
          if (item.height > 0) { // don't ask for merkle proof for unconfirmed transactions
            downloadHeadersIfMissing(item.height)
            client ! GetMerkle(item.txHash, item.height)
          }
          (heights + (item.txHash -> item.height), hashes + item.txHash)
        case ((heights, hashes), item) =>
          // otherwise we just update the height
          (heights + (item.txHash -> item.height), hashes)
      }

      // we now have updated height for all our transactions,
      heights1.collect {
        case (txid, height0) =>
          (data.heights.get(txid), height0) match {
            case (None, height) if height <= 0 =>
            // height=0 => unconfirmed, height=-1 => unconfirmed and one input is unconfirmed
            case (None, height) if height > 0 =>
              // first time we get a height for this tx: either it was just confirmed, or we restarted the wallet
              downloadHeadersIfMissing(height.toInt)
              client ! GetMerkle(txid, height.toInt)
            case (Some(previousHeight), height) if previousHeight != height =>
              // there was a reorg
              if (height > 0) {
                downloadHeadersIfMissing(height.toInt)
                client ! GetMerkle(txid, height.toInt)
              }
            case (Some(previousHeight), height) if previousHeight == height && height > 0 && !data.proofs.contains(txid) =>
              downloadHeadersIfMissing(height.toInt)
              client ! GetMerkle(txid, height.toInt)
            case (Some(previousHeight), height) if previousHeight == height =>
            // no reorg, nothing to do
          }
      }
      val data1 = data.copy(
        heights = heights1,
        history = data.history + (scriptHash -> items0),
        pendingHistoryRequests = data.pendingHistoryRequests - scriptHash,
        pendingTransactionRequests = pendingTransactionRequests1,
        pendingHeadersRequests = pendingHeadersRequests1.toSet)
      stay using persistAndNotify(data1)

    case Event(ElectrumClient.GetHeadersResponse(start, headers, _), data) =>
      Try(Blockchain.addHeadersChunk(data.blockchain, start, headers)) match {
        case Success(blockchain1) =>
          params.walletDb.addHeaders(start, headers)
          stay() using data.copy(blockchain = blockchain1)
        case Failure(error) =>
          log.error("electrum server sent bad headers, disconnecting", error)
          sender ! PoisonPill
          goto(DISCONNECTED) using data
      }

    case Event(GetTransactionResponse(tx, context_opt), data) =>
      log.debug(s"received transaction ${tx.txid}")
      data.computeTransactionDelta(tx) match {
        case Some((received, sent, feeOpt)) =>
          log.info(s"successfully connected txid=${tx.txid}")
          context.system.eventStream.publish(data.transactionReceived(tx, feeOpt, received, sent))
          // when we have successfully processed a new tx, we retry all pending txes to see if they can be added now
          data.pendingTransactions.foreach(self ! GetTransactionResponse(_, context_opt))
          val data1 = data.copy(transactions = data.transactions + (tx.txid -> tx), pendingTransactionRequests = data.pendingTransactionRequests - tx.txid, pendingTransactions = Nil)
          stay using persistAndNotify(data1)
        case None =>
          // missing parents
          log.info(s"couldn't connect txid=${tx.txid}")
          val data1 = data.copy(pendingTransactionRequests = data.pendingTransactionRequests - tx.txid, pendingTransactions = data.pendingTransactions :+ tx)
          stay using persistAndNotify(data1)
      }

    case Event(ServerError(GetTransaction(txid, _), error), data) if data.pendingTransactionRequests.contains(txid) =>
      // server tells us that txid belongs to our wallet history, but cannot provide tx ?
      log.error(s"server cannot find history tx $txid: $error")
      sender ! PoisonPill
      goto(DISCONNECTED) using data


    case Event(response@GetMerkleResponse(txid, _, height, _, _), data) =>
      data.blockchain.getHeader(height).orElse(params.walletDb.getHeader(height)) match {
        case Some(header) if header.hashMerkleRoot == response.root =>
          log.info(s"transaction $txid has been verified")
          val data1 = if (!data.transactions.contains(txid) && !data.pendingTransactionRequests.contains(txid) && !data.pendingTransactions.exists(_.txid == txid)) {
            log.warning(s"we received a Merkle proof for transaction $txid that we don't have")
            data
          } else {
            data.copy(proofs = data.proofs + (txid -> response))
          }
          stay using data1
        case Some(_) =>
          log.error(s"server sent an invalid proof for $txid, disconnecting")
          sender ! PoisonPill
          stay() using data.copy(transactions = data.transactions - txid)
        case None =>
          // this is probably because the tx is old and within our checkpoints => request the whole header chunk
          val start = (height / RETARGETING_PERIOD) * RETARGETING_PERIOD
          val request = GetHeaders(start, RETARGETING_PERIOD)
          val pendingHeadersRequest1 = if (data.pendingHeadersRequests.contains(request)) {
            data.pendingHeadersRequests
          } else {
            client ! request
            self ! response
            data.pendingHeadersRequests + request
          }
          stay() using data.copy(pendingHeadersRequests = pendingHeadersRequest1)
      }

    case Event(bc@ElectrumClient.BroadcastTransaction(tx), _) =>
      log.info(s"broadcasting txid=${tx.txid}")
      client forward bc
      stay

    case Event(CommitTransaction(tx), data) =>
      log.info(s"committing txid=${tx.txid}")
      val data1 = data.commitTransaction(tx)
      // we use the initial state to compute the effect of the tx
      // note: we know that computeTransactionDelta and the fee will be defined, because we built the tx ourselves so
      // we know all the parents
      val (received, sent, Some(fee)) = data.computeTransactionDelta(tx).get
      // we notify here because the tx won't be downloaded again (it has been added to the state at commit)
      context.system.eventStream.publish(data1.transactionReceived(tx, Some(fee), received, sent))
      stay using persistAndNotify(data1) replying true
  }

  whenUnhandled {
    case Event(IsDoubleSpent(tx), data) =>
      // detect if one of our transaction (i.e a transaction that spends from our wallet) has been double-spent
      val isDoubleSpent = data.heights
        .filter { case (_, height) => computeDepth(data.blockchain.height, height) >= 2 } // we only consider tx that have been confirmed
        .flatMap { case (txid, _) => data.transactions.get(txid) } // we get the full tx
        .exists(spendingTx => spendingTx.txIn.map(_.outPoint).toSet.intersect(tx.txIn.map(_.outPoint).toSet).nonEmpty && spendingTx.txid != tx.txid) // look for a tx that spend the same utxos and has a different txid
      stay() replying IsDoubleSpentResponse(tx, data.computeTransactionDepth(tx.txid), isDoubleSpent)

    case Event(ElectrumClient.ElectrumDisconnected, data) =>
      log.info(s"wallet got disconnected")
      // remove status for each script hash for which we have pending requests
      // this will make us query script hash history for these script hashes again when we reconnect
      goto(DISCONNECTED) using data.copy(
        status = data.status -- data.pendingHistoryRequests,
        pendingHistoryRequests = Set(),
        pendingTransactionRequests = Set(),
        pendingHeadersRequests = Set(),
        lastReadyMessage = None
      )

    case Event(GetCurrentReceiveAddresses, data) => stay replying GetCurrentReceiveAddressesResponse(data.currentReceiveAddresses)

    case Event(GetBalance, data) => stay replying data.balance

    case Event(CompleteTransaction(tx, feeRatePerKw, sequenceFlag), data) =>
      Try(data.completeTransaction(tx, feeRatePerKw, params.dustLimit, params.allowSpendUnconfirmed, sequenceFlag)) match {
        case Success(txAndFee) => stay replying CompleteTransactionResponse(Some(txAndFee))
        case _ => stay replying CompleteTransactionResponse(None)
      }

    case Event(SendAll(publicKeyScript, extraUtxos, feeRatePerKw, sequenceFlag), data) =>
      Try(data.spendAll(publicKeyScript, extraUtxos, feeRatePerKw, params.dustLimit, sequenceFlag)) match {
        case Success(txAndFee) => stay replying SendAllResponse(Some(txAndFee))
        case _ => stay replying SendAllResponse(None)
      }

    case Event(ElectrumClient.BroadcastTransaction(tx), _) =>
      val notConnected = Some(Error(code = -1, "wallet is not connected"))
      stay replying ElectrumClient.BroadcastTransactionResponse(tx, notConnected)
  }

  initialize()
}

object ElectrumWallet {
  type TransactionHistoryItemList = List[ElectrumClient.TransactionHistoryItem]

  case class WalletParameters(chainHash: ByteVector32, walletDb: WalletDb, dustLimit: Satoshi, swipeRange: Int, allowSpendUnconfirmed: Boolean)

  sealed trait State

  case object DISCONNECTED extends State

  case object WAITING_FOR_TIP extends State

  case object SYNCING extends State

  case object RUNNING extends State

  sealed trait Request

  sealed trait Response

  case object GetBalance extends Request

  case class GetBalanceResponse(confirmed: Satoshi, unconfirmed: Satoshi) extends Response {
    val totalBalance: Satoshi = confirmed + unconfirmed
  }

  case object GetCurrentReceiveAddresses extends Request

  case class GetCurrentReceiveAddressesResponse(a2p: Address2PrivKey) extends Response

  case class CompleteTransaction(tx: Transaction, feeRatePerKw: FeeratePerKw, sequenceFlag: Long) extends Request

  case class CompleteTransactionResponse(result: Option[TxAndFee] = None) extends Response

  case class SendAll(publicKeyScript: ByteVector, extraUtxos: List[TxOut], feeRatePerKw: FeeratePerKw, sequenceFlag: Long) extends Request

  case class SendAllResponse(result: Option[TxAndFee] = None) extends Response

  case class CommitTransaction(tx: Transaction) extends Request

  case class SendTransaction(tx: Transaction) extends Request

  case class SendTransactionReponse(tx: Transaction) extends Response

  case class AmountBelowDustLimit(dustLimit: Satoshi) extends Response

  case class IsDoubleSpent(tx: Transaction) extends Request

  case class IsDoubleSpentResponse(tx: Transaction, depth: Long, isDoubleSpent: Boolean) extends Response

  sealed trait WalletEvent

  case class TransactionReceived(tx: Transaction, depth: Long, received: Satoshi, sent: Satoshi, walletAddreses: List[String], feeOpt: Option[Satoshi] = None) extends WalletEvent

  case class WalletReady(confirmedBalance: Satoshi, unconfirmedBalance: Satoshi, height: Long, timestamp: Long, tag: String) extends WalletEvent

  def totalAmount(utxos: Seq[Utxo] = Nil): Satoshi = utxos.map(_.item.value).sum.sat

  def computeFee(weight: Int, feeRatePerKw: Long): Satoshi = Satoshi(weight * feeRatePerKw / 1000L)

  def computeDepth(currentHeight: Long, txHeight: Long): Long = if (txHeight <= 0L) 0L else currentHeight - txHeight + 1L

  case class Utxo(key: ExtendedPrivateKey, item: ElectrumClient.UnspentItem)
}

abstract class ElectrumWalletType(val tag: String) {
  def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey]

  def textAddress(key: ExtendedPrivateKey, chainHash: ByteVector32): String

  def computePublicKeyScript(key: PublicKey): Seq[ScriptElt]

  def accountPath(chainHash: ByteVector32): List[Long]

  def computeScriptHashFromPublicKey(key: PublicKey): ByteVector32 = {
    val serializedPubKeyScript = Script write computePublicKeyScript(key)
    Crypto.sha256(serializedPubKeyScript).reverse
  }

  def accountKey(master: ExtendedPrivateKey, chainHash: ByteVector32): ExtendedPrivateKey =
    DeterministicWallet.derivePrivateKey(master, accountPath(chainHash) ::: 0L :: Nil)

  def changeKey(master: ExtendedPrivateKey, chainHash: ByteVector32): ExtendedPrivateKey =
    DeterministicWallet.derivePrivateKey(master, accountPath(chainHash) ::: 1L :: Nil)

  def addUtxosWithDummySig(usableUtxos: Seq[Utxo], tx: Transaction, sequenceFlag: Long): Transaction

  def signTransaction(usableUtxos: Seq[Utxo], tx: Transaction): Transaction
}

class ElectrumWallet32 extends ElectrumWalletType(EclairWallet.BIP32) {
  override def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey] = Try {
    val _ :: OP_PUSHDATA(data, _) :: Nil = Script.parse(txIn.signatureScript)
    PublicKey(data)
  }.toOption

  override def textAddress(key: ExtendedPrivateKey, chainHash: ByteVector32): String = computeP2PkhAddress(key.publicKey, chainHash)

  override def computePublicKeyScript(key: PublicKey): Seq[ScriptElt] = Script.pay2pkh(key)

  override def accountPath(chainHash: ByteVector32): List[Long] = chainHash match {
    case Block.RegtestGenesisBlock.hash => hardened(1L) :: 0L :: Nil
    case Block.TestnetGenesisBlock.hash => hardened(1L) :: 0L :: Nil
    case Block.LivenetGenesisBlock.hash => hardened(0L) :: 0L :: Nil
    case _ => throw new RuntimeException
  }

  override def addUtxosWithDummySig(usableUtxos: Seq[Utxo], tx: Transaction, sequenceFlag: Long): Transaction = {
    val dummySig = ByteVector.fill(71)(1)

    val txIn1 = for {
      utxo <- usableUtxos
      sigScript = Script.write(OP_PUSHDATA(dummySig) :: OP_PUSHDATA(utxo.key.publicKey) :: Nil)
    } yield TxIn(utxo.item.outPoint, signatureScript = sigScript, sequenceFlag)
    tx.copy(txIn = txIn1)
  }

  override def signTransaction(usableUtxos: Seq[Utxo], tx: Transaction): Transaction = {
    val txIn1 = for {
      (txIn, idx) <- tx.txIn.zipWithIndex
      utxo <- usableUtxos.find(_.item.outPoint == txIn.outPoint)
      sig = Transaction.signInput(tx, idx, Script.pay2pkh(utxo.key.publicKey), SIGHASH_ALL, utxo.item.value.sat, SigVersion.SIGVERSION_BASE, utxo.key.privateKey)
      sigScript = Script.write(OP_PUSHDATA(sig) :: OP_PUSHDATA(utxo.key.publicKey) :: Nil)
    } yield txIn.copy(signatureScript = sigScript)
    tx.copy(txIn = txIn1)
  }
}

class ElectrumWallet44 extends ElectrumWallet32 {
  override val tag: String = EclairWallet.BIP44

  override def accountPath(chainHash: ByteVector32): List[Long] = chainHash match {
    case Block.RegtestGenesisBlock.hash => hardened(44) :: hardened(1) :: hardened(0) :: Nil
    case Block.TestnetGenesisBlock.hash => hardened(44) :: hardened(1) :: hardened(0) :: Nil
    case Block.LivenetGenesisBlock.hash => hardened(44) :: hardened(0) :: hardened(0) :: Nil
    case _ => throw new RuntimeException
  }
}

class ElectrumWallet49 extends ElectrumWalletType(EclairWallet.BIP49) {
  override def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey] = {
    Try {
      require(txIn.witness.stack.size == 2)
      val publicKey = PublicKey(txIn.witness.stack.tail.head)
      val OP_PUSHDATA(script, _) :: Nil = Script.parse(txIn.signatureScript)
      require(Script.write(Script pay2wpkh publicKey) == script)
      publicKey
    }.toOption
  }

  override def textAddress(key: ExtendedPrivateKey, chainHash: ByteVector32): String = computeBIP49Address(key.publicKey, chainHash)

  override def computePublicKeyScript(key: PublicKey): Seq[ScriptElt] = Script.pay2sh(Script pay2wpkh key)

  override def accountPath(chainHash: ByteVector32): List[Long] = chainHash match {
    case Block.RegtestGenesisBlock.hash => hardened(49) :: hardened(1L) :: hardened(0L) :: Nil
    case Block.TestnetGenesisBlock.hash => hardened(49) :: hardened(1L) :: hardened(0L) :: Nil
    case Block.LivenetGenesisBlock.hash => hardened(49) :: hardened(0L) :: hardened(0L) :: Nil
    case _ => throw new RuntimeException
  }

  override def addUtxosWithDummySig(usableUtxos: Seq[Utxo], tx: Transaction, sequenceFlag: Long): Transaction = {
    val txIn1 = for {
      utxo <- usableUtxos
      pubKeyScript = Script.write(Script pay2wpkh utxo.key.publicKey)
      witness = ScriptWitness(ByteVector.fill(71)(1) :: utxo.key.publicKey.value :: Nil)
    } yield TxIn(utxo.item.outPoint, signatureScript = Script.write(OP_PUSHDATA(pubKeyScript) :: Nil), sequenceFlag, witness)
    tx.copy(txIn = txIn1)
  }

  override def signTransaction(usableUtxos: Seq[Utxo], tx: Transaction): Transaction = {
    val txIn1 = for {
      (txIn, idx) <- tx.txIn.zipWithIndex
      utxo <- usableUtxos.find(_.item.outPoint == txIn.outPoint)
      pubKeyScript = Script.write(Script pay2wpkh utxo.key.publicKey)
      sig = Transaction.signInput(tx, idx, Script.pay2pkh(utxo.key.publicKey), SIGHASH_ALL, utxo.item.value.sat, SigVersion.SIGVERSION_WITNESS_V0, utxo.key.privateKey)
    } yield txIn.copy(signatureScript = Script.write(OP_PUSHDATA(pubKeyScript) :: Nil), witness = ScriptWitness(sig :: utxo.key.publicKey.value :: Nil))
    tx.copy(txIn = txIn1)
  }
}

class ElectrumWallet84 extends ElectrumWalletType(EclairWallet.BIP84) {
  override def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey] = Try(txIn.witness.stack.last).map(PublicKey.apply).toOption

  override def textAddress(key: ExtendedPrivateKey, chainHash: ByteVector32): String = computeBIP84Address(key.publicKey, chainHash)

  override def computePublicKeyScript(key: PublicKey): Seq[ScriptElt] = Script.pay2wpkh(key)

  override def accountPath(chainHash: ByteVector32): List[Long] = chainHash match {
    case Block.RegtestGenesisBlock.hash => hardened(84) :: hardened(1) :: hardened(0) :: Nil
    case Block.TestnetGenesisBlock.hash => hardened(84) :: hardened(1) :: hardened(0) :: Nil
    case Block.LivenetGenesisBlock.hash => hardened(84) :: hardened(0) :: hardened(0) :: Nil
    case _ => throw new RuntimeException
  }

  override def addUtxosWithDummySig(usableUtxos: Seq[Utxo], tx: Transaction, sequenceFlag: Long): Transaction = {
    val txIn1 = for {
      utxo <- usableUtxos
      witness = ScriptWitness(ByteVector.fill(71)(1) :: utxo.key.publicKey.value :: Nil)
    } yield TxIn(utxo.item.outPoint, signatureScript = ByteVector.empty, sequenceFlag, witness)
    tx.copy(txIn = txIn1)
  }

  override def signTransaction(usableUtxos: Seq[Utxo], tx: Transaction): Transaction = {
    val txIn1 = for {
      (txIn, idx) <- tx.txIn.zipWithIndex
      utxo <- usableUtxos.find(_.item.outPoint == txIn.outPoint)
      sig = Transaction.signInput(tx, idx, Script.pay2pkh(utxo.key.publicKey), SIGHASH_ALL, utxo.item.value.sat, SigVersion.SIGVERSION_WITNESS_V0, utxo.key.privateKey)
    } yield txIn.copy(witness = ScriptWitness(sig :: utxo.key.publicKey.value :: Nil), signatureScript = ByteVector.empty)
    tx.copy(txIn = txIn1)
  }
}

case class ElectrumData(ewt: ElectrumWalletType,
                        blockchain: Blockchain, accountKeys: Vector[ExtendedPrivateKey], changeKeys: Vector[ExtendedPrivateKey], status: Map[ByteVector32, String] = Map.empty,
                        transactions: Map[ByteVector32, Transaction] = Map.empty, heights: Map[ByteVector32, Int] = Map.empty, history: Map[ByteVector32, TransactionHistoryItemList] = Map.empty,
                        proofs: Map[ByteVector32, GetMerkleResponse] = Map.empty, pendingHistoryRequests: Set[ByteVector32] = Set.empty, pendingTransactionRequests: Set[ByteVector32] = Set.empty,
                        pendingHeadersRequests: Set[GetHeaders] = Set.empty, pendingTransactions: List[Transaction] = Nil, lastReadyMessage: Option[WalletReady] = None) {

  private val unused = new String

  val toAddress: ExtendedPrivateKey => String = ewt.textAddress(_, blockchain.chainHash)

  lazy val accountKeyMap: Map[ByteVector32, ExtendedPrivateKey] = accountKeys.map(key => ewt.computeScriptHashFromPublicKey(key.publicKey) -> key).toMap

  lazy val changeKeyMap: Map[ByteVector32, ExtendedPrivateKey] = changeKeys.map(key => ewt.computeScriptHashFromPublicKey(key.publicKey) -> key).toMap

  private lazy val firstUnusedAccountKeys = accountKeys.view.filter(key => status get ewt.computeScriptHashFromPublicKey(key.publicKey) contains unused).take(MAX_RECEIVE_ADDRESSES)

  private lazy val firstUnusedChangeKeys = changeKeys.find(key => status get ewt.computeScriptHashFromPublicKey(key.publicKey) contains unused)

  private lazy val publicScriptMap = (accountKeys ++ changeKeys).map { key =>
    val script = ewt.computePublicKeyScript(key.publicKey)
    Script.write(script) -> key
  }.toMap

  lazy val utxos: Seq[Utxo] = history.keys.toSeq.flatMap(getUtxos)

  def isReady(swipeRange: Int): Boolean = status.values.count(_.isEmpty) >= swipeRange * 2 && pendingTransactionRequests.isEmpty && pendingHistoryRequests.isEmpty

  def generateReadyMessage: WalletReady = WalletReady(balance.confirmed, balance.unconfirmed, blockchain.tip.height, blockchain.tip.header.time, ewt.tag)

  def isAlien(txid: ByteVector32): Boolean = !transactions.contains(txid) && !pendingTransactionRequests.contains(txid) && !pendingTransactions.exists(_.txid == txid)

  def currentReceiveAddresses: Address2PrivKey = {
    val privateKeys = if (firstUnusedAccountKeys.isEmpty) accountKeys.take(MAX_RECEIVE_ADDRESSES) else firstUnusedAccountKeys
    privateKeys.map(toAddress).zip(privateKeys).toMap
  }

  def currentChangeKey: ExtendedPrivateKey = firstUnusedChangeKeys.getOrElse(changeKeys.head)

  def isMine(txIn: TxIn): Boolean = ewt.extractPubKeySpentFrom(txIn).map(ewt.computePublicKeyScript).map(Script.write).exists(publicScriptMap.contains)

  def isSpend(txIn: TxIn, scriptHash: ByteVector32): Boolean = ewt.extractPubKeySpentFrom(txIn).exists(pub => ewt.computeScriptHashFromPublicKey(pub) == scriptHash)

  def isReceive(txOut: TxOut, scriptHash: ByteVector32): Boolean = publicScriptMap.get(txOut.publicKeyScript).exists(key => ewt.computeScriptHashFromPublicKey(key.publicKey) == scriptHash)

  def isMine(txOut: TxOut): Boolean = publicScriptMap.contains(txOut.publicKeyScript)

  def computeTransactionDepth(txid: ByteVector32): Long = heights.get(txid).map(height => if (height > 0) computeDepth(blockchain.tip.height, height) else 0).getOrElse(0)

  def accountOrChangeKey(scriptHash: ByteVector32): ExtendedPrivateKey = accountKeyMap.get(scriptHash) match { case None => changeKeyMap(scriptHash) case Some(key) => key }

  def toPersitent: PersistentData = PersistentData(accountKeys.length, changeKeys.length, status, transactions, heights, history, proofs, pendingTransactions)

  def getUtxos(scriptHash: ByteVector32): Seq[Utxo] =
    history.get(scriptHash) match {
      case Some(Nil) => Nil
      case None => Nil

      case Some(historyItems) =>
        // This is the private key for this script hash
        val key = accountOrChangeKey(scriptHash)

        val unspents = for {
          item <- historyItems
          tx <- transactions.get(item.txHash).toList
          (txOut, index) <- tx.txOut.zipWithIndex if isReceive(txOut, scriptHash)
          unspent = ElectrumClient.UnspentItem(item.txHash, index, txOut.amount.toLong, item.height)
        } yield Utxo(key, unspent)

        // Find all transactions that send to or receive from this script hash
        val txs = historyItems.flatMap(transactions get _.txHash).flatMap(_.txIn).map(_.outPoint)
        // Because we may have unconfirmed UTXOs that are spend by unconfirmed transactions
        unspents.filterNot(utxo => txs contains utxo.item.outPoint)
    }

  def calculateBalance(scriptHash: ByteVector32): (Satoshi, Satoshi) =
    history.get(scriptHash) match {
      case Some(Nil) => (0.sat, 0.sat)
      case None => (0.sat, 0.sat)

      case Some(items) =>
        val (confirmedItems, unconfirmedItems) = items.partition(_.height > 0)
        val confirmedTxs = confirmedItems.map(_.txHash).flatMap(transactions.get)
        val unconfirmedTxs = unconfirmedItems.map(_.txHash).flatMap(transactions.get)

        def findOurSpentOutputs(txs: Seq[Transaction] = Nil): Seq[TxOut] = for {
          input <- txs.flatMap(_.txIn) if isSpend(input, scriptHash)
          transaction <- transactions.get(input.outPoint.txid)
        } yield transaction.txOut(input.outPoint.index.toInt)

        val confirmedSpents = findOurSpentOutputs(confirmedTxs)
        val unconfirmedSpents = findOurSpentOutputs(unconfirmedTxs)

        val received = isReceive(_: TxOut, scriptHash)
        val confirmedReceived = confirmedTxs.flatMap(_.txOut).filter(received)
        val unconfirmedReceived = unconfirmedTxs.flatMap(_.txOut).filter(received)

        val confirmedBalance = confirmedReceived.map(_.amount).sum - confirmedSpents.map(_.amount).sum
        val unconfirmedBalance = unconfirmedReceived.map(_.amount).sum - unconfirmedSpents.map(_.amount).sum
        (confirmedBalance, unconfirmedBalance)
    }

  lazy val balance: GetBalanceResponse = {
    val startState = GetBalanceResponse(0L.sat, 0L.sat)
    (accountKeyMap.keys ++ changeKeyMap.keys).map(calculateBalance).foldLeft(startState) {
      case (GetBalanceResponse(confirmed, unconfirmed), confirmed1 ~ unconfirmed1) =>
        GetBalanceResponse(confirmed + confirmed1, unconfirmed + unconfirmed1)
    }
  }

  def transactionReceived(tx: Transaction, feeOpt: Option[Satoshi], received: Satoshi, sent: Satoshi): TransactionReceived = {
    val walletAddresses = tx.txOut.filter(isMine).map(_.publicKeyScript).flatMap(publicScriptMap.get).map(toAddress)
    TransactionReceived(tx, computeTransactionDepth(tx.txid), received, sent, walletAddresses.toList, feeOpt)
  }

  type SatOpt = Option[Satoshi]
  type ReceivedSentFee = (Satoshi, Satoshi, SatOpt)
  def computeTransactionDelta(tx: Transaction): Option[ReceivedSentFee] = {
    // Computes the effect of this transaction on the wallet
    val ourInputs = tx.txIn.filter(isMine)

    val missingParent = ourInputs.exists { txIn =>
      !transactions.contains(txIn.outPoint.txid)
    }

    if (missingParent) None else {
      val received = tx.txOut.filter(isMine).map(_.amount).sum
      val sent = ourInputs.map(txIn => transactions(txIn.outPoint.txid) txOut txIn.outPoint.index.toInt).map(_.amount).sum
      val feeOpt = if (ourInputs.size == tx.txIn.size) Some(sent - tx.txOut.map(_.amount).sum) else None
      (received, sent, feeOpt).asSome
    }
  }

  def completeTransaction(tx: Transaction, feeRatePerKw: FeeratePerKw, dustLimit: Satoshi, allowSpendUnconfirmed: Boolean, sequenceFlag: Long): TxAndFee = {
    val usable = if (allowSpendUnconfirmed) utxos.sortBy(_.item.value) else utxos.filter(_.item.height > 0).sortBy(_.item.value)
    val amount = tx.txOut.map(_.amount).sum

    def computeFee(candidates: Seq[Utxo], change: Option[TxOut] = None): Satoshi = {
      val tx1 = ewt.addUtxosWithDummySig(usableUtxos = candidates, tx, sequenceFlag = sequenceFlag)
      val weight = change.map(tx1.addOutput).getOrElse(tx1).weight(Protocol.PROTOCOL_VERSION)
      Transactions.weight2fee(feeRatePerKw, weight)
    }

    val dummyScript = ewt.computePublicKeyScript(currentChangeKey.publicKey)
    val dummyChange = TxOut(Satoshi(0), dummyScript)

    @tailrec
    def loop(current: Seq[Utxo], remaining: Seq[Utxo] = Nil): (Seq[Utxo], Option[TxOut]) = totalAmount(current) match {
      case total if total - computeFee(current, None) < amount && remaining.isEmpty => throw new RuntimeException("Insufficient funds")
      case total if total - computeFee(current, None) < amount => loop(remaining.head +: current, remaining.tail)

      case total if total - computeFee(current, None) <= amount + dustLimit => (current, None)
      case total if total - computeFee(current, dummyChange.asSome) <= amount + dustLimit && remaining.isEmpty => (current, None)
      case total if total - computeFee(current, dummyChange.asSome) <= amount + dustLimit => loop(remaining.head +: current, remaining.tail)
      case total => (current, dummyChange.copy(amount = total - computeFee(current, dummyChange.asSome) - amount).asSome)
    }

    val (selected, changeOpt) = loop(Seq.empty, usable)
    val tx1 = ewt.addUtxosWithDummySig(selected, tx, sequenceFlag)
    val tx2 = changeOpt.map(tx1.addOutput).getOrElse(tx1)
    val tx3 = ewt.signTransaction(utxos, tx2)

    val fee = selected.map(_.item.value.sat).sum - tx3.txOut.map(_.amount).sum
    require(tx.txIn.isEmpty, "Cannot complete a tx that already has inputs")
    require(amount > dustLimit, "Amount to send is below dust limit")
    TxAndFee(tx3, fee)
  }

  def commitTransaction(tx: Transaction): ElectrumData = {
    // Remove all our utxos spent by this tx, call this method if the tx was broadcast successfully.
    // Since we base our utxos computation on the history from server, we need to update the history right away if we want to be able to build chained unconfirmed transactions.
    // A few seconds later electrum will notify us and the entry will be overwritten. Note that we need to take into account both inputs and outputs, because there may be change.
    val incomingScripts = tx.txIn.filter(isMine).flatMap(ewt.extractPubKeySpentFrom).map(ewt.computeScriptHashFromPublicKey)
    val outgoingScripts = tx.txOut.filter(isMine).map(_.publicKeyScript).map(computeScriptHash)
    val scripts = incomingScripts ++ outgoingScripts

    val history2 =
      scripts.foldLeft(history) {
        case (history1, scriptHash) =>
          val entry = history1.get(scriptHash) match {
            case Some(items) if items.map(_.txHash).contains(tx.txid) => items
            case Some(items) => TransactionHistoryItem(0, tx.txid) :: items
            case None => TransactionHistoryItem(0, tx.txid) :: Nil
          }

          history1.updated(scriptHash, entry)
      }

    copy(transactions = transactions + (tx.txid -> tx),
      heights = heights + (tx.txid -> 0),
      history = history2)
  }

  def spendAll(publicKeyScript: ByteVector, extraUtxos: List[TxOut], feeRatePerKw: FeeratePerKw, dustLimit: Satoshi, sequenceFlag: Long): TxAndFee = {
    val tx1 = ewt.addUtxosWithDummySig(utxos, Transaction(version = 2, Nil, TxOut(balance.totalBalance, publicKeyScript) :: extraUtxos, lockTime = 0), sequenceFlag)
    val fee = Transactions.weight2fee(weight = tx1.weight(Protocol.PROTOCOL_VERSION), feeratePerKw = feeRatePerKw)
    require(balance.totalBalance - fee > dustLimit, "Resulting tx amount to send is below dust limit")
    val tx2 = tx1.copy(txOut = TxOut(balance.totalBalance - fee, publicKeyScript) :: extraUtxos)
    TxAndFee(ewt.signTransaction(utxos, tx2), fee)
  }
}

case class PersistentData(accountKeysCount: Int, changeKeysCount: Int, status: Map[ByteVector32, String], transactions: Map[ByteVector32, Transaction],
                          heights: Map[ByteVector32, Int], history: Map[ByteVector32, TransactionHistoryItemList], proofs: Map[ByteVector32, GetMerkleResponse],
                          pendingTransactions: List[Transaction] = Nil)
