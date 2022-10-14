package fr.acinq.eclair.blockchain.electrum

import java.util.concurrent.atomic.AtomicInteger
import scala.util.chaining._
import scala.annotation.tailrec
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

import immortan.LNParams.ec

import Debugggg._

object Debugggg {
  val shs = List(
    "0df0dd195d9a1d36c6ac61af28efea21f64efe376b60895815057e63c3e0a6aa", // account 17
    "d88c76d2c9b1985359574659ced0059e5159710db6fc4783c85ed7453f344fd6", // account 18
    "9db0f693c209127a672625a3a1d4009e0b52d64f82342ba38d9af93b348e8cb9" // change 11
  )
  val txids = List(
    "7cf8414d2a2069c200c299aac77a1abade95591e329f7382f2f64a237cee7b95",
    "99de3e9a21f4b1f3cbbafe67a9fad51cc6c1c646487e5bb73f091347944ab2b6",
    "49d76ac7ca241a973410ef9cb0429a2a990ca7599e4cba1aed7ffa39158654ec",
    "c3fa4e094ef6edbc8058f3e7945a4594fa03ee93b08f791c2be80dbf0c29e05d"
  )
}

class BadElectrumData(msg: String) extends Exception(msg)
class BadMerkleProof(
    txid: ByteVector32,
    rootFromHeader: ByteVector32,
    rootFromProof: ByteVector32,
    heightRequested: Int,
    heightReceived: Int
) extends BadElectrumData(
      s"txid=$txid, root-from-header=$rootFromHeader, root-from-proof=$rootFromProof, height-requested: $heightRequested, height-received=$heightReceived"
    )
class BadTxIdMismatched(requested: ByteVector32, received: ByteVector32)
    extends BadElectrumData(s"requested=$requested, received=$received")

