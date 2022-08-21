package immortan.blockchain.electrum

import scoin.DeterministicWallet._
import scoin._
import immortan.blockchain.EclairWallet._
import immortan.blockchain.bitcoind.rpc.Error
import immortan.blockchain.electrum.Blockchain.RETARGETING_PERIOD
import immortan.blockchain.electrum.ElectrumClient._
import immortan.blockchain.electrum.ElectrumWallet._
import immortan.blockchain.electrum.db.sqlite.SqliteWalletDb.persistentDataCodec
import immortan.blockchain.electrum.db.{HeaderDb, WalletDb}
import immortan.blockchain.fee.FeeratePerKw
import scoin.ln.transactions.Transactions
import immortan.crypto.Tools._
import immortan.sqlite.SQLiteTx
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Success, Try}

class ElectrumWallet(
    pool: ElectrumClientPool,
    chainSync: ElectrumChainSync,
    params: WalletParameters,
    ewt: ElectrumWalletType
)(implicit
    ac: castor.Context
) extends CastorStateMachineActorWithData[Any, ElectrumData] { self =>
  def persistAndNotify(data: ElectrumData): ElectrumData = {
    val t = new java.util.Timer()
    val task = new java.util.TimerTask { def run() = self.send(KEY_REFILL) }
    t.schedule(task, 100L)

    if (data.lastReadyMessage contains data.currentReadyMessage) return data
    val newData = data.copy(lastReadyMessage = Some(data.currentReadyMessage))
    params.walletDb.persist(
      newData.toPersistent,
      newData.balance,
      ewt.xPub.publicKey
    )
    EventStream.publish(newData.currentReadyMessage)
    newData
  }

  EventStream
    .subscribe({ case data: Blockchain => self.send(data) })

  pool.addStatusListener(self.asInstanceOf[castor.SimpleActor[Any]])

  def initialData = null
  def initialState = Disconnected
  def stay = (state, data)

  case object Disconnected
      extends State({
        case (raw: ByteVector, null) => {
          // Serialized data may become big with much usage
          // Deserialzie it in this dedicated thread to not slow down UI

          val persisted = Try(
            persistentDataCodec.decode(raw.toBitVector).require.value
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

          (
            state,
            ElectrumData(
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
          )
        }

        case (bc: Blockchain, data) => {
          for (scriptHash <- data.accountKeyMap.keys)
            pool.subscribeToScriptHash(
              scriptHash,
              self.asInstanceOf[castor.SimpleActor[Any]]
            )

          for (scriptHash <- data.changeKeyMap.keys)
            pool.subscribeToScriptHash(
              scriptHash,
              self.asInstanceOf[castor.SimpleActor[Any]]
            )

          (Running, persistAndNotify(data.copy(blockchain = bc)))
        }

        case other => whenUnhandled(other)
      })

  case object Running
      extends State({
        case (bc: Blockchain, data) => {
          val newData = data.copy(blockchain = bc)
          newData.pendingMerkleResponses.foreach(self.send(_))
          (state, persistAndNotify(newData))
        }

        case (
              ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status),
              data
            ) if data.status.get(scriptHash).contains(status) => {
          val missing = data.history
            .getOrElse(scriptHash, Nil)
            .map(item => item.txHash -> item.height)
            .toMap -- data.transactions.keySet -- data.pendingTransactionRequests

          missing.foreach { case (txid, height) =>
            getTransaction(txid)
            getMerkle(txid, height)
          }

          if (missing.nonEmpty) {
            // An optimization to not recalculate internal data values on each scriptHashResponse event
            (
              state,
              persistAndNotify(
                data.copy(pendingHistoryRequests =
                  data.pendingTransactionRequests ++ missing.keySet
                )
              )
            )
          } else stay
        }

        case (
              ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, _),
              data
            )
            if !data.accountKeyMap.contains(scriptHash) && !data.changeKeyMap
              .contains(scriptHash) =>
          stay

        case (
              ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status),
              data
            ) if status.isEmpty =>
          (
            state,
            persistAndNotify(
              data.copy(status = data.status.updated(scriptHash, status))
            )
          )

        case (
              ElectrumClient
                .ScriptHashSubscriptionResponse(scriptHash, status),
              data
            ) => {
          getScriptHashHistory(scriptHash)
          (
            state,
            persistAndNotify(
              data.copy(
                status = data.status.updated(scriptHash, status),
                pendingHistoryRequests =
                  data.pendingHistoryRequests + scriptHash
              )
            )
          )
        }

        case other => whenUnhandled(other)
      })

  def whenUnhandled(other: (Any, ElectrumData)): (State, ElectrumData) =
    other match {
      case (ElectrumClient.ElectrumDisconnected, data) =>
        (Disconnected, data.reset)

      case (KEY_REFILL, data)
          if data.firstUnusedChangeKeys.size < MAX_RECEIVE_ADDRESSES => {
        val newKey = derivePublicKey(
          ewt.changeMaster,
          data.changeKeys.last.path.lastChildNumber + 1
        )
        val newKeyScriptHash =
          computeScriptHash(ewt writePublicKeyScriptHash newKey.publicKey)

        pool.subscribeToScriptHash(
          newKeyScriptHash,
          self.asInstanceOf[castor.SimpleActor[Any]]
        )

        (
          state,
          persistAndNotify(
            data.copy(
              status = data.status.updated(newKeyScriptHash, new String),
              changeKeys = data.changeKeys :+ newKey
            )
          )
        )
      }

      case (KEY_REFILL, data)
          if data.firstUnusedAccountKeys.size < MAX_RECEIVE_ADDRESSES => {
        val newKey = derivePublicKey(
          ewt.accountMaster,
          data.accountKeys.last.path.lastChildNumber + 1
        )
        val newKeyScriptHash =
          computeScriptHash(ewt writePublicKeyScriptHash newKey.publicKey)
        pool.subscribeToScriptHash(newKeyScriptHash, self)
        (
          state,
          persistAndNotify(
            data.copy(
              status = data.status.updated(newKeyScriptHash, new String),
              accountKeys = data.accountKeys :+ newKey
            )
          )
        )
      }

      case _ => stay
    }

  def getData() = Future { GetDataResponse(data) }

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
        ElectrumClient.BroadcastTransactionResponse(
          tx,
          Some(Error(code = -1, "wallet is not connected"))
        )
      }
    case Running => pool.request(BroadcastTransaction(tx))
  }

  def getScriptHashHistory(scriptHash: ByteVector32): Unit =
    pool.request(GetScriptHashHistory(scriptHash)).onComplete {
      case Success(
            ElectrumClient.GetScriptHashHistoryResponse(scriptHash, items)
          ) => {
        val pendingHeadersRequests1 =
          collection.mutable.HashSet.empty[GetHeaders]
        pendingHeadersRequests1 ++= data.pendingHeadersRequests

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
            val request = GetHeaders(
              height / RETARGETING_PERIOD * RETARGETING_PERIOD,
              RETARGETING_PERIOD
            )
            if (pendingHeadersRequests1 contains request) return
            pendingHeadersRequests1.add(request)
            chainSync.send(request)
          }
        }

        def process(txid: ByteVector32, height: Int): Unit = {
          if (data.proofs.contains(txid) || height < 1) return
          downloadHeadersIfMissing(height)
          getMerkle(txid, height)
        }

        setStateAndData(
          state,
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
              pendingHeadersRequests = pendingHeadersRequests1.toSet
            )
          )
        )
      }

      case _ => {}
    }

  def getTransaction(txid: ByteVector32): Unit =
    pool.request(GetTransaction(txid)).onComplete {
      case Success(GetTransactionResponse(tx, contextOpt)) => {
        val clearedExcludedOutPoints: List[OutPoint] =
          data.excludedOutPoints diff tx.txIn.map(_.outPoint)
        // Even though we have excluded some utxos in this wallet user may still spend them from other wallet, so clear excluded outpoints here
        val newData = data.copy(
          pendingTransactionRequests =
            data.pendingTransactionRequests - tx.txid,
          excludedOutPoints = clearedExcludedOutPoints
        )

        data.computeTransactionDelta(tx) map {
          case TransactionDelta(_, feeOpt, received, sent) =>
            for (pendingTx <- data.pendingTransactions)
              self.send(GetTransactionResponse(pendingTx, contextOpt))
            EventStream publish data.transactionReceived(
              tx,
              feeOpt,
              received,
              sent,
              ewt.xPub,
              params.headerDb
            )

            setStateAndData(
              state,
              persistAndNotify(
                newData.copy(
                  transactions = data.transactions.updated(tx.txid, tx),
                  pendingTransactions = Nil
                )
              )
            )
        } getOrElse {
          // We are currently missing parents for this transaction, keep waiting
          setStateAndData(
            state,
            persistAndNotify(
              newData.copy(pendingTransactions = data.pendingTransactions :+ tx)
            )
          )
        }
      }

      case _ => {}
    }

  def getMerkle(txid: ByteVector32, height: Int): Unit =
    pool.request(GetMerkle(txid, height)).onComplete {
      case Success(
            response @ GetMerkleResponse(Some(source), txid, _, height, _, _)
          ) => {
        val request = GetHeaders(
          height / RETARGETING_PERIOD * RETARGETING_PERIOD,
          RETARGETING_PERIOD
        )

        data.blockchain.getHeader(height) orElse params.headerDb.getHeader(
          height
        ) match {
          case Some(existingHeader)
              if existingHeader.hashMerkleRoot == response.root && data
                .isTxKnown(
                  txid
                ) =>
            setStateAndData(
              state,
              persistAndNotify(
                data
                  .copy(
                    proofs = data.proofs.updated(txid, response),
                    pendingMerkleResponses =
                      data.pendingMerkleResponses - response
                  )
                  .withOverridingTxids
              )
            )

          case Some(existingHeader)
              if existingHeader.hashMerkleRoot == response.root => {}

          case None if data.pendingHeadersRequests.contains(request) =>
            setStateAndData(
              state,
              data.copy(pendingMerkleResponses =
                data.pendingMerkleResponses + response
              )
            )

          case None => {
            chainSync.send(request)
            setStateAndData(
              state,
              data.copy(
                pendingMerkleResponses = data.pendingMerkleResponses + response,
                pendingHeadersRequests = data.pendingHeadersRequests + request
              )
            )
          }

          case _ => {
            // Something is wrong with this client, better disconnect from it
            source.send(PoisonPill)
            setStateAndData(
              state,
              data.copy(transactions = data.transactions - txid)
            )
          }
        }
      }

      case _ => {}
    }
}

