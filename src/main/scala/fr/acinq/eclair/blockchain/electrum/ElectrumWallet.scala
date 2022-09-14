package fr.acinq.eclair.blockchain.electrum

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Success, Failure, Try}
import scala.concurrent.ExecutionContext.Implicits.global

import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.EclairWallet._
import fr.acinq.eclair.blockchain.bitcoind.rpc.Error
import fr.acinq.eclair.blockchain.electrum.Blockchain.RETARGETING_PERIOD
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb.persistentDataCodec
import fr.acinq.eclair.blockchain.electrum.db.{HeaderDb, WalletDb}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions
import immortan.crypto.Tools._
import immortan.sqlite.SQLiteTx
import scodec.bits.ByteVector

// @formatter:off
/**
 * Simple electrum wallet.
 * See the documentation at https://electrumx-spesmilo.readthedocs.io/en/latest/
 *
 * Typical workflow:
 *
 * client ---- header update ----> wallet
 * client ---- status update ----> wallet
 * client <--- ask history   ----- wallet
 * client ---- history       ----> wallet
 * client <--- ask tx        ----- wallet
 * client ---- tx            ----> wallet
 */
// @formatter:on

class ElectrumWallet(
    pool: ElectrumClientPool,
    chainSync: ElectrumChainSync,
    params: WalletParameters,
    ewt: ElectrumWalletType
) { self =>
  sealed trait State
  case object Disconnected extends State
  case object Running extends State

  var data: ElectrumData = _
  var state: State = Disconnected

  // @formatter:off
  // disconnected --> waitingForTip --> running --+
  // ^                                            |
  // |                                            |
  // +--------------------------------------------+

  /**
   * If the wallet is ready and its state changed since the last time it was ready:
   * - publish a `WalletReady` notification
   * - persist state data
   *
   * @param data wallet data
   * @return the input data with an updated 'last ready message' if needed
   */
  // @formatter:on

  def persistAndNotify(dataInTransit: ElectrumData): Unit = {
    val t = new java.util.Timer()
    val task = new java.util.TimerTask { def run() = { self.keyRefill() } }
    t.schedule(task, 100L)

    if (!data.lastReadyMessage.contains(data.currentReadyMessage)) {
      data = dataInTransit.copy(
        lastReadyMessage = Some(dataInTransit.currentReadyMessage)
      )
      params.walletDb.persist(
        data.toPersistent,
        data.balance,
        ewt.xPub.publicKey
      )

      EventStream.publish(data.currentReadyMessage)
    }
  }

  EventStream
    .subscribe {
      case BlockchainReady(bc) => self.blockchainReady(bc)
      case _: ElectrumDisconnected => {
        state = Disconnected
        data = data.reset
      }
    }

  def load(encoded: ByteVector): Unit =
    if (state == Disconnected) {
      // Serialized data may become big with much usage
      // Deserialzie it in this dedicated thread to not slow down UI
      val persisted = Try(
        persistentDataCodec.decode(encoded.toBitVector).require.value
      ).getOrElse(params.emptyPersistentData)
      val firstAccountKeys =
        for (
          idx <- math.max(
            persisted.accountKeysCount - 1500,
            0
          ) until persisted.accountKeysCount
        ) yield derivePublicKey(ewt.accountMaster, idx)
      val firstChangeKeys =
        for (
          idx <- math.max(
            persisted.changeKeysCount - 1500,
            0
          ) until persisted.changeKeysCount
        ) yield derivePublicKey(ewt.changeMaster, idx)

      data = ElectrumData(
        ewt,
        Blockchain(
          ewt.chainHash,
          checkpoints = Vector.empty,
          headersMap = Map.empty,
          bestchain = Vector.empty
        ),
        firstAccountKeys.toVector,
        firstChangeKeys.toVector,
        persisted.excludedOutPoints,
        persisted.status,
        persisted.transactions,
        persisted.overriddenPendingTxids,
        persisted.history,
        persisted.proofs,
        pendingHistoryRequests = Set.empty,
        pendingHeadersRequests = Set.empty,
        pendingTransactionRequests = Set.empty,
        pendingTransactions = persisted.pendingTransactions
      )
    }

  def blockchainReady(bc: Blockchain): Unit = {
    state match {
      case Disconnected =>
        state = Running
        persistAndNotify(data.copy(blockchain = bc))
        data.accountKeyMap.keys.foreach(trackScriptHash(_))
        data.changeKeyMap.keys.foreach(trackScriptHash(_))
      case Running =>
        val newData = data.copy(blockchain = bc)
        newData.pendingMerkleResponses.foreach(handleMerkle(_))
        persistAndNotify(newData)
    }
  }

  def keyRefill(): Unit = {
    def newKey(
        master: ExtendedPublicKey,
        keys: Vector[ExtendedPublicKey]
    ): (ExtendedPublicKey, ByteVector32) = {
      val xp = derivePublicKey(
        master,
        keys.last.path.lastChildNumber + 1
      )
      val sh = computeScriptHash(ewt.writePublicKeyScriptHash(xp.publicKey))
      (xp, sh)
    }

    if (data.firstUnusedAccountKeys.size < MAX_RECEIVE_ADDRESSES) {
      val (xp, sh) = newKey(ewt.accountMaster, data.accountKeys)
      trackScriptHash(sh)
      persistAndNotify(
        data.copy(
          status = data.status.updated(sh, ""),
          accountKeys = data.accountKeys :+ xp
        )
      )
    }

    if (data.firstUnusedChangeKeys.size < MAX_RECEIVE_ADDRESSES) {
      val (xp, sh) = newKey(ewt.changeMaster, data.changeKeys)
      trackScriptHash(sh)
      persistAndNotify(
        data.copy(
          status = data.status.updated(sh, ""),
          changeKeys = data.changeKeys :+ xp
        )
      )
    }
  }

  def trackScriptHash(scriptHash: ByteVector32): Unit =
    pool
      .subscribeToScriptHash(scriptHash) {
        case ScriptHashSubscriptionResponse(scriptHash, status) =>
          if (data.status.get(scriptHash).contains(status)) {
            System.err.println(s"[debug][wallet] $scriptHash status: $status")

            val missing = data.history
              .getOrElse(scriptHash, Nil)
              .map(item => item.txHash -> item.height)
              .toMap -- data.transactions.keySet -- data.pendingTransactionRequests

            if (missing.nonEmpty) {
              System.err.println(
                s"[debug][wallet] ${scriptHash} missing txs: $missing"
              )

              missing.foreach { case (txid, height) =>
                getTransaction(txid)
                getMerkle(txid, height)
              }

              // An optimization to not recalculate internal data values on each scriptHashResponse event
              persistAndNotify(
                data.copy(pendingHistoryRequests =
                  data.pendingTransactionRequests ++ missing.keySet
                )
              )
            }
          } else if (status == "") {
            persistAndNotify(
              data.copy(status = data.status.updated(scriptHash, status))
            )
          } else if (
            data.accountKeyMap.contains(scriptHash) ||
            data.changeKeyMap.contains(scriptHash)
          ) {
            System.err.println(
              s"[debug][wallet] requesting history for $scriptHash"
            )

            getScriptHashHistory(scriptHash)
            persistAndNotify(
              data.copy(
                status = data.status.updated(scriptHash, status),
                pendingHistoryRequests =
                  data.pendingHistoryRequests + scriptHash
              )
            )
          }
      }

  def getData = data

  def provideExcludedOutPoints(excluded: List[OutPoint]): Unit =
    persistAndNotify(data.copy(excludedOutPoints = excluded))

  def isDoubleSpent(tx: Transaction) = Future {
    val doubleSpendTrials: Option[Boolean] = for {
      spendingTxid <- data.overriddenPendingTxids.get(tx.txid)
      spendingBlockHeight <- data.proofs.get(spendingTxid).map(_.blockHeight)
    } yield data.computeDepth(spendingBlockHeight) > 0

    val depth = data.depth(tx.txid)
    val stamp = data.timestamp(tx.txid, params.headerDb)
    val isDoubleSpent = doubleSpendTrials.contains(true)
    IsDoubleSpentResponse(tx, depth, stamp, isDoubleSpent)
  }

  def getCurrentReceiveAddresses() = Future {
    val changeKey =
      data.firstUnusedChangeKeys.headOption.getOrElse(data.changeKeys.head)
    val sortredAccountKeys =
      data.firstUnusedAccountKeys.toList.sortBy(_.path.lastChildNumber)
    GetCurrentReceiveAddressesResponse(
      sortredAccountKeys,
      changeKey,
      ewt
    )
  }

  def generateTxResponse(
      pubKeyScriptToAmount: Map[ByteVector, Satoshi],
      feeRatePerKw: FeeratePerKw,
      sequenceFlag: Long
  ) = Future[CompleteTransactionResponse] {
    val txOuts =
      for (Tuple2(script, amount) <- pubKeyScriptToAmount)
        yield TxOut(amount, script)
    val tx = Transaction(
      version = 2,
      txIn = Nil,
      txOut = txOuts.toList,
      lockTime = 0
    )

    data
      .completeTransaction(
        tx,
        feeRatePerKw,
        params.dustLimit,
        sequenceFlag,
        data.utxos
      )
      .get
      .copy(pubKeyScriptToAmount = pubKeyScriptToAmount)
  }

  def sendAll(
      publicKeyScript: ByteVector,
      pubKeyScriptToAmount: Map[ByteVector, Satoshi],
      feeRatePerKw: FeeratePerKw,
      sequenceFlag: Long,
      fromOutpoints: Set[OutPoint],
      extraUtxos: List[TxOut] = Nil
  ): Future[GenerateTxResponse] = Future {
    val inUtxos =
      if (fromOutpoints.nonEmpty)
        data.utxos.filter(utxo => fromOutpoints contains utxo.item.outPoint)
      else data.utxos

    data
      .spendAll(
        publicKeyScript,
        pubKeyScriptToAmount,
        inUtxos,
        extraUtxos,
        feeRatePerKw,
        params.dustLimit,
        sequenceFlag
      )
      .get
  }

  def completeTransaction(
      pubKeyScriptToAmount: Map[ByteVector, Satoshi],
      feeRatePerKw: FeeratePerKw,
      sequenceFlag: Long
  ): Future[CompleteTransactionResponse] = Future {
    val txOuts =
      for (Tuple2(script, amount) <- pubKeyScriptToAmount)
        yield TxOut(amount, script)
    val tx = Transaction(
      version = 2,
      txIn = Nil,
      txOut = txOuts.toList,
      lockTime = 0
    )

    data
      .completeTransaction(
        tx,
        feeRatePerKw,
        params.dustLimit,
        sequenceFlag,
        data.utxos
      )
      .get
      .copy(pubKeyScriptToAmount = pubKeyScriptToAmount)
  }

  def rbfBump(bump: RBFBump) = Future[RBFResponse] {
    if (bump.tx.txIn.forall(_.sequence <= OPT_IN_FULL_RBF))
      data.rbfBump(bump, params.dustLimit)
    else RBFResponse(Left(RBF_DISABLED))
  }

  def rbfReroute(reroute: RBFReroute) = Future[RBFResponse] {
    if (reroute.tx.txIn.forall(_.sequence <= OPT_IN_FULL_RBF))
      data.rbfReroute(reroute, params.dustLimit)
    else RBFResponse(Left(RBF_DISABLED))
  }

  def broadcastTransaction(tx: Transaction) = state match {
    case Disconnected =>
      Future {
        BroadcastTransactionResponse(
          tx,
          Some(Error(code = -1, "wallet is not connected"))
        )
      }
    case Running =>
      pool.request[BroadcastTransactionResponse](BroadcastTransaction(tx))
  }

  def getScriptHashHistory(scriptHash: ByteVector32): Unit =
    pool
      .request[GetScriptHashHistoryResponse](GetScriptHashHistory(scriptHash))
      .onComplete {
        case Success(GetScriptHashHistoryResponse(scriptHash, items)) => {
          System.err.println(
            s"[debug][wallet] got history $scriptHash ::> $items"
          )

          val pending =
            collection.mutable.HashSet.empty[GetHeaders]
          pending ++= data.pendingHeadersRequests

          val shadowItems = for {
            existingItems <- data.history.get(scriptHash).toList
            item <- existingItems if !items.exists(_.txHash == item.txHash)
          } yield item

          val allItems = items ++ shadowItems

          def downloadHeadersIfMissing(height: Int): Unit = {
            if (
              data.blockchain
                .getHeader(height)
                .orElse(params.headerDb getHeader height)
                .isEmpty
            ) {
              // we don't have this header because it is older than our checkpoints => request the entire chunk
              val req = GetHeaders(
                height / RETARGETING_PERIOD * RETARGETING_PERIOD,
                RETARGETING_PERIOD
              )
              if (pending.contains(req)) return
              pending.add(req)
              chainSync.getHeaders(req)
            }
          }

          def process(txid: ByteVector32, height: Int): Unit = {
            if (data.proofs.contains(txid) || height < 1) return
            downloadHeadersIfMissing(height)
            System.err.println(s"2 requesting merkle $txid")
            getMerkle(txid, height)
          }

          persistAndNotify(
            data.copy(
              history = data.history.updated(scriptHash, allItems),
              pendingHistoryRequests = data.pendingHistoryRequests - scriptHash,
              pendingTransactionRequests =
                allItems.foldLeft(data.pendingTransactionRequests) {
                  case (hashes, item)
                      if !data.transactions.contains(
                        item.txHash
                      ) && !data.pendingTransactionRequests
                        .contains(item.txHash) =>
                    getTransaction(item.txHash)
                    process(item.txHash, item.height)
                    hashes + item.txHash

                  case (hashes, item) =>
                    process(item.txHash, item.height)
                    hashes
                },
              pendingHeadersRequests = pending.toSet
            )
          )
        }

        case Failure(err) =>
          System.err.println(
            s"[error] failed to call electrum server for GetScriptHashHistory: $err"
          )
      }

  def handleTransaction(resp: GetTransactionResponse): Unit = {
    val GetTransactionResponse(tx, contextOpt) = resp

    System.err.println(s"handling transaction ${tx.txid}")

    val clearedExcludedOutPoints: List[OutPoint] =
      data.excludedOutPoints diff tx.txIn.map(_.outPoint)

    // Even though we have excluded some utxos in this wallet user may still spend them from other wallet,
    //   so clear excluded outpoints here
    val newData = data.copy(
      pendingTransactionRequests = data.pendingTransactionRequests - tx.txid,
      excludedOutPoints = clearedExcludedOutPoints
    )

    data.computeTransactionDelta(tx).map {
      case TransactionDelta(_, feeOpt, received, sent) =>
        for (pendingTx <- data.pendingTransactions)
          handleTransaction(GetTransactionResponse(pendingTx, contextOpt))
        EventStream.publish(
          data.transactionReceived(
            tx,
            feeOpt,
            received,
            sent,
            ewt.xPub,
            params.headerDb
          )
        )

        persistAndNotify(
          newData.copy(
            transactions = data.transactions.updated(tx.txid, tx),
            pendingTransactions = Nil
          )
        )
    } getOrElse {
      // We are currently missing parents for this transaction, keep waiting
      persistAndNotify(
        newData.copy(pendingTransactions = data.pendingTransactions :+ tx)
      )
    }
  }

  def getTransaction(txid: ByteVector32): Unit = {
    System.err.println(s"getting transaction $txid")
    pool.request[GetTransactionResponse](GetTransaction(txid)).onComplete {
      case Success(resp: GetTransactionResponse) => handleTransaction(resp)
      case Failure(err) =>
        System.err.println(
          s"[error] failed to call electrum server for GetTransaction: $err"
        )
    }
  }

  def handleMerkle(resp: GetMerkleResponse): Unit = {
    val GetMerkleResponse(Some(source), txid, _, height, _, _) = resp

    System.err.println(s"got merkle $txid $height")

    val req = GetHeaders(
      height / RETARGETING_PERIOD * RETARGETING_PERIOD,
      RETARGETING_PERIOD
    )

    val header = data.blockchain
      .getHeader(height)
      .orElse(params.headerDb.getHeader(height))

    header match {
      case Some(existingHeader)
          if existingHeader.hashMerkleRoot == resp.root &&
            data.isTxKnown(txid) =>
        System.err.println("oooooooooooooo")
        persistAndNotify(
          data
            .copy(
              proofs = data.proofs.updated(txid, resp),
              pendingMerkleResponses = data.pendingMerkleResponses - resp
            )
            .withOverridingTxids
        )

      case Some(existingHeader) if existingHeader.hashMerkleRoot == resp.root =>
        System.err.println("rrrrrrrrrrrr")

      // we don't have this header yet, but the request is pending, so just
      //   wait and try to handle this merkle later
      case None if data.pendingHeadersRequests.contains(req) =>
        System.err.println("aaaaaaaaaaaaa")
        data =
          data.copy(pendingMerkleResponses = data.pendingMerkleResponses + resp)

      // we don't have this header at all, so let's ask for it
      case None => {
        System.err.println("zzzzzzzzzzzzz")
        chainSync.getHeaders(req)
        data = data.copy(
          pendingMerkleResponses = data.pendingMerkleResponses + resp,
          pendingHeadersRequests = data.pendingHeadersRequests + req
        )
      }

      case _ => {
        // something is wrong with this client, better disconnect from it
        System.err.println("xxxxxxxxxxxx")
        source.send(PoisonPill)
        data = data.copy(transactions = data.transactions - txid)
      }
    }
  }

  def getMerkle(txid: ByteVector32, height: Int): Unit =
    pool.request[GetMerkleResponse](GetMerkle(txid, height)).onComplete {
      case Success(resp) if resp.source.isDefined => handleMerkle(resp)
      case Success(_: GetMerkleResponse) =>
        System.err.println(
          s"[error] GetMerkle response didn't have the context"
        )
      case Failure(err) =>
        System.err.println(
          s"[error] failed to call electrum server for GetMerkle: $err"
        )
    }
}

