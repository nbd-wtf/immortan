package immortan

import fr.acinq.eclair._
import fr.acinq.bitcoin._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import com.softwaremill.sttp._
import fr.acinq.eclair.Features._
import scala.concurrent.duration._
import fr.acinq.eclair.blockchain.fee._
import fr.acinq.eclair.blockchain.electrum._
import fr.acinq.bitcoin.DeterministicWallet._
import scodec.bits.{ByteVector, HexStringSyntax}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import immortan.utils.{FiatRatesInfo, WalletEventsCatcher}
import scala.concurrent.{ExecutionContextExecutor, Future}
import fr.acinq.eclair.router.Router.{PublicChannel, RouterConf}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import fr.acinq.eclair.transactions.{DirectedHtlc, Transactions}
import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import fr.acinq.eclair.channel.{LocalParams, NormalCommits, PersistentChannelData}
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.eclair.blockchain.electrum.db.WalletDb
import fr.acinq.eclair.router.ChannelUpdateExt
import fr.acinq.eclair.payment.PaymentRequest
import org.bitcoinj.core.NetworkParameters
import immortan.SyncMaster.ShortChanIdSet
import immortan.PaymentInfo.RevealedParts
import fr.acinq.eclair.crypto.Generators
import immortan.crypto.Noise.KeyPair
import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import akka.util.Timeout


object LNParams {
  val blocksPerDay: Int = 144 // On average we can expect this many blocks per day
  val cltvRejectThreshold: Int = 144 // Reject incoming payment if CLTV expiry is closer than this to current chain tip when HTLC arrives
  val incomingPaymentCltvExpiry: Int = 144 + 72 // Ask payer to set final CLTV expiry to payer's current chain tip + this many blocks

  val maxCltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(1008)
  val maxToLocalDelay: CltvExpiryDelta = CltvExpiryDelta(2016)
  val maxFundingSatoshis: Satoshi = Satoshi(10000000000L)
  val maxNegotiationIterations: Int = 20
  val maxChainConnectionsCount: Int = 5
  val maxReserveToFundingRatio = 0.05
  val maxAcceptedHtlcs: Int = 483

  val minCltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(18)
  val minHostedOnChainRefund: Satoshi = Satoshi(1000000L)
  val minPayment: MilliSatoshi = MilliSatoshi(5000L)
  val minFundingSatoshis: Satoshi = Satoshi(100000L)
  val minHostedLiabilityBlockdays: Int = 365
  val minDustLimit: Satoshi = Satoshi(546L)
  val minDepthBlocks: Int = 3

  val jParams: NetworkParameters = org.bitcoinj.params.MainNetParams.get
  val chainHash: ByteVector32 = Block.LivenetGenesisBlock.hash
  val reserveToFundingRatio = 0.0025 // %
  val offChainFeeRatio = 0.01 // %

  // Init messages + features

  private[this] val networks: InitTlv = InitTlv.Networks(chainHash :: Nil)
  private[this] val tlvStream: TlvStream[InitTlv] = TlvStream(networks)

  val normInit: Init = Init(Features(
    (ChannelRangeQueries, FeatureSupport.Optional),
    (ChannelRangeQueriesExtended, FeatureSupport.Optional),
    (OptionDataLossProtect, FeatureSupport.Optional),
    (BasicMultiPartPayment, FeatureSupport.Optional),
    (VariableLengthOnion, FeatureSupport.Optional),
    (TrampolineRouting, FeatureSupport.Optional),
    (AnchorOutputs, FeatureSupport.Optional),
    (PaymentSecret, FeatureSupport.Optional),
    (ChainSwap, FeatureSupport.Optional),
    (Wumbo, FeatureSupport.Optional)
  ), tlvStream)

  val phcSyncInit: Init = Init(Features(
    (ChannelRangeQueries, FeatureSupport.Optional),
    (ChannelRangeQueriesExtended, FeatureSupport.Optional),
    (HostedChannels, FeatureSupport.Optional)
  ), tlvStream)

  val hcInit: Init = Init(Features(
    (ChannelRangeQueries, FeatureSupport.Optional),
    (ChannelRangeQueriesExtended, FeatureSupport.Optional),
    (BasicMultiPartPayment, FeatureSupport.Optional),
    (VariableLengthOnion, FeatureSupport.Optional),
    (TrampolineRouting, FeatureSupport.Optional),
    (HostedChannels, FeatureSupport.Optional),
    (PaymentSecret, FeatureSupport.Optional),
    (ChainSwap, FeatureSupport.Optional)
  ), tlvStream)

  // Variables to be assigned at runtime

