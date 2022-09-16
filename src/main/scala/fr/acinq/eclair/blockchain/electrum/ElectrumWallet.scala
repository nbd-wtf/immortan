package fr.acinq.eclair.blockchain.electrum

import scala.util.chaining._
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Success, Failure, Try}

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

class BadElectrumData(msg: String) extends Exception(msg)
class BadMerkleProof(msg: String) extends BadElectrumData(msg)
class BadTxIdMismatched(msg: String) extends BadElectrumData(msg)

class ElectrumWallet(
    pool: ElectrumClientPool,
    chainSync: ElectrumChainSync,
    params: WalletParameters,
    ewt: ElectrumWalletType
) { self =>
  implicit val context = scala.concurrent.ExecutionContext.fromExecutor(
    java.util.concurrent.Executors.newSingleThreadExecutor
  )

  sealed trait State
  case object Disconnected extends State
  case object Running extends State

  var data: ElectrumData = _
  var state: State = Disconnected

  def persistAndNotify(nextData: ElectrumData): Unit = {
    val t = new java.util.Timer()
    val task = new java.util.TimerTask { def run() = { self.keyRefill() } }
    t.schedule(task, 100L)

    EventStream.publish(nextData.readyMessage)

    params.walletDb.persist(
      nextData.toPersistent,
      nextData.balance,
      ewt.xPub.publicKey
    )

    data = nextData
  }

  EventStream
    .subscribe {
      case BlockchainReady(bc) => self.blockchainReady(bc)
      case ElectrumDisconnected => {
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
        persistAndNotify(data.copy(blockchain = bc))
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
      .subscribeToScriptHash("wallet", scriptHash) {
        case ScriptHashSubscriptionResponse(scriptHash, status) =>
          if (data.status.get(scriptHash).contains(status)) {
            System.err.println(
              s"[debug][wallet] $scriptHash status hasn't changed"
            )
          } else if (
            status != "" &&
            (data.accountKeyMap.contains(scriptHash) ||
              data.changeKeyMap.contains(scriptHash))
          ) {
            System.err.println(
              s"[debug][wallet] script hash $scriptHash has changed ($status), requesting history"
            )
            val result = for {
              history <- pool
                .requestMany[GetScriptHashHistoryResponse](
                  GetScriptHashHistory(scriptHash),
                  2
                )
                .map(_.map { case GetScriptHashHistoryResponse(_, items) =>
                  items
                })
                .map(histories =>
                  histories.reduce[List[TransactionHistoryItem]] {
                    case (
                          itemsA,
                          itemsB
                        ) =>
                      itemsA ++ itemsB.filterNot(i => itemsA.contains(i))
                  }
                )
                .map(_.sortBy(_.height))
              _ = System.err.println(
                s"[debug][wallet] got history $scriptHash ::> ${history}"
              )

              // fetch headers if missing for any of these items
              _ <- Future.sequence(history.map { item =>
                if (
                  data.blockchain
                    .getHeader(item.height)
                    .orElse(params.headerDb.getHeader(item.height))
                    .isEmpty
                ) {
                  System.err.println(
                    s"[debug][wallet] downloading missing header ${item.height} for transaction ${item.txHash}"
                  )
                  // we don't have this header because it is older than our checkpoints => request the entire chunk
                  //   and wait for chainsync to process it
                  chainSync.getHeaders(
                    item.height / RETARGETING_PERIOD * RETARGETING_PERIOD,
                    RETARGETING_PERIOD
                  )
                } else Future { () }
              })
              _ = System.err.println("headers ok")

              // fetch transactions in the mempool for this scripthash
              mempool <- pool
                .requestMany[GetScriptHashMempoolResponse](
                  GetScriptHashMempool(scriptHash),
                  2
                )
                .map(_.map { case GetScriptHashMempoolResponse(_, items) =>
                  items
                })
                .map(mempools =>
                  mempools.reduce[List[TransactionHistoryItem]] {
                    case (
                          itemsA,
                          itemsB
                        ) =>
                      itemsA ++ itemsB.filterNot(i => itemsA.contains(i))
                  }
                )

              // fetch transaction and merkle proof for all these txids
              // (if the user is doing things right we'll have at most 2 transactions for each script hash)
              merkleProofs <- Future.sequence(
                history.map(item =>
                  data.proofs
                    .get(item.txHash)
                    .map(p => Future(p))
                    .getOrElse(
                      pool
                        .request[GetMerkleResponse](
                          GetMerkle(item.txHash, item.height)
                        )
                        .andThen { case Success(merkle) =>
                          val headerOpt = data.blockchain
                            .getHeader(merkle.blockHeight)
                            .orElse(
                              params.headerDb.getHeader(merkle.blockHeight)
                            )

                          headerOpt match {
                            case Some(existingHeader)
                                if existingHeader.hashMerkleRoot != merkle.root =>
                              throw new BadMerkleProof(
                                s"${existingHeader.hashMerkleRoot} != ${merkle.root} at ${merkle.blockHeight}"
                              )
                            case Some(_) =>
                            // it's ok
                            // merkle root matches block header
                            // TODO: check the merkle path
                            case None =>
                              throw new Exception(
                                "no header for merkle proof? this should never happen."
                              )
                          }
                        }
                    )
                )
              )

              transactions <- Future
                .sequence(
                  (history ++ mempool).map(item =>
                    data.transactions
                      .get(item.txHash)
                      .map(p => Future(p))
                      .getOrElse(
                        pool
                          .request[GetTransactionResponse](
                            GetTransaction(item.txHash)
                          )
                          .map(_.tx)
                          .andThen {
                            case Success(tx) if tx.txid != item.txHash =>
                              throw new BadTxIdMismatched(
                                s"${tx.txid} != ${item.txHash} while fetching a parent"
                              )
                          }
                      )
                  )
                )

              // also fetch all parents of transactions
              parents <- Future
                .sequence(
                  transactions
                    .flatMap(_.txIn.map(_.outPoint.txid))
                    .map(txid =>
                      data.transactions
                        .get(txid)
                        .map(p => Future(p))
                        .getOrElse(
                          pool
                            .request[GetTransactionResponse](
                              GetTransaction(txid)
                            )
                            .map(_.tx)
                            .andThen {
                              case Success(tx) if tx.txid != txid =>
                                throw new BadTxIdMismatched(
                                  s"${tx.txid} != ${txid} while fetching a transaction"
                                )
                            }
                        )
                    )
                )
            } yield {
              // prepare updated data
              val newData = data.copy(
                // add the history
                history = data.history.updatedWith(scriptHash) {
                  case None => Some(history ++ mempool)
                  case Some(existing) =>
                    Some(
                      existing ++
                        (history ++ mempool)
                          .filterNot(ni => existing.contains(ni))
                    )
                },

                // add all transactions
                transactions = data.transactions ++
                  transactions.map(tx => tx.txid -> tx) ++
                  parents.map(tx => tx.txid -> tx),

                // add all merkle proofs
                proofs = data.proofs ++ merkleProofs.map(merkle =>
                  merkle.txid -> merkle
                ),

                // add the status so we don't query again
                status = data.status
                  .updated(scriptHash, status),

                // even though we have excluded some utxos in this wallet user may still
                //   spend them from elsewhere, so clear excluded outpoints here
                excludedOutPoints =
                  transactions.foldLeft(data.excludedOutPoints) {
                    case (exc, tx) => exc.diff(tx.txIn.map(_.outPoint))
                  }
              )

              System.err.println(s"\n\n=== $scriptHash ===")
              System.err.println(
                s"NEW HISTORY: ${newData.history.get(scriptHash)}"
              )
              System.err.println(s"\n")

              // update data
              persistAndNotify(newData)

              // notify all transactions so they can be stored/displayed etc by wallet
              transactions.foreach { tx =>
                newData.computeTransactionDelta(tx).map {
                  case TransactionDelta(_, feeOpt, received, sent) =>
                    EventStream.publish(
                      TransactionReceived(
                        tx,
                        newData.depth(tx.txid),
                        newData.timestamp(tx.txid, params.headerDb),
                        received,
                        sent,
                        tx.txOut
                          .filter(newData.isMine)
                          .map(_.publicKeyScript)
                          .flatMap(newData.publicScriptMap.get)
                          .map(newData.ewt.textAddress)
                          .toList,
                        ewt.xPub,
                        feeOpt
                      )
                    )
                }
              }
            }

            result.onComplete {
              case Success(_) =>
                System.err.println(
                  s"[info][wallet] scripthash $scriptHash updated successfully"
                )
              case Failure(err: BadElectrumData) =>
                // kill this electrum client
                System.err.println(
                  s"[error][wallet] got bad data from electrum server: $err"
                )
              case Failure(err) =>
                System.err.println(
                  s"[error][wallet] something wrong happened while updating script hashes: $err"
                )
            }
          }
      }

  def getData = data

  def provideExcludedOutPoints(excluded: List[OutPoint]): Unit =
    persistAndNotify(data.copy(excludedOutPoints = excluded))

  def isDoubleSpent(tx: Transaction) = Future {
    val doubleSpendTrials: Option[Boolean] = for {
      spendingTxid <- data.overriddenPendingTxids.get(tx.txid)
      spendingBlockHeight <- data.proofs
        .get(spendingTxid)
        .map(_.blockHeight)
    } yield data.computeDepth(spendingBlockHeight) > 0

    val depth = data.depth(tx.txid)
    val stamp = data.timestamp(tx.txid, params.headerDb)
    val isDoubleSpent = doubleSpendTrials.contains(true)
    IsDoubleSpentResponse(tx, depth, stamp, isDoubleSpent)
  }

  def getCurrentReceiveAddresses() = Future {
    val changeKey =
      data.firstUnusedChangeKeys.headOption.getOrElse(
        data.changeKeys.head
      )
    val sortedAccountKeys =
      data.firstUnusedAccountKeys.toList.sortBy(_.path.lastChildNumber)
    GetCurrentReceiveAddressesResponse(
      sortedAccountKeys,
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
      pool.request[BroadcastTransactionResponse](
        BroadcastTransaction(tx)
      )
  }
}

object ElectrumWallet {
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
   * @param pendingTransactions        transactions received but not yet connected to their parents [unused]
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
    history: Map[ByteVector32, List[TransactionHistoryItem]],
    proofs: Map[ByteVector32, GetMerkleResponse],
    pendingTransactions: List[Transaction] = Nil
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

  def readyMessage: WalletReady = WalletReady(
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

  def unExcludedUtxos: Seq[Utxo] = {
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

  def utxos: Seq[Utxo] = unExcludedUtxos.filterNot(utxo =>
    excludedOutPoints.contains(utxo.item.outPoint)
  )

  // Remove status for each script hash for which we have pending requests,
  //   this will make us query script hash history for these script hashes again when we reconnect
  def reset: ElectrumData = copy(
    status = status
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
    transactions.contains(txid) || pendingTransactions.exists(_.txid == txid)

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
    proofs.get(txid).map(_.blockHeight).map(computeDepth(_)).getOrElse(0)

  def computeDepth(txHeight: Int): Int = {
    if (txHeight <= 0L) 0 else blockchain.height - txHeight + 1
  }

  def isMine(txOut: TxOut): Boolean =
    publicScriptMap.contains(txOut.publicKeyScript)

  def balance: Satoshi = {
    System.err.println(
      s"uxtos: ${utxos.map(utxo => s"${utxo.item.value}").mkString("|")}"
    )
    val b = utxos.foldLeft(0L.sat)(_ + _.item.value.sat)
    System.err.println(s"balance: $b")
    b
  }

  // Computes the effect of this transaction on the wallet
  def computeTransactionDelta(tx: Transaction): Option[TransactionDelta] = {
    // ourInputs will be empty if we're receiving this transaction normally
    val ourInputs = tx.txIn.filter(isMine)

    // can only be computed if all our inputs have parents
    val allParentsArePresent =
      ourInputs.forall(txIn => transactions.contains(txIn.outPoint.txid))

    if (!allParentsArePresent) None
    else {
      val spentUtxos = ourInputs.map { txIn =>
        // This may be needed for RBF and it's a good place to create these UTXOs
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
    def computeFee(candidates: Seq[Utxo], change: Option[TxOut]) = {
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
    ): (Seq[Utxo], Option[TxOut]) = current.map(_.item.value).sum.sat match {
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
    history: Map[ByteVector32, List[TransactionHistoryItem]] = Map.empty,
    proofs: Map[ByteVector32, GetMerkleResponse] = Map.empty,
    pendingTransactions: List[Transaction] = Nil,
    excludedOutPoints: List[OutPoint] = Nil
)