class ElectrumWallet(
    pool: ElectrumClientPool,
    chainSync: ElectrumChainSync,
    params: WalletParameters,
    ewt: ElectrumWalletType
) {
  sealed trait State
  case object Disconnected extends State
  case object Running extends State

  var data: ElectrumData = _
  var state: State = Disconnected

  val scriptHashesSyncing: AtomicInteger = new AtomicInteger(0)
  var maxEverInConcurrentSync = 0 // this is used in the UI
  val lastStatusReceivedForScriptHash =
    scala.collection.mutable.Map.empty[ByteVector32, String]
  val scriptHashesBeingTracked =
    scala.collection.mutable.Set.empty[ByteVector32]

  def persistAndNotify(): Unit = {
    EventStream.publish(data.readyMessage)

    params.walletDb.persist(
      data.toPersistent,
      data.balance,
      ewt.xPub.publicKey
    )
  }

  EventStream
    .subscribe {
      case BlockchainReady(bc)  => blockchainReady(bc)
      case ElectrumDisconnected => state = Disconnected
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
    synchronized { data = data.copy(blockchain = bc) }
    persistAndNotify()

    state match {
      case Disconnected =>
        state = Running

        data.accountKeyMap.keys.foreach(trackScriptHash(_))
        data.changeKeyMap.keys.foreach(trackScriptHash(_))
      case Running =>
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
      System.err.println(
        s"[debug][wallet] refilling account key (${xp.path.lastChildNumber}): ${sh.toHex.take(6)}"
      )

      synchronized {
        data = data.copy(
          status = data.status.updated(sh, ""),
          accountKeys = data.accountKeys :+ xp
        )
      }
      trackScriptHash(sh)
    }

    if (data.firstUnusedChangeKeys.size < MAX_RECEIVE_ADDRESSES) {
      val (xp, sh) = newKey(ewt.changeMaster, data.changeKeys)
      System.err.println(
        s"[debug][wallet] refilling change key (${xp.path.lastChildNumber}): ${sh.toHex.take(6)}"
      )

      synchronized {
        data = data.copy(
          status = data.status.updated(sh, ""),
          changeKeys = data.changeKeys :+ xp
        )
      }
      trackScriptHash(sh)
    }
  }

  def trackScriptHash(scriptHash: ByteVector32): Unit =
    if (!scriptHashesBeingTracked.contains(scriptHash)) {
      synchronized {
        scriptHashesBeingTracked += scriptHash
      }

      pool
        .subscribeToScriptHash("wallet", scriptHash) {
          case ScriptHashSubscriptionResponse(scriptHash, status) =>
            if (
              status != "" &&
              Some(status) != data.status.get(scriptHash) &&
              Some(status) != lastStatusReceivedForScriptHash.get(scriptHash) &&
              (data.accountKeyMap.contains(scriptHash) ||
                data.changeKeyMap.contains(scriptHash))
            ) {
              // keep track of this status so we don't run this same thing twice for the same updates
              //   (this shouldn't happen in general because of the debouncing, but it's still a good idea
              //    to put this here just after the check)
              synchronized {
                lastStatusReceivedForScriptHash += (scriptHash -> status)
              }

              System.err.println(
                s"[debug][wallet] script hash ${scriptHash.toString().take(6)} has changed (${status.take(6)}), requesting history"
              )

              // emit events so wallets can display nice things
              if (scriptHashesSyncing.getAndIncrement() == 0)
                EventStream.publish(WalletSyncStarted)
              if (scriptHashesSyncing.get > maxEverInConcurrentSync)
                maxEverInConcurrentSync += 1
              EventStream.publish(
                WalletSyncProgress(
                  maxEverInConcurrentSync,
                  scriptHashesSyncing.get
                )
              )

              val result = for {
                history <- pool
                  .request[GetScriptHashHistoryResponse](
                    GetScriptHashHistory(scriptHash)
                  )
                  .map { case GetScriptHashHistoryResponse(_, items) =>
                    items
                  }
                  .map(_.sortBy(_.height))
                _ = System.err.println(
                  s"[debug][wallet] got history for ${scriptHash.toHex.take(6)}: ${history}"
                )

                // fetch headers if missing for any of these items
                _ <- Future.sequence(history.map { item =>
                  if (
                    item.height > 0 /* we don't want headers for unconfirmed transactions */ &&
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
                    chainSync
                      .getHeaders(
                        item.height / RETARGETING_PERIOD * RETARGETING_PERIOD,
                        RETARGETING_PERIOD
                      )
                    // .andThen { _ =>
                    //   System.err.println(
                    //     s"[debug][wallet] got missing headers for ${item.txHash}"
                    //   )
                    // }
                  } else Future { () }
                })

                // fetch transaction and merkle proof for all these txids
                // (if the user is doing things right we'll have at most 2 transactions for each script hash)
                merkleProofs <- Future.sequence(
                  history
                    .filter(
                      _.height > 0
                    ) // only do this when the tx is confirmed
                    .map(item =>
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
                                    item.txHash,
                                    existingHeader.hashMerkleRoot,
                                    merkle.root,
                                    item.height,
                                    merkle.blockHeight
                                  )
                                case Some(_) =>
                                // it's ok
                                // merkle root matches block header
                                case None =>
                                  throw new Exception(
                                    s"no header ${merkle.blockHeight} for ${item.txHash} merkle proof? this should never happen."
                                  )
                              }
                            }
                        )
                    )
                )

                transactions <- Future
                  .sequence(
                    history.map(item =>
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
                                  item.txHash,
                                  tx.txid
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
                                  throw new BadTxIdMismatched(txid, tx.txid)
                              }
                          )
                      )
                  )
              } yield {
                // prepare updated data
                synchronized {
                  val newHistory = {
                    data.history.updatedWith(scriptHash) {
                      case None           => Some(history)
                      case Some(existing) =>
                        // keep history items that we have and they don't
                        // except unconfirmed transactions we had, these we discard
                        //   since they may have been dropped from mempool or replaced
                        val oldItems = existing.filter(_.height > 0)
                        val newItems =
                          history.filterNot(oldItems.contains(_))
                        Some(oldItems ++ newItems)
                    }
                  }

                  val added = newHistory.keySet -- data.history.keySet
                  val removed = data.history.keySet -- data.history.keySet
                  System.err.println(
                    s"[debug][wallet] saving history (${newHistory.size}): added [${added
                        .mkString(" ")}], removed [${removed.mkString(" ")}]"
                  )

                  data = data
                    .copy(
                      // save the status
                      status = data.status.updated(scriptHash, status),

                      // add the history
                      history = newHistory,

                      // add all transactions we got
                      transactions = data.transactions ++
                        transactions.map(tx => tx.txid -> tx) ++
                        parents.map(tx => tx.txid -> tx),

                      // add all merkle proofs
                      proofs = data.proofs ++ merkleProofs.map(merkle =>
                        merkle.txid -> merkle
                      ),

                      // even though we have excluded some utxos in this wallet user may still
                      //   spend them from elsewhere, so clear excluded outpoints here
                      excludedOutPoints =
                        transactions.foldLeft(data.excludedOutPoints) {
                          case (exc, tx) => exc.diff(tx.txIn.map(_.outPoint))
                        }
                    )
                    .withOverridingTxids // this accounts for double-spends like RBF
                }

                // update data
                persistAndNotify()

                // refill keys
                Future { keyRefill() }

                // notify all transactions so they can be stored/displayed etc by wallet
                transactions.foreach { tx =>
                  data.computeTransactionDelta(tx).map {
                    case TransactionDelta(_, feeOpt, received, sent) =>
                      EventStream.publish(
                        TransactionReceived(
                          tx = tx,
                          depth = data.depth(tx.txid),
                          stamp = data.timestamp(tx.txid, params.headerDb),
                          received = received,
                          sent = sent,
                          walletAddreses = tx.txOut
                            .filter(data.isMine)
                            .map(_.publicKeyScript)
                            .flatMap(data.publicScriptMap.get)
                            .map(data.ewt.textAddress)
                            .toList,
                          xPub = ewt.xPub,
                          feeOpt = feeOpt
                        )
                      )
                  }
                }
              }

              result
                .andThen { _ =>
                  // emit events so wallets can show visual things to users
                  if (scriptHashesSyncing.decrementAndGet() == 0)
                    EventStream.publish(WalletSyncEnded)

                  EventStream.publish(
                    WalletSyncProgress(
                      maxEverInConcurrentSync,
                      scriptHashesSyncing.get
                    )
                  )
                }
                .andThen { _ =>
                  synchronized {
                    if (
                      lastStatusReceivedForScriptHash
                        .get(scriptHash) == Some(status)
                    )
                      lastStatusReceivedForScriptHash.remove(scriptHash)
                  }
                }
                .onComplete {
                  case Success(_) =>
                    System.err.println(
                      s"[info][wallet] scripthash ${scriptHash.toHex.take(6)} updated successfully"
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
            } else keyRefill()
        }
    }

  def getData = data

  def provideExcludedOutPoints(excluded: List[OutPoint]): Unit = {
    synchronized {
      data = data.copy(excludedOutPoints = excluded)
    }
    persistAndNotify()
  }

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
  sealed trait Event extends ElectrumEvent
  case object WalletSyncStarted extends Event
  case class WalletSyncProgress(max: Int, left: Int) extends Event
  case object WalletSyncEnded extends Event

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

  lazy val firstUnusedAccountKeys: Iterable[ExtendedPublicKey] =
    accountKeyMap.collect {
      case (nonExistentScriptHash, privKey)
          if !status.contains(nonExistentScriptHash) =>
        privKey
      case (emptyScriptHash, privKey) if status(emptyScriptHash) == "" =>
        privKey
    }

  lazy val firstUnusedChangeKeys: Iterable[ExtendedPublicKey] = {
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
      accountKeyMap
        .get(scriptHash)
        .orElse(changeKeyMap.get(scriptHash))
        .map { key =>
          // We definitely have a private key generated for corresponding scriptHash here
          val txos = for {
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

          // Find all out points which spend from this script hash and make sure txos do not contain them
          txos.filterNot { txo =>
            historyItems
              .map(_.txHash)
              .flatMap(transactions.get)
              .flatMap(_.txIn)
              .map(_.outPoint)
              .toSet
              .contains(txo.item.outPoint)
          }
        }
        .getOrElse(Seq.empty)
    }
  }

  lazy val utxos: Seq[Utxo] =
    unExcludedUtxos.filterNot(utxo =>
      excludedOutPoints.contains(utxo.item.outPoint)
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

  lazy val balance: Satoshi =
    utxos.foldLeft(0L.sat)(_ + _.item.value.sat)

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
        // someone is sending this to us
        Some(TransactionDelta(spentUtxos, None, mineReceived, mineSent))
      else
        // we're sending this to someone
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
    // the result of this is used to determine what transaction is overriding what
    def overridingScore(tx: Transaction): Long =
      if (proofs.contains(tx.txid))
        // this was confirmed on the blockchain, so it always wins
        Long.MaxValue
      else
        // we check the depth of the bip32 derivation, the higher one wins
        tx.txOut
          .map(_.publicKeyScript)
          .flatMap(publicScriptChangeMap.get)
          .map(_.path.lastChildNumber)
          .headOption
          .getOrElse(Long.MinValue)

    def computeOverride(
        txsThatSpendTheSameUtxo: Iterable[Transaction] = Nil
    ): LazyList[(ByteVector32, ByteVector32)] =
      LazyList
        .continually(txsThatSpendTheSameUtxo.maxBy(overridingScore))
        .zip(txsThatSpendTheSameUtxo)
        .collect {
          case (laterTx, formerTx) if laterTx != formerTx =>
            formerTx.txid -> laterTx.txid
        }

    copy(overriddenPendingTxids =
      transactions.values
        .map(LazyList.continually(_))
        .flatMap(txStream => txStream.head.txIn.zip(txStream))
        .groupBy(_._1.outPoint)
        .values
        .map(_.secondItems)
        .flatMap(computeOverride)
        .toMap
    )
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
