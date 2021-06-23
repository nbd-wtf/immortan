package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin._
import immortan.crypto.Tools._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.eclair.blockchain.EclairWallet._
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._

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


class ChainSync(client: ActorRef, walletDb: WalletDb, chainHash: ByteVector32) extends FSM[ElectrumWallet.State, Blockchain] {

  def loadChain: Blockchain = if (chainHash != Block.RegtestGenesisBlock.hash) {
    val blockchain = Blockchain.fromCheckpoints(checkpoints = CheckPoint.load(chainHash, walletDb), chainhash = chainHash)
    val headers = walletDb.getHeaders(blockchain.checkpoints.size * RETARGETING_PERIOD, maxCount = Int.MaxValue)
    Blockchain.addHeadersChunk(blockchain, height = blockchain.checkpoints.size * RETARGETING_PERIOD, headers)
  } else Blockchain.fromGenesisBlock(Block.RegtestGenesisBlock.hash, Block.RegtestGenesisBlock.header)

  client ! ElectrumClient.AddStatusListener(self)

  startWith(DISCONNECTED, loadChain)

  when(DISCONNECTED) {
    case Event(_: ElectrumClient.ElectrumReady, blockchain) =>
      client ! ElectrumClient.HeaderSubscription(self)
      goto(WAITING_FOR_TIP) using blockchain
  }

  when(WAITING_FOR_TIP) {
    case Event(response: ElectrumClient.HeaderSubscriptionResponse, blockchain) if response.height < blockchain.height =>
      log.info("Electrum peer is behind us, killing and switching to new one")
      goto(DISCONNECTED) replying PoisonPill

    case Event(_: ElectrumClient.HeaderSubscriptionResponse, blockchain) if blockchain.bestchain.isEmpty =>
      client ! ElectrumClient.GetHeaders(blockchain.checkpoints.size * RETARGETING_PERIOD, RETARGETING_PERIOD)
      log.info("Performing full chain sync")
      goto(SYNCING)

    case Event(response: ElectrumClient.HeaderSubscriptionResponse, blockchain) if response.header == blockchain.tip.header =>
      context.system.eventStream.publish(blockchain)
      goto(RUNNING)

    case Event(response: ElectrumClient.HeaderSubscriptionResponse, blockchain) =>
      client ! ElectrumClient.GetHeaders(blockchain.tip.height + 1, RETARGETING_PERIOD)
      log.info(s"Syncing headers ${blockchain.height} to ${response.height}")
      goto(SYNCING)
  }

  when(SYNCING) {
    case Event(respone: ElectrumClient.GetHeadersResponse, blockchain) if respone.headers.isEmpty =>
      context.system.eventStream.publish(blockchain)
      goto(RUNNING)

    case Event(ElectrumClient.GetHeadersResponse(start, headers, _), blockchain) =>
      val blockchain1Try = Try apply Blockchain.addHeaders(blockchain, start, headers)

      blockchain1Try match {
        case Success(blockchain1) =>
          val (blockchain2, chunks) = Blockchain.optimize(blockchain1)
          for (chunk <- chunks grouped RETARGETING_PERIOD) walletDb.addHeaders(chunk.map(_.header), chunk.head.height)
          log.info(s"Got new headers chunk at ${blockchain2.tip.height}, requesting next chunk")
          client ! ElectrumClient.GetHeaders(blockchain2.tip.height + 1, RETARGETING_PERIOD)
          goto(SYNCING) using blockchain2

        case Failure(error) =>
          log.error("Electrum peer sent bad headers", error)
          goto(DISCONNECTED) replying PoisonPill
      }

    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), _) =>
      log.debug(s"Ignoring header $header at $height while syncing")
      stay
  }

  when(RUNNING) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), blockchain) if blockchain.tip.header != header =>
      val difficultyOk = Blockchain.getDifficulty(blockchain, height, walletDb).forall(header.bits.==)
      val blockchain1Try = Try apply Blockchain.addHeader(blockchain, height, header)

      blockchain1Try match {
        case Success(blockchain1) if difficultyOk =>
          val (blockchain2, chunks) = Blockchain.optimize(blockchain1)
          for (chunk <- chunks grouped RETARGETING_PERIOD) walletDb.addHeaders(chunk.map(_.header), chunk.head.height)
          log.info(s"Got new chain tip ${header.blockId} at $height, obtained header is $header, publishing chain")
          context.system.eventStream.publish(blockchain2)
          stay using blockchain2

        case _ =>
          log.error("Electrum peer sent bad headers")
          stay replying PoisonPill
      }

    case Event(ElectrumClient.GetHeadersResponse(start, headers, _), blockchain) =>
      val blockchain1Try = Try apply Blockchain.addHeaders(blockchain, start, headers)

      blockchain1Try match {
        case Success(blockchain1) =>
          walletDb.addHeaders(headers, start)
          context.system.eventStream.publish(blockchain1)
          stay using blockchain1

        case _ =>
          log.error("Electrum peer sent bad headers")
          stay replying PoisonPill
      }
  }

  whenUnhandled {
    case Event(getHeaders: GetHeaders, _) =>
      client ! getHeaders
      stay

    case Event(ElectrumClient.ElectrumDisconnected, _) =>
      goto(DISCONNECTED)
  }

  initialize
}

