package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.Features._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.channel.{ChannelData, LocalParams}
import fr.acinq.eclair.router.{Announcements, ChannelUpdateExt}
import fr.acinq.eclair.router.Router.{PublicChannel, RouterConf}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import fr.acinq.eclair.{ActivatedFeature, CltvExpiryDelta, FeatureSupport, Features}
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto, DeterministicWallet, Protocol, Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeTargets, FeeratePerKB, FeeratePerKw, FeerateTolerance, FeeratesPerKB, FeeratesPerKw, OnChainFeeConf}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.payment.PaymentRequest
import immortan.SyncMaster.ShortChanIdSet
import fr.acinq.eclair.crypto.Generators
import immortan.crypto.Noise.KeyPair
import java.io.ByteArrayInputStream
import scala.collection.mutable
import scodec.bits.ByteVector
import immortan.crypto.Tools
import java.nio.ByteOrder


object LNParams {
  val blocksPerDay: Int = 144 // On average we can expect this many blocks per day
  val cltvRejectThreshold: Int = 144 // Reject incoming payment if CLTV expiry is closer than this to current chain tip when HTLC arrives
  val incomingPaymentCltvExpiry: Int = 144 + 72 // Ask payer to set final CLTV expiry to payer's current chain tip + this many blocks

  val chainHash: ByteVector32 = Block.LivenetGenesisBlock.hash
  val minHostedOnChainRefund: Satoshi = 1000000L.sat
  val minPayment: MilliSatoshi = 5000L.msat
  val minHostedLiabilityBlockdays = 365

  val maxToLocalDelay: CltvExpiryDelta = CltvExpiryDelta(2016)
  val maxFundingSatoshis: Satoshi = 1000000000.sat
  val minFundingSatoshis: Satoshi = 100000L.sat
  val maxReserveToFundingRatio = 0.05 // %
  val reserveToFundingRatio = 0.0025 // %
  val minDepthBlocks: Int = 1

  var routerConf: RouterConf =
    RouterConf(searchMaxFeeBase = MilliSatoshi(25000L),
      searchMaxFeePct = 0.01, firstPassMaxCltv = CltvExpiryDelta(1008),
      firstPassMaxRouteLength = 6, mppMinPartAmount = MilliSatoshi(30000000L),
      maxRemoteAttempts = 12, maxChannelFailures = 12, maxStrangeNodeFailures = 12)

  lazy val (normInit, phcSyncInit, hcInit) = {
    val networks: InitTlv = InitTlv.Networks(chainHash :: Nil)
    val tlvStream: TlvStream[InitTlv] = TlvStream(networks)

    // Mimic phoenix
    val normFeatures: Set[ActivatedFeature] = Set(
      ActivatedFeature(OptionDataLossProtect, FeatureSupport.Mandatory),
      ActivatedFeature(BasicMultiPartPayment, FeatureSupport.Optional),
      ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional),
      ActivatedFeature(PaymentSecret, FeatureSupport.Optional),
      ActivatedFeature(Wumbo, FeatureSupport.Optional)
    )

    val phcSyncFeatures: Set[ActivatedFeature] = Set(
      ActivatedFeature(HostedChannels, FeatureSupport.Mandatory)
    )

    val hcFeatures: Set[ActivatedFeature] = Set(
      ActivatedFeature(ChannelRangeQueriesExtended, FeatureSupport.Mandatory),
      ActivatedFeature(BasicMultiPartPayment, FeatureSupport.Mandatory),
      ActivatedFeature(ChannelRangeQueries, FeatureSupport.Mandatory),
      ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory),
      ActivatedFeature(HostedChannels, FeatureSupport.Mandatory),
      ActivatedFeature(ChainSwap, FeatureSupport.Optional)
    )

    val norm = Init(Features(normFeatures), tlvStream)
    val phcSync = Init(Features(phcSyncFeatures), tlvStream)
    val hc = Init(Features(hcFeatures), tlvStream)
    (norm, phcSync, hc)
  }

  var format: StorageFormat = _
  var channelMaster: ChannelMaster = _

  val blockCount = new AtomicLong(0L)
  val feeratesPerKB = new AtomicReference[FeeratesPerKB](null)
  val feeratesPerKw = new AtomicReference[FeeratesPerKw](null)

  val feeEstimator: FeeEstimator = new FeeEstimator {
    override def getFeeratePerKb(target: Int): FeeratePerKB = feeratesPerKB.get.feePerBlock(target)
    override def getFeeratePerKw(target: Int): FeeratePerKw = feeratesPerKw.get.feePerBlock(target)
  }

  val onChainFeeConf: OnChainFeeConf =
    OnChainFeeConf(FeeTargets(fundingBlockTarget = 6, commitmentBlockTarget = 6, mutualCloseBlockTarget = 36, claimMainBlockTarget = 36),
      feeEstimator, closeOnOfflineMismatch = false, updateFeeMinDiffRatio = 0.1, FeerateTolerance(0.5, 10), perNodeFeerateTolerance = Map.empty)

  def currentBlockDay: Long = blockCount.get / blocksPerDay
}