  var cm: ChannelMaster = _
  var format: StorageFormat = _
  var chainWallet: WalletExt = _
  var syncParams: SyncParams = _
  var fiatRatesInfo: FiatRatesInfo = _

  var routerConf: RouterConf =
    RouterConf(maxCltv = CltvExpiryDelta(1008), routeHopDistance = 6,
      mppMinPartAmount = MilliSatoshi(30000000L), maxRemoteAttempts = 12,
      maxChannelFailures = 12, maxStrangeNodeFailures = 12)

  // Chain feerate utils

  val defaultFeerates: FeeratesPerKB =
    FeeratesPerKB(
      block_1 = FeeratePerKB(210000.sat),
      blocks_2 = FeeratePerKB(180000.sat),
      blocks_6 = FeeratePerKB(150000.sat),
      blocks_12 = FeeratePerKB(110000.sat),
      blocks_36 = FeeratePerKB(50000.sat),
      blocks_72 = FeeratePerKB(20000.sat),
      blocks_144 = FeeratePerKB(15000.sat),
      blocks_1008 = FeeratePerKB(5000.sat),
      mempoolMinFee = FeeratePerKB(5000.sat)
    )

  val feeratesPerKB: AtomicReference[FeeratesPerKB] = new AtomicReference(defaultFeerates)
  val feeratesPerKw: AtomicReference[FeeratesPerKw] = new AtomicReference(FeeratesPerKw apply defaultFeerates)
  val blockCount: AtomicLong = new AtomicLong(0L)

  val feeEstimator: FeeEstimator = new FeeEstimator {
    override def getFeeratePerKb(target: Int): FeeratePerKB = feeratesPerKB.get.feePerBlock(target)
    override def getFeeratePerKw(target: Int): FeeratePerKw = feeratesPerKw.get.feePerBlock(target)
  }

  val onChainFeeConf: OnChainFeeConf =
    OnChainFeeConf(FeeTargets(fundingBlockTarget = 6, commitmentBlockTarget = 6, mutualCloseBlockTarget = 36, claimMainBlockTarget = 36),
      feeEstimator, closeOnOfflineMismatch = false, updateFeeMinDiffRatio = 0.1, FeerateTolerance(0.5, 10), perNodeFeerateTolerance = Map.empty)

  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val system: ActorSystem = ActorSystem("immortan-actor-system")
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.Implicits.global
  implicit val sttpBackend: SttpBackend[Future, Nothing] = OkHttpFutureBackend(SttpBackendOptions.Default)

  val feerateProviders: List[FeeProvider] = List(
    new EsploraFeeProvider(uri"https://mempool.space/api/fee-estimates", 15.seconds),
    new EsploraFeeProvider(uri"https://blockstream.info/api/fee-estimates", 15.seconds),
    new BitgoFeeProvider(chainHash, 15.seconds),
    new EarnDotComFeeProvider(15.seconds)
  )

  def createWallet(addresses: Set[ElectrumServerAddress], walletDb: WalletDb, seed: ByteVector): WalletExt = {
    val clientPool = system.actorOf(SimpleSupervisor.props(Props(new ElectrumClientPool(blockCount, addresses)), "pool", SupervisorStrategy.Resume))
    val watcher = system.actorOf(SimpleSupervisor.props(Props(new ElectrumWatcher(blockCount, clientPool)), "watcher", SupervisorStrategy.Resume))
    val wallet = system.actorOf(ElectrumWallet.props(seed, clientPool, ElectrumWallet.WalletParameters(chainHash, walletDb)), "wallet")
    val catcher = system.actorOf(Props(new WalletEventsCatcher), "catcher")
    val eclairWallet = new ElectrumEclairWallet(wallet, chainHash)
    WalletExt(eclairWallet, catcher, clientPool, watcher)
  }

  def currentBlockDay: Long = blockCount.get / blocksPerDay
}