class ElectrumWallet(client: ActorRef, chainSync: ActorRef, params: ElectrumWallet.WalletParameters, ewt: ElectrumWalletType) extends FSM[ElectrumWallet.State, ElectrumData] {

  def persistAndNotify(data: ElectrumData): ElectrumData = {
    if (data.lastReadyMessage contains data.currentReadyMessage) data
    else doPersistAndNotify(data)
  }

  def doPersistAndNotify(data: ElectrumData): ElectrumData = {
    context.system.eventStream.publish(data.currentReadyMessage)
    params.walletDb.persist(data.toPersistentElectrumData, ewt.tag)
    data.copy(lastReadyMessage = data.currentReadyMessage.asSome)
  }

  context.system.eventStream.subscribe(channel = classOf[Blockchain], subscriber = self)

  client ! ElectrumClient.AddStatusListener(self)

  startWith(DISCONNECTED, null)

  when(DISCONNECTED) {
    case Event(persisted: PersistentData, null) =>
      val blockChain = Blockchain(ewt.chainHash, checkpoints = Vector.empty, headersMap = Map.empty, bestchain = Vector.empty)
      val firstAccountKeys = for (idx <- 0 until persisted.accountKeysCount) yield derivePublicKey(ewt.accountMaster, idx)
      val firstChangeKeys = for (idx <- 0 until persisted.changeKeysCount) yield derivePublicKey(ewt.changeMaster, idx)

      val data1 = ElectrumData(ewt, blockChain, firstAccountKeys.toVector, firstChangeKeys.toVector, persisted.status, persisted.transactions,
        persisted.heights, persisted.history, persisted.proofs, pendingHistoryRequests = Set.empty, pendingHeadersRequests = Set.empty,
        pendingTransactionRequests = Set.empty, pendingTransactions = persisted.pendingTransactions)
      stay using data1

    case Event(FreshWallet, null) =>
      val blockChain = Blockchain(ewt.chainHash, checkpoints = Vector.empty, headersMap = Map.empty, bestchain = Vector.empty)
      val firstAccountKeys = for (idx <- 0 until params.swipeRange) yield derivePublicKey(ewt.accountMaster, idx)
      val firstChangeKeys = for (idx <- 0 until params.swipeRange) yield derivePublicKey(ewt.changeMaster, idx)
      stay using ElectrumData(ewt, blockChain, firstAccountKeys.toVector, firstChangeKeys.toVector)

    case Event(blockchain1: Blockchain, data) =>
      val data1 = data.copy(blockchain = blockchain1)
      goto(RUNNING) using persistAndNotify(data1)
  }