// Extension wrappers

case class NodeAnnouncementExt(na: NodeAnnouncement) {
  lazy val prettyNodeName: String = na.addresses collectFirst {
    case _: IPv4 | _: IPv6 => na.nodeId.toString take 15 grouped 3 mkString "\u0020"
    case _: Tor2 => s"<strong>Tor</strong>\u0020${na.nodeId.toString take 12 grouped 3 mkString "\u0020"}"
    case _: Tor3 => s"<strong>Tor</strong>\u0020${na.nodeId.toString take 12 grouped 3 mkString "\u0020"}"
  } getOrElse "No IP address"

  // Important: this relies on format being defined at runtime
  // we do not provide format as class field here to avoid storing of duplicated data
  lazy val nodeSpecificExtendedKey: DeterministicWallet.ExtendedPrivateKey = LNParams.format.keys.ourFakeNodeIdKey(na.nodeId)
  lazy val nodeSpecificPrivKey: PrivateKey = nodeSpecificExtendedKey.privateKey
  lazy val nodeSpecificPubKey: PublicKey = nodeSpecificPrivKey.publicKey

  lazy val nodeSpecificPkap: KeyPairAndPubKey = KeyPairAndPubKey(KeyPair(nodeSpecificPubKey.value, nodeSpecificPrivKey.value), na.nodeId)
  lazy val nodeSpecificHostedChanId: ByteVector32 = Tools.hostedChanId(nodeSpecificPubKey.value, na.nodeId.value)

  private def derivePrivKey(path: KeyPath) = derivePrivateKey(nodeSpecificExtendedKey, path)
  private val channelPrivateKeys: mutable.Map[KeyPath, ExtendedPrivateKey] = Tools.memoize(derivePrivKey)
  private val channelPublicKeys: mutable.Map[KeyPath, ExtendedPublicKey] = Tools.memoize(channelPrivateKeys andThen publicKey)

  private def internalKeyPath(channelKeyPath: DeterministicWallet.KeyPath, index: Long): Seq[Long] = channelKeyPath.path :+ hardened(index)

  private def fundingPrivateKey(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = channelPrivateKeys(internalKeyPath(channelKeyPath, 0L))

  private def revocationSecret(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = channelPrivateKeys(internalKeyPath(channelKeyPath, 1L))

  private def paymentSecret(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = channelPrivateKeys(internalKeyPath(channelKeyPath, 2L))

  private def delayedPaymentSecret(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = channelPrivateKeys(internalKeyPath(channelKeyPath, 3L))

  private def htlcSecret(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = channelPrivateKeys(internalKeyPath(channelKeyPath, 4L))

  private def shaSeed(channelKeyPath: DeterministicWallet.KeyPath): ByteVector32 = {
    val key = channelPrivateKeys(internalKeyPath(channelKeyPath, 5L))
    Crypto.sha256(key.privateKey.value :+ 1.toByte)
  }

  /**
   * Create a BIP32 path from a public key. This path will be used to derive channel keys.
   * Having channel keys derived from the funding public keys makes it very easy to retrieve your funds when've you've lost your data:
   * - connect to your peer and use DLP to get them to publish their remote commit tx
   * - retrieve the commit tx from the bitcoin network, extract your funding pubkey from its witness data
   * - recompute your channel keys and spend your output
   *
   * @return a BIP32 path
   */
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

  def fundingPublicKey(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeys(internalKeyPath(channelKeyPath, 0L))

  def revocationPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeys(internalKeyPath(channelKeyPath, 1L))

  def paymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeys(internalKeyPath(channelKeyPath, 2L))

  def delayedPaymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeys(internalKeyPath(channelKeyPath, 3L))

  def htlcPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = channelPublicKeys(internalKeyPath(channelKeyPath, 4L))

  def commitmentSecret(channelKeyPath: DeterministicWallet.KeyPath, index: Long): PrivateKey = Generators.perCommitSecret(shaSeed(channelKeyPath), index)

  def commitmentPoint(channelKeyPath: DeterministicWallet.KeyPath, index: Long): PublicKey = Generators.perCommitPoint(shaSeed(channelKeyPath), index)

  /**
   * @param tx               input transaction
   * @param publicKey        extended public key
   * @param txOwner          owner of the transaction (local/remote)
   * @param commitmentFormat format of the commitment tx
   * @return a signature generated with the private key that matches the input
   *         extended public key
   */
  def sign(tx: Transactions.TransactionWithInputInfo, publicKey: ExtendedPublicKey, txOwner: Transactions.TxOwner, commitmentFormat: Transactions.CommitmentFormat): ByteVector64 =
    Transactions.sign(tx, channelPrivateKeys(publicKey.path).privateKey, txOwner, commitmentFormat)

  /**
   * This method is used to spend funds sent to htlc keys/delayed keys
   *
   * @param tx               input transaction
   * @param publicKey        extended public key
   * @param remotePoint      remote point
   * @param txOwner          owner of the transaction (local/remote)
   * @param commitmentFormat format of the commitment tx
   * @return a signature generated with a private key generated from the input keys's matching
   *         private key and the remote point.
   */
  def sign(tx: Transactions.TransactionWithInputInfo, publicKey: ExtendedPublicKey, remotePoint: PublicKey, txOwner: Transactions.TxOwner, commitmentFormat: Transactions.CommitmentFormat): ByteVector64 =
    Transactions.sign(tx, Generators.derivePrivKey(channelPrivateKeys(publicKey.path).privateKey, remotePoint), txOwner, commitmentFormat)

  /**
   * Ths method is used to spend revoked transactions, with the corresponding revocation key
   *
   * @param tx               input transaction
   * @param publicKey        extended public key
   * @param remoteSecret     remote secret
   * @param txOwner          owner of the transaction (local/remote)
   * @param commitmentFormat format of the commitment tx
   * @return a signature generated with a private key generated from the input keys's matching
   *         private key and the remote secret.
   */
  def sign(tx: Transactions.TransactionWithInputInfo, publicKey: ExtendedPublicKey, remoteSecret: PrivateKey, txOwner: Transactions.TxOwner, commitmentFormat: Transactions.CommitmentFormat): ByteVector64 =
    Transactions.sign(tx, Generators.revocationPrivKey(channelPrivateKeys(publicKey.path).privateKey, remoteSecret), txOwner, commitmentFormat)

  def signChannelAnnouncement(witness: ByteVector, fundingKeyPath: KeyPath): ByteVector64 =
    Announcements.signChannelAnnouncement(witness, channelPrivateKeys(fundingKeyPath).privateKey)
}

case class PaymentRequestExt(pr: PaymentRequest, raw: String) {
  def paymentHashStr: String = pr.paymentHash.toHex
}

case class SwapInStateExt(state: SwapInState, nodeId: PublicKey)

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

trait PaymentDBUpdater {
  def replaceOutgoingPayment(nodeId: PublicKey, prex: PaymentRequestExt, desc: PaymentDescription, action: Option[PaymentAction], finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Tools.Fiat2Btc): Unit
  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription, balanceSnap: MilliSatoshi, fiatRateSnap: Tools.Fiat2Btc): Unit
  // These MUST be the only two methods capable of updating payment state to SUCCEEDED
  def updOkOutgoing(upd: UpdateFulfillHtlc, fee: MilliSatoshi): Unit
  def updStatusIncoming(add: UpdateAddHtlc, status: String): Unit
}

trait ChainLink {
  var listeners: Set[ChainLinkListener] = Set.empty
  def addAndMaybeInform(listener: ChainLinkListener): Unit = {
    if (chainTipCanBeTrusted) listener.onTrustedChainTipKnown
    listeners += listener
  }

  def chainTipCanBeTrusted: Boolean
  def currentChainTip: Int
  def start: Unit
  def stop: Unit
}

trait ChainLinkListener {
  def onTrustedChainTipKnown: Unit = Tools.none
  def onCompleteChainDisconnect: Unit = Tools.none
}

trait ChannelBag {
  def all: List[ChannelData]
  def delete(chanId: ByteVector32): Unit
  def put(chanId: ByteVector32, data: ChannelData): ChannelData
}