class SyncParams {
  val blw: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03144fcc73cea41a002b2865f98190ab90e4ff58a2ce24d3870f5079081e42922d"), NodeAddress.unresolved(9735, host = 5, 9, 83, 143), "BLW Den")
  val lightning: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03baa70886d9200af0ffbd3f9e18d96008331c858456b16e3a9b41e735c6208fef"), NodeAddress.unresolved(9735, host = 45, 20, 67, 1), "LIGHTNING")
  val conductor: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03c436af41160a355fc1ed230a64f6a64bcbd2ae50f12171d1318f9782602be601"), NodeAddress.unresolved(9735, host = 18, 191, 89, 219), "Conductor")
  val cheese: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"0276e09a267592e7451a939c932cf685f0754de382a3ca85d2fb3a864d4c365ad5"), NodeAddress.unresolved(9735, host = 94, 177, 171, 73), "Cheese")
  val acinq: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), NodeAddress.unresolved(9735, host = 34, 239, 230, 56), "ACINQ")

  val hostedChanNodes: Set[RemoteNodeInfo] = Set(blw, lightning, acinq) // Trusted nodes which are shown as default ones when user chooses providers
  val hostedSyncNodes: Set[RemoteNodeInfo] = Set(blw, lightning, acinq) // Semi-trusted PHC-enabled nodes which can be used as seeds for PHC sync
  val syncNodes: Set[RemoteNodeInfo] = Set(lightning, acinq, conductor) // Nodes with extended queries support used as seeds for normal sync

  val maxPHCCapacity: MilliSatoshi = MilliSatoshi(1000000000000000L) // PHC can not be larger than 10 000 BTC
  val minPHCCapacity: MilliSatoshi = MilliSatoshi(50000000000L) // PHC can not be smaller than 0.5 BTC
  val minNormalChansForPHC = 5 // How many normal chans a node must have to be eligible for PHCs
  val maxPHCPerNode = 2 // How many PHCs a node can have in total

  val minCapacity: MilliSatoshi = MilliSatoshi(1000000000L) // 1M sat
  val maxNodesToSyncFrom = 3 // How many disjoint peers to use for majority sync
  val acceptThreshold = 1 // ShortIds and updates are accepted if confirmed by more than this peers
  val messagesToAsk = 1000 // Ask for this many messages from peer before they say this chunk is done
  val chunksToWait = 4 // Wait for at least this much chunk iterations from any peer before recording results
}