  when(RUNNING) {
    case Event(blockchain1: Blockchain, data) =>
      val data1 = data.copy(blockchain = blockchain1)
      stay using persistAndNotify(data1)

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if data.status.get(scriptHash).contains(status) =>
      val missing = data.history.getOrElse(scriptHash, Nil).map(_.txHash).filterNot(data.transactions.contains).toSet -- data.pendingTransactionRequests
      val data1 = data.copy(pendingHistoryRequests = data.pendingTransactionRequests ++ missing)
      for (txid <- missing) client ! GetTransaction(txid)
      stay using persistAndNotify(data1)

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if !data.accountKeyMap.contains(scriptHash) && !data.changeKeyMap.contains(scriptHash) =>
      log.warning(s"Received status $status for scriptHash $scriptHash which does not match any of our keys")
      stay

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if status.isEmpty =>
      val status1 = data.status.updated(scriptHash, status)
      val data1 = data.copy(status = status1)
      stay using persistAndNotify(data1)

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) =>
      val data1 = data.copy(status = data.status.updated(scriptHash, status), pendingHistoryRequests = data.pendingHistoryRequests + scriptHash)
      val statusBytes = Try(ByteVector32 fromValidHex status).getOrElse(ByteVector32.Zeroes)
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)

      data.status.contains(statusBytes) match {
        case false if data.changeKeyMap.contains(scriptHash) =>
          val newKey = derivePublicKey(ewt.changeMaster, data.changeKeys.last.path.lastChildNumber + 1)
          client ! ElectrumClient.ScriptHashSubscription(ewt.computeScriptHashFromPublicKey(newKey.publicKey), self)
          val data2 = data1.copy(changeKeys = data1.changeKeys :+ newKey)
          stay using persistAndNotify(data2)

        case false =>
          val newKey = derivePublicKey(ewt.accountMaster, data.accountKeys.last.path.lastChildNumber + 1)
          client ! ElectrumClient.ScriptHashSubscription(ewt.computeScriptHashFromPublicKey(newKey.publicKey), self)
          val data2 = data1.copy(accountKeys = data1.accountKeys :+ newKey)
          stay using persistAndNotify(data2)

        case true =>
          // This is not a new scriptHash
          stay using persistAndNotify(data1)
      }

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
            chainSync ! request
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

    case Event(GetTransactionResponse(tx, contextOpt), data) =>
      val data1 = data.copy(pendingTransactionRequests = data.pendingTransactionRequests - tx.txid)

      data.computeTransactionDelta(tx).map { case (received, sent, feeOpt) =>
        for (pendingTx <- data.pendingTransactions) self ! GetTransactionResponse(pendingTx, contextOpt)
        context.system.eventStream publish data.transactionReceived(tx, feeOpt, received, sent, ewt.xPub)
        val data2 = data1.copy(transactions = data.transactions.updated(tx.txid, tx), pendingTransactions = Nil)
        stay using persistAndNotify(data2)
      } getOrElse {
        // We are currently missing parents for this transaction
        val data2 = data1.copy(pendingTransactions = data.pendingTransactions :+ tx)
        stay using persistAndNotify(data2)
      }

    case Event(ServerError(GetTransaction(txid, _), error), data) if data.pendingTransactionRequests.contains(txid) =>
      log.error(s"Electrum server cannot find history for txid $txid with error $error")
      goto(DISCONNECTED) replying PoisonPill

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
            chainSync ! request
            self ! response
            data.pendingHeadersRequests + request
          }
          stay() using data.copy(pendingHeadersRequests = pendingHeadersRequest1)
      }

    case Event(bc: ElectrumClient.BroadcastTransaction, _) =>
      client forward bc
      stay

    case Event(commit: CommitTransaction, data) =>
      val data1 = data.commitTransaction(commit.tx)
      // We use the initial state to compute the effect of the tx
      val (received, sent, fee) = data.computeTransactionDelta(commit.tx).get
      // We notify here because the tx won't be downloaded again (it has been added to the state at commit)
      context.system.eventStream publish data1.transactionReceived(commit.tx, fee, received, sent, ewt.xPub)
      stay using persistAndNotify(data1) replying true
  }

  whenUnhandled {
    case Event(IsDoubleSpent(tx), data) =>
      // detect if one of our transaction (i.e a transaction that spends from our wallet) has been double-spent
      val isDoubleSpent = data.heights
        .filter { case (_, height) => computeDepth(data.blockchain.height, height) >= 2 } // we only consider tx that have been confirmed
        .flatMap { case (txid, _) => data.transactions.get(txid) } // we get the full tx
        .exists(spendingTx => spendingTx.txIn.map(_.outPoint).toSet.intersect(tx.txIn.map(_.outPoint).toSet).nonEmpty && spendingTx.txid != tx.txid) // look for a tx that spend the same utxos and has a different txid
      stay replying IsDoubleSpentResponse(tx, data.computeTransactionDepth(tx.txid), isDoubleSpent)

    case Event(ElectrumClient.ElectrumDisconnected, data) =>
      log.info(s"wallet got disconnected")
      // remove status for each script hash for which we have pending requests
      // this will make us query script hash history for these script hashes again when we reconnect
      goto(DISCONNECTED) using data.copy(
        status = data.status -- data.pendingHistoryRequests,
        pendingHistoryRequests = Set.empty,
        pendingTransactionRequests = Set.empty,
        pendingHeadersRequests = Set.empty,
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

  case class WalletParameters(walletDb: WalletDb, dustLimit: Satoshi, swipeRange: Int, allowSpendUnconfirmed: Boolean)

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

  case class GetCurrentReceiveAddressesResponse(a2p: Address2PubKey) extends Response

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

  case class TransactionReceived(tx: Transaction, depth: Long, received: Satoshi, sent: Satoshi, walletAddreses: List[String], xPub: ExtendedPublicKey, feeOpt: Option[Satoshi] = None) extends WalletEvent

  case class WalletReady(confirmedBalance: Satoshi, unconfirmedBalance: Satoshi, height: Long, timestamp: Long, tag: String) extends WalletEvent

  def totalAmount(utxos: Seq[Utxo] = Nil): Satoshi = utxos.map(_.item.value).sum.sat

  def computeFee(weight: Int, feeRatePerKw: Long): Satoshi = Satoshi(weight * feeRatePerKw / 1000L)

  def computeDepth(currentHeight: Long, txHeight: Long): Long = if (txHeight <= 0L) 0L else currentHeight - txHeight + 1L

  case class Utxo(key: ExtendedPublicKey, item: ElectrumClient.UnspentItem)

  case class AccountAndXPrivKey(xPriv: ExtendedPrivateKey, master: ExtendedPrivateKey)
}

object ElectrumWalletType {
  def make(tag: String, seed: ByteVector, chainHash: ByteVector32): ElectrumWalletType = tag match {
    case EclairWallet.BIP32 => makeSigningWallet(EclairWallet.BIP32, xPriv32(generate(seed), chainHash), chainHash)
    case EclairWallet.BIP44 => makeSigningWallet(EclairWallet.BIP44, xPriv44(generate(seed), chainHash), chainHash)
    case EclairWallet.BIP49 => makeSigningWallet(EclairWallet.BIP49, xPriv49(generate(seed), chainHash), chainHash)
    case EclairWallet.BIP84 => makeSigningWallet(EclairWallet.BIP84, xPriv84(generate(seed), chainHash), chainHash)
    case _ => throw new RuntimeException
  }

  def makeSigningWallet(tag: String, secrets: AccountAndXPrivKey, chainHash: ByteVector32): ElectrumWalletType = tag match {
    case EclairWallet.BIP32 => ElectrumWallet32(secrets.asSome, publicKey(secrets.xPriv), chainHash)
    case EclairWallet.BIP44 => ElectrumWallet32(secrets.asSome, publicKey(secrets.xPriv), chainHash)
    case EclairWallet.BIP49 => ElectrumWallet49(secrets.asSome, publicKey(secrets.xPriv), chainHash)
    case EclairWallet.BIP84 => ElectrumWallet84(secrets.asSome, publicKey(secrets.xPriv), chainHash)
    case _ => throw new RuntimeException
  }

  def makeWatchingWallet(tag: String, xPub: ExtendedPublicKey, chainHash: ByteVector32): ElectrumWalletType = tag match {
    case EclairWallet.BIP32 => ElectrumWallet32(secrets = None, xPub, chainHash)
    case EclairWallet.BIP44 => ElectrumWallet32(secrets = None, xPub, chainHash)
    case EclairWallet.BIP49 => ElectrumWallet49(secrets = None, xPub, chainHash)
    case EclairWallet.BIP84 => ElectrumWallet84(secrets = None, xPub, chainHash)
    case _ => throw new RuntimeException
  }

  def xPriv32(master: ExtendedPrivateKey, chainHash: ByteVector32): AccountAndXPrivKey = chainHash match {
    case Block.RegtestGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(1L) :: 0L :: Nil), master)
    case Block.TestnetGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(1L) :: 0L :: Nil), master)
    case Block.LivenetGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(0L) :: 0L :: Nil), master)
    case _ => throw new RuntimeException
  }

  def xPriv44(master: ExtendedPrivateKey, chainHash: ByteVector32): AccountAndXPrivKey = chainHash match {
    case Block.RegtestGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(44L) :: hardened(1L) :: hardened(0L) :: Nil), master)
    case Block.TestnetGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(44L) :: hardened(1L) :: hardened(0L) :: Nil), master)
    case Block.LivenetGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(44L) :: hardened(0L) :: hardened(0L) :: Nil), master)
    case _ => throw new RuntimeException
  }

  def xPriv49(master: ExtendedPrivateKey, chainHash: ByteVector32): AccountAndXPrivKey = chainHash match {
    case Block.RegtestGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(49L) :: hardened(1L) :: hardened(0L) :: Nil), master)
    case Block.TestnetGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(49L) :: hardened(1L) :: hardened(0L) :: Nil), master)
    case Block.LivenetGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(49L) :: hardened(0L) :: hardened(0L) :: Nil), master)
    case _ => throw new RuntimeException
  }

  def xPriv84(master: ExtendedPrivateKey, chainHash: ByteVector32): AccountAndXPrivKey = chainHash match {
    case Block.RegtestGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(84L) :: hardened(1L) :: hardened(0L) :: Nil), master)
    case Block.TestnetGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(84L) :: hardened(1L) :: hardened(0L) :: Nil), master)
    case Block.LivenetGenesisBlock.hash => AccountAndXPrivKey(derivePrivateKey(master, hardened(84L) :: hardened(0L) :: hardened(0L) :: Nil), master)
    case _ => throw new RuntimeException
  }
}