object ElectrumWallet {
  type TxOutOption = Option[TxOut]
  type TxHistoryItemList = List[TransactionHistoryItem]

  // RBF
  final val GENERATION_FAIL = 0
  final val PARENTS_MISSING = 1
  final val FOREIGN_INPUTS = 2
  final val RBF_DISABLED = 3

  sealed trait Request
  sealed trait Response

  sealed trait GenerateTxResponse extends Response {
    def withReplacedTx(tx: Transaction): GenerateTxResponse
    val pubKeyScriptToAmount: Map[ByteVector, Satoshi]
    val data: ElectrumData
    val tx: Transaction
    val fee: Satoshi
  }

  case class GetDataResponse(data: ElectrumData) extends Response

  case object GetCurrentReceiveAddresses extends Request
  case class GetCurrentReceiveAddressesResponse(
      keys: List[ExtendedPublicKey],
      changeKey: ExtendedPublicKey,
      ewt: ElectrumWalletType
  ) extends Response {
    def firstAccountAddress: String = ewt.textAddress(keys.head)
    def changeAddress: String = ewt.textAddress(changeKey)
  }

  case class CompleteTransactionResponse(
      pubKeyScriptToAmount: Map[ByteVector, Satoshi],
      data: ElectrumData,
      tx: Transaction,
      fee: Satoshi
  ) extends GenerateTxResponse {
    override def withReplacedTx(tx1: Transaction): CompleteTransactionResponse =
      copy(tx = tx1)
  }

