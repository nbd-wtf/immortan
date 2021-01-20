package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import fr.acinq.eclair.Features._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, ByteVector32, Satoshi}
import fr.acinq.eclair.router.Router.{PublicChannel, RouterConf}
import fr.acinq.eclair.{ActivatedFeature, CltvExpiryDelta, FeatureSupport, Features}
import fr.acinq.eclair.router.ChannelUpdateExt
import fr.acinq.eclair.payment.PaymentRequest
import immortan.SyncMaster.ShortChanIdSet
import immortan.crypto.Noise.KeyPair


object LNParams {
  val blocksPerDay: Int = 144 // On average we can expect this many blocks per day
  val cltvRejectThreshold: Int = 144 // Reject incoming payment if CLTV expiry is closer than this to current chain tip when HTLC arrives
  val incomingPaymentCltvExpiry: Int = 144 + 72 // Ask payer to set final CLTV expiry to payer's current chain tip + this many blocks
  val chainHash: ByteVector32 = Block.LivenetGenesisBlock.hash
  val minHostedOnChainRefund = Satoshi(1000000L)
  val minHostedLiabilityBlockdays = 365
  val minPayment = MilliSatoshi(5000L)

  var routerConf =
    RouterConf(searchMaxFeeBase = MilliSatoshi(25000L),
      searchMaxFeePct = 0.01, firstPassMaxCltv = CltvExpiryDelta(1008),
      firstPassMaxRouteLength = 6, mppMinPartAmount = MilliSatoshi(30000000L),
      maxRemoteAttempts = 12, maxChannelFailures = 12, maxStrangeNodeFailures = 12)

  var format: StorageFormat = _
  var channelMaster: ChannelMaster = _

  lazy val (syncInit, phcSyncInit, hcInit) = {
    val networks: InitTlv = InitTlv.Networks(chainHash :: Nil)
    val tlvStream: TlvStream[InitTlv] = TlvStream(networks)

    // Mimic phoenix
    val syncFeatures: Set[ActivatedFeature] = Set (
      ActivatedFeature(OptionDataLossProtect, FeatureSupport.Mandatory),
      ActivatedFeature(BasicMultiPartPayment, FeatureSupport.Optional),
      ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional),
      ActivatedFeature(PaymentSecret, FeatureSupport.Optional),
      ActivatedFeature(Wumbo, FeatureSupport.Optional)
    )

    val phcSyncFeatures: Set[ActivatedFeature] = Set (
      ActivatedFeature(HostedChannels, FeatureSupport.Mandatory)
    )

    val hcFeatures: Set[ActivatedFeature] = Set (
      ActivatedFeature(ChannelRangeQueriesExtended, FeatureSupport.Mandatory),
      ActivatedFeature(BasicMultiPartPayment, FeatureSupport.Mandatory),
      ActivatedFeature(ChannelRangeQueries, FeatureSupport.Mandatory),
      ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory),
      ActivatedFeature(HostedChannels, FeatureSupport.Mandatory),
      ActivatedFeature(ChainSwap, FeatureSupport.Optional)
    )

    val sync = Init(Features(syncFeatures), tlvStream)
    val phcSync = Init(Features(phcSyncFeatures), tlvStream)
    val hc = Init(Features(hcFeatures), tlvStream)
    (sync, phcSync, hc)
  }
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
  lazy val nodeSpecificPrivKey: PrivateKey = LNParams.format.keys.ourFakeNodeIdKey(na.nodeId)
  lazy val nodeSpecificPubKey: PublicKey = nodeSpecificPrivKey.publicKey

  lazy val nodeSpecificPkap: PublicKeyAndPair = PublicKeyAndPair(keyPair = KeyPair(nodeSpecificPubKey.value, nodeSpecificPrivKey.value), them = na.nodeId)
  lazy val nodeSpecificHostedChanId: ByteVector32 = hostedChanId(pubkey1 = nodeSpecificPubKey.value, pubkey2 = na.nodeId.value)
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
  def replaceOutgoingPayment(nodeId: PublicKey, prex: PaymentRequestExt, description: PaymentDescription, action: Option[PaymentAction], finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc): Unit
  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc): Unit
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
  def onTrustedChainTipKnown: Unit = none
  def onCompleteChainDisconnect: Unit = none
  val isTransferrable: Boolean = false
}

trait ChannelBag {
  def all: List[HostedCommits]
  def delete(chanId: ByteVector32): Unit
  def put(chanId: ByteVector32, data: HostedCommits): HostedCommits
}