abstract class ElectrumWalletType(val tag: String) {

  val secrets: Option[AccountAndXPrivKey]

  val xPub: ExtendedPublicKey

  val chainHash: ByteVector32

  val accountMaster: ExtendedPublicKey = derivePublicKey(xPub, 0L :: Nil)

  val changeMaster: ExtendedPublicKey = derivePublicKey(xPub, 1L :: Nil)

  def textAddress(key: ExtendedPublicKey): String

  def computePublicKeyScript(key: PublicKey): Seq[ScriptElt]

  def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey]

  def computeScriptHashFromPublicKey(key: PublicKey): ByteVector32 = {
    val serializedPubKeyScript: Seq[ScriptElt] = computePublicKeyScript(key)
    Crypto.sha256(Script write serializedPubKeyScript).reverse
  }

  def addUtxosWithDummySig(usableUtxos: Seq[Utxo], tx: Transaction, sequenceFlag: Long): Transaction

  def signTransaction(usableUtxos: Seq[Utxo], tx: Transaction): Transaction
}

case class ElectrumWallet32(secrets: Option[AccountAndXPrivKey], xPub: ExtendedPublicKey, chainHash: ByteVector32) extends ElectrumWalletType(EclairWallet.BIP32) {

  override def textAddress(key: ExtendedPublicKey): String = computeP2PkhAddress(key.publicKey, chainHash)

  override def computePublicKeyScript(key: PublicKey): Seq[ScriptElt] = Script.pay2pkh(key)

  override def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey] = Try {
    val _ :: OP_PUSHDATA(data, _) :: Nil = Script.parse(txIn.signatureScript)
    PublicKey(data)
  }.toOption

  override def addUtxosWithDummySig(usableUtxos: Seq[Utxo], tx: Transaction, sequenceFlag: Long): Transaction = {
    val txIn1 = for {
      utxo <- usableUtxos
      dummySig = ByteVector.fill(71)(1)
      sigScript = Script.write(OP_PUSHDATA(dummySig) :: OP_PUSHDATA(utxo.key.publicKey) :: Nil)
    } yield TxIn(utxo.item.outPoint, sigScript, sequenceFlag)
    tx.copy(txIn = txIn1)
  }

  override def signTransaction(usableUtxos: Seq[Utxo], tx: Transaction): Transaction = {
    val txIn1 = for {
      (txIn, idx) <- tx.txIn.zipWithIndex
      utxo <- usableUtxos.find(_.item.outPoint == txIn.outPoint)
      previousOutputScript = Script.pay2pkh(pubKey = utxo.key.publicKey)
      privateKey = derivePrivateKey(secrets.get.master, utxo.key.path).privateKey
      sig = Transaction.signInput(tx, idx, previousOutputScript, SIGHASH_ALL, utxo.item.value.sat, SigVersion.SIGVERSION_BASE, privateKey)
      sigScript = Script.write(OP_PUSHDATA(sig) :: OP_PUSHDATA(utxo.key.publicKey) :: Nil)
    } yield txIn.copy(signatureScript = sigScript)
    tx.copy(txIn = txIn1)
  }
}