// Important: LNParams.format must be defined
case class RemoteNodeInfo(nodeId: PublicKey, address: NodeAddress, alias: String) {
  lazy val nodeSpecificExtendedKey: DeterministicWallet.ExtendedPrivateKey = LNParams.format.keys.ourFakeNodeIdKey(nodeId)

  lazy val nodeSpecificPrivKey: PrivateKey = nodeSpecificExtendedKey.privateKey

  lazy val nodeSpecificPubKey: PublicKey = nodeSpecificPrivKey.publicKey

  lazy val nodeSpecificPair: KeyPairAndPubKey = KeyPairAndPubKey(KeyPair(nodeSpecificPubKey.value, nodeSpecificPrivKey.value), nodeId)

  private def derivePrivKey(path: KeyPath) = derivePrivateKey(nodeSpecificExtendedKey, path)

  private val channelPrivateKeysMemo = memoize(derivePrivKey)

  private val channelPublicKeysMemo = memoize(channelPrivateKeysMemo.get _ andThen publicKey)

  private def internalKeyPath(channelKeyPath: DeterministicWallet.KeyPath, index: Long): Seq[Long] = channelKeyPath.path :+ hardened(index)

  private def shaSeed(channelKeyPath: DeterministicWallet.KeyPath): ByteVector32 = {
    val extendedKey = channelPrivateKeysMemo getUnchecked internalKeyPath(channelKeyPath, 5L)
    Crypto.sha256(extendedKey.privateKey.value :+ 1.toByte)
  }

  def keyPath(localParams: LocalParams): DeterministicWallet.KeyPath = {
    val fundPubKey = fundingPublicKey(localParams.fundingKeyPath).publicKey
    val bis = new ByteArrayInputStream(Crypto.sha256(fundPubKey.value).toArray)
    def nextHop: Long = Protocol.uint32(input = bis, order = ByteOrder.BIG_ENDIAN)
    val path = Seq(nextHop, nextHop, nextHop, nextHop, nextHop, nextHop, nextHop, nextHop)
    DeterministicWallet.KeyPath(path)
  }

  def newFundingKeyPath(isFunder: Boolean): KeyPath = {
    def nextHop: Long = secureRandom.nextInt & 0xFFFFFFFFL
    val last = DeterministicWallet hardened { if (isFunder) 1 else 0 }
    val path = Seq(nextHop, nextHop, nextHop, nextHop, nextHop, nextHop, nextHop, nextHop, last)
    DeterministicWallet.KeyPath(path)
  }

  def fundingPublicKey(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeysMemo getUnchecked internalKeyPath(channelKeyPath, 0L)

  def revocationPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeysMemo getUnchecked internalKeyPath(channelKeyPath, 1L)

  def paymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeysMemo getUnchecked internalKeyPath(channelKeyPath, 2L)

  def delayedPaymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeysMemo getUnchecked internalKeyPath(channelKeyPath, 3L)

  def htlcPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeysMemo getUnchecked internalKeyPath(channelKeyPath, 4L)

  def commitmentSecret(channelKeyPath: DeterministicWallet.KeyPath, index: Long): PrivateKey = Generators.perCommitSecret(shaSeed(channelKeyPath), index)

  def commitmentPoint(channelKeyPath: DeterministicWallet.KeyPath, index: Long): PublicKey = Generators.perCommitPoint(shaSeed(channelKeyPath), index)

  def sign(tx: Transactions.TransactionWithInputInfo, publicKey: ExtendedPublicKey, txOwner: Transactions.TxOwner, commitmentFormat: Transactions.CommitmentFormat): ByteVector64 =
    Transactions.sign(tx, channelPrivateKeysMemo(publicKey.path).privateKey, txOwner, commitmentFormat)

  def sign(tx: Transactions.TransactionWithInputInfo, publicKey: ExtendedPublicKey, remotePoint: PublicKey, txOwner: Transactions.TxOwner, commitmentFormat: Transactions.CommitmentFormat): ByteVector64 =
    Transactions.sign(tx, Generators.derivePrivKey(channelPrivateKeysMemo(publicKey.path).privateKey, remotePoint), txOwner, commitmentFormat)

  def sign(tx: Transactions.TransactionWithInputInfo, publicKey: ExtendedPublicKey, remoteSecret: PrivateKey, txOwner: Transactions.TxOwner, commitmentFormat: Transactions.CommitmentFormat): ByteVector64 =
    Transactions.sign(tx, Generators.revocationPrivKey(channelPrivateKeysMemo(publicKey.path).privateKey, remoteSecret), txOwner, commitmentFormat)
}

case class WalletExt(wallet: ElectrumEclairWallet, eventsCatcher: ActorRef, clientPool: ActorRef, watcher: ActorRef)

case class UpdateAddHtlcExt(theirAdd: UpdateAddHtlc, remoteInfo: RemoteNodeInfo)

case class SwapInStateExt(state: SwapInState, nodeId: PublicKey)

case class PaymentRequestExt(pr: PaymentRequest, raw: String)

// Interfaces

trait NetworkDataStore {
  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit
  def listChannelAnnouncements: Iterable[ChannelAnnouncement]

  def addChannelUpdateByPosition(cu: ChannelUpdate): Unit
  def listChannelUpdates: Iterable[ChannelUpdateExt]

  // We disregard position and always exclude channel as a whole
  def addExcludedChannel(shortId: ShortChannelId, untilStamp: Long): Unit
  def listChannelsWithOneUpdate: ShortChanIdSet
  def listExcludedChannels: Set[Long]

  def incrementChannelScore(cu: ChannelUpdate): Unit
  def removeChannelUpdate(shortId: ShortChannelId): Unit
  def removeGhostChannels(ghostIds: ShortChanIdSet, oneSideIds: ShortChanIdSet): Unit
  def getRoutingData: Map[ShortChannelId, PublicChannel]

  def processCompleteHostedData(pure: CompleteHostedRoutingData): Unit
  def processPureData(data: PureRoutingData): Unit
}

// Bag of stored payments and successful relays

trait PaymentBag {
  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo]
  def getRelayedPreimageInfo(paymentHash: ByteVector32): Option[RelayedPreimageInfo]

  def addRelayedPreimageInfo(paymentHash: ByteVector32, preimage: ByteVector32, stamp: Long,
                             relayed: MilliSatoshi, earned: MilliSatoshi)

  def replaceOutgoingPayment(prex: PaymentRequestExt, desc: PaymentDescription, action: Option[PaymentAction],
                             finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc,
                             chainFee: MilliSatoshi): Unit

  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription,
                             balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc, chainFee: MilliSatoshi): Unit

  // These MUST be the only two methods capable of updating payment state to SUCCEEDED
  def updOkIncoming(revealedParts: RevealedParts, paymentHash: ByteVector32): Unit
  def updOkOutgoing(upd: UpdateFulfillHtlc, fee: MilliSatoshi): Unit
}

object ChannelBag {
  case class Hash160AndCltv(hash160: ByteVector, cltvExpiry: CltvExpiry)
}

trait ChannelBag {
  def all: Iterable[PersistentChannelData]
  def hide(commitments: NormalCommits): Unit
  def delete(commitments: HostedCommits): Unit
  def put(data: PersistentChannelData): PersistentChannelData

  def htlcInfos(commitNumer: Long): Iterable[ChannelBag.Hash160AndCltv]
  def putHtlcInfo(sid: ShortChannelId, commitNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit
  def putHtlcInfos(htlcs: Seq[DirectedHtlc], sid: ShortChannelId, commitNumber: Long): Unit
  def rmHtlcInfos(sid: ShortChannelId): Unit
}