  case class SendAll(
      publicKeyScript: ByteVector,
      pubKeyScriptToAmount: Map[ByteVector, Satoshi],
      feeRatePerKw: FeeratePerKw,
      sequenceFlag: Long,
      fromOutpoints: Set[OutPoint],
      extraUtxos: List[TxOut] = Nil
  ) extends Request
  case class SendAllResponse(
      pubKeyScriptToAmount: Map[ByteVector, Satoshi],
      data: ElectrumData,
      tx: Transaction,
      fee: Satoshi
  ) extends GenerateTxResponse {
    override def withReplacedTx(tx1: Transaction): SendAllResponse =
      copy(tx = tx1)
  }

  case class RBFBump(
      tx: Transaction,
      feeRatePerKw: FeeratePerKw,
      sequenceFlag: Long
  ) extends Request
  case class RBFReroute(
      tx: Transaction,
      feeRatePerKw: FeeratePerKw,
      publicKeyScript: ByteVector,
      sequenceFlag: Long
  ) extends Request
  case class RBFResponse(
      result: Either[Int, GenerateTxResponse] = Left(GENERATION_FAIL)
  ) extends Response

  case class IsDoubleSpentResponse(
      tx: Transaction,
      depth: Long,
      stamp: Long,
      isDoubleSpent: Boolean
  ) extends Response