case class ElectrumWallet49(secrets: Option[AccountAndXPrivKey], xPub: ExtendedPublicKey, chainHash: ByteVector32) extends ElectrumWalletType(EclairWallet.BIP49) {

  override def textAddress(key: ExtendedPublicKey): String = computeBIP49Address(key.publicKey, chainHash)

  override def computePublicKeyScript(key: PublicKey): Seq[ScriptElt] = Script.pay2sh(Script pay2wpkh key)

  override def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey] = {
    Try {
      require(txIn.witness.stack.size == 2)
      val publicKey = PublicKey(txIn.witness.stack.tail.head)
      val OP_PUSHDATA(script, _) :: Nil = Script.parse(txIn.signatureScript)
      require(Script.write(Script pay2wpkh publicKey) == script)
      publicKey
    }.toOption
  }

  override def addUtxosWithDummySig(usableUtxos: Seq[Utxo], tx: Transaction, sequenceFlag: Long): Transaction = {
    val txIn1 = for {
      utxo <- usableUtxos
      pubKeyScript = Script.write(Script pay2wpkh utxo.key.publicKey)
      witness = ScriptWitness(ByteVector.fill(71)(1) :: utxo.key.publicKey.value :: Nil)
    } yield TxIn(utxo.item.outPoint, Script.write(OP_PUSHDATA(pubKeyScript) :: Nil), sequenceFlag, witness)
    tx.copy(txIn = txIn1)
  }

  override def signTransaction(usableUtxos: Seq[Utxo], tx: Transaction): Transaction = {
    val txIn1 = for {
      (txIn, idx) <- tx.txIn.zipWithIndex
      utxo <- usableUtxos.find(_.item.outPoint == txIn.outPoint)
      pubKeyScript = Script.write(Script pay2wpkh utxo.key.publicKey)
      privateKey = derivePrivateKey(secrets.get.master, utxo.key.path).privateKey
      sig = Transaction.signInput(tx, idx, Script.pay2pkh(utxo.key.publicKey), SIGHASH_ALL, utxo.item.value.sat, SigVersion.SIGVERSION_WITNESS_V0, privateKey)
    } yield txIn.copy(signatureScript = Script.write(OP_PUSHDATA(pubKeyScript) :: Nil), witness = ScriptWitness(sig :: utxo.key.publicKey.value :: Nil))
    tx.copy(txIn = txIn1)
  }
}