object ElectrumWallet {
  type TxOutOption = Option[TxOut]
  type TxHistoryItemList = List[TransactionHistoryItem]

  final val KEY_REFILL = "key-refill"

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

  case object GetData extends Request
  case class GetDataResponse(data: ElectrumData) extends Response

  case class ProvideExcludedOutPoints(excludedOutPoints: List[OutPoint] = Nil)
      extends Request

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

  sealed trait WalletEvent { val xPub: ExtendedPublicKey }
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

case class Utxo(key: ExtendedPublicKey, item: ElectrumClient.UnspentItem)

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
) { me =>

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
      case (emptyScriptHash, privKey)
          if status(emptyScriptHash) == new String =>
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
            unspent = ElectrumClient.UnspentItem(
              item.txHash,
              index,
              txOut.amount.toLong,
              item.height
            )
          } yield Utxo(key, unspent)

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
    excludedOutPoints contains utxo.item.outPoint
  )

  // Remove status for each script hash for which we have pending requests, this will make us query script hash history for these script hashes again when we reconnect
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

  def depth(txid: ByteVector32): Int =
    proofs.get(txid).map(_.blockHeight).map(computeDepth).getOrElse(0)

  def computeDepth(txHeight: Int): Int =
    if (txHeight <= 0L) 0 else blockchain.height - txHeight + 1

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
      val item = UnspentItem(
        txIn.outPoint.txid,
        txIn.outPoint.index.toInt,
        amount.toLong,
        height = 0
      )
      Utxo(key = publicScriptMap(publicKeyScript), item)
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
      me,
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
      me,
      ewt.signTransaction(usableInUtxos, tx2),
      fee
    )
  }

  def withOverridingTxids: ElectrumData = {
    def changeKeyDepth(tx: Transaction) = if (proofs contains tx.txid)
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