  sealed trait WalletEvent extends ElectrumEvent { val xPub: ExtendedPublicKey }
  case class TransactionReceived(
      tx: Transaction,
      depth: Long,
      stamp: Long,
      received: Satoshi,
      sent: Satoshi,
      walletAddreses: List[String],
      xPub: ExtendedPublicKey,
      feeOpt: Option[Satoshi] = None
  ) extends WalletEvent
  case class WalletReady(
      balance: Satoshi,
      height: Long,
      heightsCode: Int,
      xPub: ExtendedPublicKey,
      unExcludedUtxos: Seq[Utxo],
      excludedOutPoints: List[OutPoint] = Nil
  ) extends WalletEvent
}

case class UnspentItem(
    txHash: ByteVector32,
    txPos: Int,
    value: Long,
    height: Long
) {
  lazy val outPoint = OutPoint(txHash.reverse, txPos)
}
case class Utxo(key: ExtendedPublicKey, item: UnspentItem)

case class AccountAndXPrivKey(
    xPriv: ExtendedPrivateKey,
    master: ExtendedPrivateKey
)

case class TransactionDelta(
    spentUtxos: Seq[Utxo],
    feeOpt: Option[Satoshi],
    received: Satoshi,
    sent: Satoshi
)

case class WalletParameters(
    headerDb: HeaderDb,
    walletDb: WalletDb,
    txDb: SQLiteTx,
    dustLimit: Satoshi
) {
  lazy val emptyPersistentData: PersistentData = PersistentData(
    accountKeysCount = MAX_RECEIVE_ADDRESSES,
    changeKeysCount = MAX_RECEIVE_ADDRESSES
  )
  lazy val emptyPersistentDataBytes: ByteVector =
    persistentDataCodec.encode(emptyPersistentData).require.toByteVector
}

  // @formatter:off
  /**
   * Wallet state, which stores data returned by Electrum servers.
   * Most items are indexed by script hash (i.e. by pubkey script sha256 hash).
   * Height follows Electrum's conventions:                                                                               * - h > 0 means that the tx was confirmed at block #h
   * - 0 means unconfirmed, but all input are confirmed
   * < 0 means unconfirmed, and some inputs are unconfirmed as well
   *
   * @param blockchain                 blockchain
   * @param accountKeys                account keys
   * @param changeKeys                 change keys
   * @param status                     script hash -> status; "" means that the script hash has not been used yet
   * @param transactions               wallet transactions
   * @param heights                    transactions heights
   * @param history                    script hash -> history
   * @param locks                      transactions which lock some of our utxos.
   * @param pendingHistoryRequests     requests pending a response from the electrum server
   * @param pendingTransactionRequests requests pending a response from the electrum server
   * @param pendingTransactions        transactions received but not yet connected to their parents
   */
  // @formatter:on