case class ElectrumWallet84(secrets: Option[AccountAndXPrivKey], xPub: ExtendedPublicKey, chainHash: ByteVector32) extends ElectrumWalletType(EclairWallet.BIP84) {

  override def textAddress(key: ExtendedPublicKey): String = computeBIP84Address(key.publicKey, chainHash)

  override def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey] = Try(txIn.witness.stack.last).map(PublicKey.apply).toOption

  override def computePublicKeyScript(key: PublicKey): Seq[ScriptElt] = Script.pay2wpkh(key)

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
      privateKey = derivePrivateKey(secrets.get.master, utxo.key.path).privateKey
      sig = Transaction.signInput(tx, idx, Script.pay2pkh(utxo.key.publicKey), SIGHASH_ALL, utxo.item.value.sat, SigVersion.SIGVERSION_WITNESS_V0, privateKey)
    } yield txIn.copy(witness = ScriptWitness(sig :: utxo.key.publicKey.value :: Nil), signatureScript = ByteVector.empty)
    tx.copy(txIn = txIn1)
  }
}

case class ElectrumData(ewt: ElectrumWalletType, blockchain: Blockchain, accountKeys: Vector[ExtendedPublicKey], changeKeys: Vector[ExtendedPublicKey], status: Map[ByteVector32, String] = Map.empty,
                        transactions: Map[ByteVector32, Transaction] = Map.empty, heights: Map[ByteVector32, Int] = Map.empty, history: Map[ByteVector32, TransactionHistoryItemList] = Map.empty,
                        proofs: Map[ByteVector32, GetMerkleResponse] = Map.empty, pendingHistoryRequests: Set[ByteVector32] = Set.empty, pendingTransactionRequests: Set[ByteVector32] = Set.empty,
                        pendingHeadersRequests: Set[GetHeaders] = Set.empty, pendingTransactions: List[Transaction] = Nil, lastReadyMessage: Option[WalletReady] = None) {

  lazy val accountKeyMap: Map[ByteVector32, ExtendedPublicKey] = accountKeys.map(key => ewt.computeScriptHashFromPublicKey(key.publicKey) -> key).toMap

  lazy val changeKeyMap: Map[ByteVector32, ExtendedPublicKey] = changeKeys.map(key => ewt.computeScriptHashFromPublicKey(key.publicKey) -> key).toMap

  lazy val currentReadyMessage: WalletReady = WalletReady(balance.confirmed, balance.unconfirmed, blockchain.tip.height, blockchain.tip.header.time, ewt.tag)

  private lazy val firstUnusedAccountKeys = accountKeys.view.filter(key => status get ewt.computeScriptHashFromPublicKey(key.publicKey) contains new String).take(MAX_RECEIVE_ADDRESSES)

  private lazy val firstUnusedChangeKeys = changeKeys.find(key => status get ewt.computeScriptHashFromPublicKey(key.publicKey) contains new String)

  private lazy val publicScriptMap = (accountKeys ++ changeKeys).map { key =>
    val script = ewt.computePublicKeyScript(key.publicKey)
    Script.write(script) -> key
  }.toMap

  lazy val utxos: Seq[Utxo] = history.keys.toSeq.flatMap(getUtxos)

  def currentReceiveAddresses: Address2PubKey = {
    val privateKeys = if (firstUnusedAccountKeys.isEmpty) accountKeys.take(MAX_RECEIVE_ADDRESSES) else firstUnusedAccountKeys
    privateKeys.map(ewt.textAddress).zip(privateKeys).toMap
  }

  def isMine(txIn: TxIn): Boolean = ewt.extractPubKeySpentFrom(txIn).map(ewt.computePublicKeyScript).map(Script.write).exists(publicScriptMap.contains)

  def isSpend(txIn: TxIn, scriptHash: ByteVector32): Boolean = ewt.extractPubKeySpentFrom(txIn).exists(pub => ewt.computeScriptHashFromPublicKey(pub) == scriptHash)

  def isReceive(txOut: TxOut, scriptHash: ByteVector32): Boolean = publicScriptMap.get(txOut.publicKeyScript).exists(key => ewt.computeScriptHashFromPublicKey(key.publicKey) == scriptHash)

  def isMine(txOut: TxOut): Boolean = publicScriptMap.contains(txOut.publicKeyScript)

  def computeTransactionDepth(txid: ByteVector32): Long = heights.get(txid).map(height => if (height > 0) computeDepth(blockchain.tip.height, height) else 0).getOrElse(0)

  def accountOrChangeKey(scriptHash: ByteVector32): ExtendedPublicKey = accountKeyMap.get(scriptHash) match { case None => changeKeyMap(scriptHash) case Some(key) => key }

  def toPersistentElectrumData: PersistentData = PersistentData(accountKeys.length, changeKeys.length, status, transactions, heights, history, proofs, pendingTransactions)

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

  def transactionReceived(tx: Transaction, feeOpt: Option[Satoshi], received: Satoshi, sent: Satoshi, xPub: ExtendedPublicKey): TransactionReceived = {
    val walletAddresses = tx.txOut.filter(isMine).map(_.publicKeyScript).flatMap(publicScriptMap.get).map(ewt.textAddress).toList
    TransactionReceived(tx, computeTransactionDepth(tx.txid), received, sent, walletAddresses, xPub, feeOpt)
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

    val changeKey = firstUnusedChangeKeys.getOrElse(changeKeys.head)
    val changeScript = ewt.computePublicKeyScript(changeKey.publicKey)
    val changeTxOut = TxOut(Satoshi(0), changeScript)

    @tailrec
    def loop(current: Seq[Utxo], remaining: Seq[Utxo] = Nil): (Seq[Utxo], Option[TxOut]) = totalAmount(current) match {
      case total if total - computeFee(current, None) < amount && remaining.isEmpty => throw new RuntimeException("Insufficient funds")
      case total if total - computeFee(current, None) < amount => loop(remaining.head +: current, remaining.tail)

      case total if total - computeFee(current, None) <= amount + dustLimit => (current, None)
      case total if total - computeFee(current, changeTxOut.asSome) <= amount + dustLimit && remaining.isEmpty => (current, None)
      case total if total - computeFee(current, changeTxOut.asSome) <= amount + dustLimit => loop(remaining.head +: current, remaining.tail)
      case total => (current, changeTxOut.copy(amount = total - computeFee(current, changeTxOut.asSome) - amount).asSome)
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

sealed trait PersistentStatus
case object FreshWallet extends PersistentStatus
case class PersistentData(accountKeysCount: Int, changeKeysCount: Int, status: Map[ByteVector32, String], transactions: Map[ByteVector32, Transaction],
                          heights: Map[ByteVector32, Int], history: Map[ByteVector32, TransactionHistoryItemList], proofs: Map[ByteVector32, GetMerkleResponse],
                          pendingTransactions: List[Transaction] = Nil) extends PersistentStatus