case class ElectrumData(
    ewt: ElectrumWalletType,
    blockchain: Blockchain,
    accountKeys: Vector[ExtendedPublicKey],
    changeKeys: Vector[ExtendedPublicKey],
    excludedOutPoints: List[OutPoint],
    status: Map[ByteVector32, String],
    transactions: Map[ByteVector32, Transaction],
    overriddenPendingTxids: Map[ByteVector32, ByteVector32],
    history: Map[ByteVector32, TxHistoryItemList],
    proofs: Map[ByteVector32, GetMerkleResponse],
    pendingHistoryRequests: Set[ByteVector32] = Set.empty,
    pendingTransactionRequests: Set[ByteVector32] = Set.empty,
    pendingHeadersRequests: Set[GetHeaders] = Set.empty,
    pendingTransactions: List[Transaction] = Nil,
    pendingMerkleResponses: Set[GetMerkleResponse] = Set.empty,
    lastReadyMessage: Option[WalletReady] = None
) {
  lazy val publicScriptAccountMap: Map[ByteVector, ExtendedPublicKey] =
    accountKeys
      .map(key => ewt.writePublicKeyScriptHash(key.publicKey) -> key)
      .toMap
  lazy val publicScriptChangeMap: Map[ByteVector, ExtendedPublicKey] =
    changeKeys
      .map(key => ewt.writePublicKeyScriptHash(key.publicKey) -> key)
      .toMap
  lazy val publicScriptMap: Map[ByteVector, ExtendedPublicKey] =
    publicScriptAccountMap ++ publicScriptChangeMap

  lazy val accountKeyMap: Map[ByteVector32, ExtendedPublicKey] =
    for ((serialized, key) <- publicScriptAccountMap)
      yield (computeScriptHash(serialized), key)
  lazy val changeKeyMap: Map[ByteVector32, ExtendedPublicKey] =
    for ((serialized, key) <- publicScriptChangeMap)
      yield (computeScriptHash(serialized), key)

  lazy val currentReadyMessage: WalletReady = WalletReady(
    balance,
    blockchain.height,
    proofs.hashCode + transactions.hashCode,
    ewt.xPub,
    unExcludedUtxos,
    excludedOutPoints
  )

  lazy val firstUnusedAccountKeys: immutable.Iterable[ExtendedPublicKey] =
    accountKeyMap.collect {
      case (nonExistentScriptHash, privKey)
          if !status.contains(nonExistentScriptHash) =>
        privKey
      case (emptyScriptHash, privKey) if status(emptyScriptHash) == "" =>
        privKey
    }

  lazy val firstUnusedChangeKeys: immutable.Iterable[ExtendedPublicKey] = {
    val usedChangeNumbers = transactions.values
      .flatMap(_.txOut)
      .map(_.publicKeyScript)
      .flatMap(publicScriptChangeMap.get)
      .map(_.path.lastChildNumber)
      .toSet
    changeKeys.collect {
      case unusedChangeKey
          if !usedChangeNumbers.contains(
            unusedChangeKey.path.lastChildNumber
          ) =>
        unusedChangeKey
    }
  }

  lazy val unExcludedUtxos: Seq[Utxo] = {
    history.toSeq.flatMap { case (scriptHash, historyItems) =>
      accountKeyMap.get(scriptHash) orElse changeKeyMap.get(scriptHash) map {
        key =>
          // We definitely have a private key generated for corresponding scriptHash here
          val unspents = for {
            item <- historyItems
            tx <- transactions.get(item.txHash).toList
            if !overriddenPendingTxids.contains(tx.txid)
            (txOut, index) <- tx.txOut.zipWithIndex
            if computeScriptHash(txOut.publicKeyScript) == scriptHash
          } yield Utxo(
            key,
            UnspentItem(
              item.txHash,
              index,
              txOut.amount.toLong,
              item.height
            )
          )

          // Find all out points which spend from this script hash and make sure unspents do not contain them
          val outPoints = historyItems
            .map(_.txHash)
            .flatMap(transactions.get)
            .flatMap(_.txIn)
            .map(_.outPoint)
            .toSet
          unspents.filterNot(utxo => outPoints contains utxo.item.outPoint)
      } getOrElse Nil
    }
  }

  lazy val utxos: Seq[Utxo] = unExcludedUtxos.filterNot(utxo =>
    excludedOutPoints.contains(utxo.item.outPoint)
  )

  // Remove status for each script hash for which we have pending requests,
  //   this will make us query script hash history for these script hashes again when we reconnect
  def reset: ElectrumData = copy(
    status = status -- pendingHistoryRequests,
    pendingHistoryRequests = Set.empty,
    pendingTransactionRequests = Set.empty,
    pendingHeadersRequests = Set.empty,
    lastReadyMessage = None
  )

  def toPersistent: PersistentData = PersistentData(
    accountKeys.length,
    changeKeys.length,
    status,
    transactions,
    overriddenPendingTxids,
    history,
    proofs,
    pendingTransactions,
    excludedOutPoints
  )

  def isTxKnown(txid: ByteVector32): Boolean =
    transactions.contains(txid) || pendingTransactionRequests.contains(
      txid
    ) || pendingTransactions.exists(_.txid == txid)

  def isMine(txIn: TxIn): Boolean = ewt
    .extractPubKeySpentFrom(txIn)
    .map(ewt.writePublicKeyScriptHash)
    .exists(publicScriptMap.contains)

  def timestamp(txid: ByteVector32, headerDb: HeaderDb): Long = {
    val blockHeight = proofs.get(txid).map(_.blockHeight).getOrElse(default = 0)
    val stampOpt =
      blockchain.getHeader(blockHeight) orElse headerDb.getHeader(blockHeight)
    stampOpt.map(_.time * 1000L).getOrElse(System.currentTimeMillis)
  }

  def depth(txid: ByteVector32): Int = {
    System.err.println(s"DEPTH: $txid --> ${proofs.get(txid)}")
    proofs.get(txid).map(_.blockHeight).map(computeDepth(_)).getOrElse(0)
  }

  def computeDepth(txHeight: Int): Int = {
    System.err.println(
      s"      ${blockchain.height} - $txHeight + 1 = ${if (txHeight <= 0L) 0
        else blockchain.height - txHeight + 1}"
    )
    if (txHeight <= 0L) 0 else blockchain.height - txHeight + 1
  }

  def isMine(txOut: TxOut): Boolean =
    publicScriptMap.contains(txOut.publicKeyScript)

  lazy val balance: Satoshi = utxos.foldLeft(0L.sat)(_ + _.item.value.sat)

  def transactionReceived(
      tx: Transaction,
      feeOpt: Option[Satoshi],
      received: Satoshi,
      sent: Satoshi,
      xPub: ExtendedPublicKey,
      headerDb: HeaderDb
  ): TransactionReceived = {
    val walletAddresses = tx.txOut
      .filter(isMine)
      .map(_.publicKeyScript)
      .flatMap(publicScriptMap.get)
      .map(ewt.textAddress)
      .toList
    TransactionReceived(
      tx,
      depth(tx.txid),
      timestamp(tx.txid, headerDb),
      received,
      sent,
      walletAddresses,
      xPub,
      feeOpt
    )
  }

  def computeTransactionDelta(tx: Transaction): Option[TransactionDelta] = {
    // Computes the effect of this transaction on the wallet
    val ourInputs = tx.txIn.filter(isMine)

    for (txIn <- ourInputs) {
      // Can only be computed if all our inputs have parents
      val hasParent = transactions.contains(txIn.outPoint.txid)
      if (!hasParent) return None
    }

    val spentUtxos = ourInputs.map { txIn =>
      // This may be needed for FBF and it's a good place to create these UTXOs
      // we create simulated as-if yet unused UTXOs to be reused in RBF transaction
      val TxOut(amount, publicKeyScript) =
        transactions(txIn.outPoint.txid).txOut(txIn.outPoint.index.toInt)
      Utxo(
        key = publicScriptMap(publicKeyScript),
        UnspentItem(
          txIn.outPoint.txid,
          txIn.outPoint.index.toInt,
          amount.toLong,
          height = 0
        )
      )
    }

    val mineSent = spentUtxos.map(_.item.value.sat).sum
    val mineReceived = tx.txOut.filter(isMine).map(_.amount).sum
    val totalReceived = tx.txOut.map(_.amount).sum

    if (ourInputs.size != tx.txIn.size)
      Some(TransactionDelta(spentUtxos, None, mineReceived, mineSent))
    else
      Some(
        TransactionDelta(
          spentUtxos,
          Some(mineSent - totalReceived),
          mineReceived,
          mineSent
        )
      )
  }

  def rbfBump(bump: RBFBump, dustLimit: Satoshi): RBFResponse = {
    val tx1 = bump.tx.copy(txOut = bump.tx.txOut.filterNot(isMine), txIn = Nil)

    computeTransactionDelta(bump.tx) map {
      case delta
          if delta.feeOpt.isDefined && bump.tx.txOut.size == 1 && tx1.txOut.nonEmpty && utxos.isEmpty =>
        rbfReroute(
          tx1.txOut.head.publicKeyScript,
          delta.spentUtxos,
          bump.feeRatePerKw,
          dustLimit,
          bump.sequenceFlag
        )

      case delta if delta.feeOpt.isDefined =>
        val leftUtxos = utxos.filterNot(_.item.txHash == bump.tx.txid)
        completeTransaction(
          tx1,
          bump.feeRatePerKw,
          dustLimit,
          bump.sequenceFlag,
          leftUtxos,
          delta.spentUtxos
        ) match {
          case Success(response) => RBFResponse(Right(response))
          case _                 => RBFResponse(Left(GENERATION_FAIL))
        }

      case _ => RBFResponse(Left(FOREIGN_INPUTS))
    } getOrElse RBFResponse(Left(PARENTS_MISSING))
  }

  def rbfReroute(reroute: RBFReroute, dustLimit: Satoshi): RBFResponse =
    computeTransactionDelta(reroute.tx) map { delta =>
      rbfReroute(
        reroute.publicKeyScript,
        delta.spentUtxos,
        reroute.feeRatePerKw,
        dustLimit,
        reroute.sequenceFlag
      )
    } getOrElse RBFResponse(Left(PARENTS_MISSING))

  def rbfReroute(
      publicKeyScript: ByteVector,
      spentUtxos: Seq[Utxo],
      feeRatePerKw: FeeratePerKw,
      dustLimit: Satoshi,
      sequenceFlag: Long
  ): RBFResponse = {
    spendAll(
      publicKeyScript,
      strictPubKeyScriptsToAmount = Map.empty,
      usableInUtxos = spentUtxos,
      extraOutUtxos = Nil,
      feeRatePerKw,
      dustLimit,
      sequenceFlag
    ) match {
      case Success(response) => RBFResponse(Right(response))
      case _                 => RBFResponse(Left(GENERATION_FAIL))
    }
  }

  def completeTransaction(
      tx: Transaction,
      feeRatePerKw: FeeratePerKw,
      dustLimit: Satoshi,
      sequenceFlag: Long,
      usableInUtxos: Seq[Utxo],
      mustUseUtxos: Seq[Utxo] = Nil
  ): Try[CompleteTransactionResponse] = Try {
    def computeFee(candidates: Seq[Utxo], change: TxOutOption) = {
      val tx1 =
        ewt.setUtxosWithDummySig(usableUtxos = candidates, tx, sequenceFlag)
      val weight = change
        .map(tx1.addOutput)
        .getOrElse(tx1)
        .weight(Protocol.PROTOCOL_VERSION)
      Transactions.weight2fee(feeRatePerKw, weight)
    }

    val amountToSend = tx.txOut.map(_.amount).sum
    val changeKey = firstUnusedChangeKeys.headOption.getOrElse(changeKeys.head)
    val changeScript = ewt.computePublicKeyScript(changeKey.publicKey)
    val changeTxOut = TxOut(Satoshi(0L), changeScript)

    require(tx.txIn.isEmpty, "Cannot complete a tx that already has inputs")
    require(amountToSend > dustLimit, "Amount to send is below dust limit")

    @tailrec
    def loop(
        current: Seq[Utxo],
        remaining: Seq[Utxo] = Nil
    ): (Seq[Utxo], TxOutOption) = current.map(_.item.value).sum.sat match {
      case total
          if total - computeFee(
            current,
            None
          ) < amountToSend && remaining.isEmpty =>
        throw new RuntimeException("Insufficient funds")
      case total if total - computeFee(current, None) < amountToSend =>
        loop(remaining.head +: current, remaining.tail)

      case total
          if total - computeFee(current, None) <= amountToSend + dustLimit =>
        (current, None)
      case total
          if total - computeFee(
            current,
            Some(changeTxOut)
          ) <= amountToSend + dustLimit && remaining.isEmpty =>
        (current, None)
      case total
          if total - computeFee(
            current,
            Some(changeTxOut)
          ) <= amountToSend + dustLimit =>
        loop(remaining.head +: current, remaining.tail)
      case total =>
        (
          current,
          Some(
            changeTxOut
              .copy(amount =
                total - computeFee(current, Some(changeTxOut)) - amountToSend
              )
          )
        )
    }

    val usable = usableInUtxos.sortBy(_.item.value)
    val (selected, changeOpt) = loop(current = mustUseUtxos, usable)
    val txWithInputs = ewt.setUtxosWithDummySig(selected, tx, sequenceFlag)
    val txWithChange =
      changeOpt.map(txWithInputs.addOutput).getOrElse(txWithInputs)

    val tx3 = ewt.signTransaction(usableInUtxos ++ mustUseUtxos, txWithChange)
    val computedFee =
      selected.map(_.item.value).sum.sat - tx3.txOut.map(_.amount).sum
    CompleteTransactionResponse(
      pubKeyScriptToAmount = Map.empty,
      this,
      tx3,
      computedFee
    )
  }

  def spendAll(
      restPubKeyScript: ByteVector,
      strictPubKeyScriptsToAmount: Map[ByteVector, Satoshi],
      usableInUtxos: Seq[Utxo],
      extraOutUtxos: List[TxOut],
      feeRatePerKw: FeeratePerKw,
      dustLimit: Satoshi,
      sequenceFlag: Long
  ): Try[SendAllResponse] = Try {

    val strictTxOuts =
      for (Tuple2(pubKeyScript, amount) <- strictPubKeyScriptsToAmount)
        yield TxOut(amount, pubKeyScript)
    val restTxOut = TxOut(
      amount = usableInUtxos
        .map(_.item.value.sat)
        .sum - strictTxOuts.map(_.amount).sum - extraOutUtxos.map(_.amount).sum,
      restPubKeyScript
    )
    val tx1 = ewt.setUtxosWithDummySig(
      usableInUtxos,
      Transaction(
        version = 2,
        Nil,
        restTxOut :: strictTxOuts.toList ::: extraOutUtxos,
        lockTime = 0
      ),
      sequenceFlag
    )

    val fee = Transactions.weight2fee(
      weight = tx1.weight(Protocol.PROTOCOL_VERSION),
      feeratePerKw = feeRatePerKw
    )
    require(
      restTxOut.amount - fee > dustLimit,
      "Resulting tx amount to send is below dust limit"
    )

    val restTxOut1 = TxOut(restTxOut.amount - fee, restPubKeyScript)
    val tx2 =
      tx1.copy(txOut = restTxOut1 :: strictTxOuts.toList ::: extraOutUtxos)
    val allPubKeyScriptsToAmount =
      strictPubKeyScriptsToAmount.updated(restPubKeyScript, restTxOut1.amount)
    SendAllResponse(
      allPubKeyScriptsToAmount,
      this,
      ewt.signTransaction(usableInUtxos, tx2),
      fee
    )
  }

  def withOverridingTxids: ElectrumData = {
    def changeKeyDepth(tx: Transaction) = if (proofs.contains(tx.txid))
      Long.MaxValue
    else
      tx.txOut
        .map(_.publicKeyScript)
        .flatMap(publicScriptChangeMap.get)
        .map(_.path.lastChildNumber)
        .headOption
        .getOrElse(Long.MinValue)
    def computeOverride(txs: Iterable[Transaction] = Nil) =
      LazyList.continually(txs maxBy changeKeyDepth) zip txs collect {
        case (latterTx, formerTx) if latterTx != formerTx =>
          formerTx.txid -> latterTx.txid
      }
    val overrides = transactions.values
      .map(LazyList continually _)
      .flatMap(txStream => txStream.head.txIn zip txStream)
      .groupBy(_._1.outPoint)
      .values
      .map(_.secondItems)
      .flatMap(computeOverride)
    copy(overriddenPendingTxids = overrides.toMap)
  }
}

case class PersistentData(
    accountKeysCount: Int,
    changeKeysCount: Int,
    status: Map[ByteVector32, String] = Map.empty,
    transactions: Map[ByteVector32, Transaction] = Map.empty,
    overriddenPendingTxids: Map[ByteVector32, ByteVector32] = Map.empty,
    history: Map[ByteVector32, TxHistoryItemList] = Map.empty,
    proofs: Map[ByteVector32, GetMerkleResponse] = Map.empty,
    pendingTransactions: List[Transaction] = Nil,
    excludedOutPoints: List[OutPoint] = Nil
)
