package fr.acinq.eclair.wire

import fr.acinq.eclair._
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

object LightningMessageCodecs {
  val featuresCodec: Codec[Features[FeatureScope]] =
    varsizebinarydata.xmap[Features[FeatureScope]](
      { bytes => Features(bytes) },
      { features => features.toByteVector }
    )

  val initFeaturesCodec: Codec[Features[InitFeature]] =
    featuresCodec.xmap[Features[InitFeature]](_.initFeatures(), _.unscoped())

  /** For historical reasons, features are divided into two feature bitmasks. We
    * only send from the second one, but we allow receiving in both.
    */
  val combinedFeaturesCodec: Codec[Features[InitFeature]] =
    (("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata))
      .as[(ByteVector, ByteVector)]
      .xmap[Features[InitFeature]](
        { case (gf, lf) =>
          val length = gf.length.max(lf.length)
          Features(gf.padLeft(length) | lf.padLeft(length)).initFeatures()
        },
        { features => (ByteVector.empty, features.toByteVector) }
      )

  val initCodec =
    (("features" | combinedFeaturesCodec) :: ("tlvStream" | InitTlvCodecs.initTlvCodec))
      .as[Init]

  val failCodec =
    (("channelId" | bytes32) :: ("data" | varsizebinarydata)).as[Fail]

  val warningCodec =
    (("channelId" | bytes32) :: ("data" | varsizebinarydata)).as[Warning]

  val pingCodec =
    (("pongLength" | uint16) :: ("data" | varsizebinarydata)).as[Ping]

  val pongCodec = ("data" | varsizebinarydata).as[Pong]

  val channelReestablishCodec = {
    ("channelId" | bytes32) ::
      ("nextLocalCommitmentNumber" | uint64overflow) ::
      ("nextRemoteRevocationNumber" | uint64overflow) ::
      ("yourLastPerCommitmentSecret" | privateKey) ::
      ("myCurrentPerCommitmentPoint" | publicKey)
  }.as[ChannelReestablish]

  val openChannelCodec = {
    ("chainHash" | bytes32) ::
      ("temporaryChannelId" | bytes32) ::
      ("fundingSatoshis" | satoshi) ::
      ("pushMsat" | millisatoshi) ::
      ("dustLimitSatoshis" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | satoshi) ::
      ("htlcMinimumMsat" | millisatoshi) ::
      ("feeratePerKw" | feeratePerKw) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("firstPerCommitmentPoint" | publicKey) ::
      ("channelFlags" | byte) ::
      ("tlvStream" | OpenChannelTlv.openTlvCodec)
  }.as[OpenChannel]

  val acceptChannelCodec = {
    ("temporaryChannelId" | bytes32) ::
      ("dustLimitSatoshis" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | satoshi) ::
      ("htlcMinimumMsat" | millisatoshi) ::
      ("minimumDepth" | uint32) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("firstPerCommitmentPoint" | publicKey) ::
      ("tlvStream" | AcceptChannelTlv.acceptTlvCodec)
  }.as[AcceptChannel]

  val fundingCreatedCodec = {
    ("temporaryChannelId" | bytes32) ::
      ("fundingTxid" | bytes32) ::
      ("fundingOutputIndex" | uint16) ::
      ("signature" | bytes64)
  }.as[FundingCreated]

  val fundingSignedCodec = {
    ("channelId" | bytes32) ::
      ("signature" | bytes64)
  }.as[FundingSigned]

  val fundingLockedCodec = {
    ("channelId" | bytes32) ::
      ("nextPerCommitmentPoint" | publicKey)
  }.as[FundingLocked]

  val shutdownCodec = {
    ("channelId" | bytes32) ::
      ("scriptPubKey" | varsizebinarydata)
  }.as[Shutdown]

  val closingSignedCodec = {
    ("channelId" | bytes32) ::
      ("feeSatoshis" | satoshi) ::
      ("signature" | bytes64)
  }.as[ClosingSigned]

  val updateAddHtlcCodec = {
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("amountMsat" | millisatoshi) ::
      ("paymentHash" | bytes32) ::
      ("expiry" | cltvExpiry) ::
      ("onionRoutingPacket" | PaymentOnionCodecs.paymentOnionPacketCodec) ::
      ("tlvStream" | PaymentTagTlv.codec)
  }.as[UpdateAddHtlc]

  val updateFulfillHtlcCodec = {
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("paymentPreimage" | bytes32)
  }.as[UpdateFulfillHtlc]

  val updateFailHtlcCodec = {
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("reason" | varsizebinarydata)
  }.as[UpdateFailHtlc]

  val updateFailMalformedHtlcCodec = {
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("onionHash" | bytes32) ::
      ("failureCode" | uint16)
  }.as[UpdateFailMalformedHtlc]

  val commitSigCodec = {
    ("channelId" | bytes32) ::
      ("signature" | bytes64) ::
      ("htlcSignatures" | listofsignatures)
  }.as[CommitSig]

  val revokeAndAckCodec = {
    ("channelId" | bytes32) ::
      ("perCommitmentSecret" | privateKey) ::
      ("nextPerCommitmentPoint" | publicKey)
  }.as[RevokeAndAck]

  val updateFeeCodec =
    (("channelId" | bytes32) :: ("feeratePerKw" | feeratePerKw)).as[UpdateFee]

  val announcementSignaturesCodec = {
    ("channelId" | bytes32) ::
      ("shortChannelId" | int64) ::
      ("nodeSignature" | bytes64) ::
      ("bitcoinSignature" | bytes64)
  }.as[AnnouncementSignatures]

  val channelAnnouncementWitnessCodec =
    ("features" | featuresCodec) ::
      ("chainHash" | bytes32) ::
      ("shortChannelId" | int64) ::
      ("nodeId1" | publicKey) ::
      ("nodeId2" | publicKey) ::
      ("bitcoinKey1" | publicKey) ::
      ("bitcoinKey2" | publicKey) ::
      ("unknownFields" | bytes)

  val channelAnnouncementCodec = {
    ("nodeSignature1" | bytes64) ::
      ("nodeSignature2" | bytes64) ::
      ("bitcoinSignature1" | bytes64) ::
      ("bitcoinSignature2" | bytes64) ::
      channelAnnouncementWitnessCodec
  }.as[ChannelAnnouncement]

  val nodeAnnouncementWitnessCodec =
    ("features" | featuresCodec) ::
      ("timestamp" | uint32) ::
      ("nodeId" | publicKey) ::
      ("rgbColor" | rgb) ::
      ("alias" | zeropaddedstring(32)) ::
      ("addresses" | listofnodeaddresses) ::
      ("unknownFields" | bytes)

  val nodeAnnouncementCodec =
    (("signature" | bytes64) :: nodeAnnouncementWitnessCodec)
      .as[NodeAnnouncement]

  val channelUpdateChecksumCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | int64) ::
      (("messageFlags" | byte) >>:~ { messageFlags =>
        ("channelFlags" | byte) ::
          ("cltvExpiryDelta" | cltvExpiryDelta) ::
          ("htlcMinimumMsat" | millisatoshi) ::
          ("feeBaseMsat" | millisatoshi32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional(
            (messageFlags & 1) != 0,
            millisatoshi
          ))
      })

  val channelUpdateWitnessCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | int64) ::
      ("timestamp" | uint32) ::
      (("messageFlags" | byte) >>:~ { messageFlags =>
        ("channelFlags" | byte) ::
          ("cltvExpiryDelta" | cltvExpiryDelta) ::
          ("htlcMinimumMsat" | millisatoshi) ::
          ("feeBaseMsat" | millisatoshi32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional(
            (messageFlags & 1) != 0,
            millisatoshi
          )) ::
          ("unknownFields" | bytes)
      })

  val channelUpdateCodec =
    (("signature" | bytes64) :: channelUpdateWitnessCodec).as[ChannelUpdate]

  val encodedShortChannelIdsCodec: Codec[EncodedShortChannelIds] =
    discriminated[EncodedShortChannelIds]
      .by(byte)
      .\(0) {
        case a @ EncodedShortChannelIds(_, Nil) =>
          a // empty list is always encoded with encoding type 'uncompressed' for compatibility with other implementations
        case a @ EncodedShortChannelIds(EncodingType.UNCOMPRESSED, _) => a
      }(
        (provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(int64))
          .as[EncodedShortChannelIds]
      )
      .\(1) {
        case a @ EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, _) => a
      }(
        (provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(
          list(int64)
        )).as[EncodedShortChannelIds]
      )

  val queryShortChannelIdsCodec = {
    ("chainHash" | bytes32) ::
      ("shortChannelIds" | variableSizeBytes(
        uint16,
        encodedShortChannelIdsCodec
      )) ::
      ("tlvStream" | QueryShortChannelIdsTlv.codec)
  }.as[QueryShortChannelIds]

  val replyShortChannelIdsEndCodec = {
    ("chainHash" | bytes32) ::
      ("complete" | byte)
  }.as[ReplyShortChannelIdsEnd]

  val queryChannelRangeCodec = {
    ("chainHash" | bytes32) ::
      ("firstBlockNum" | uint32) ::
      ("numberOfBlocks" | uint32) ::
      ("tlvStream" | QueryChannelRangeTlv.codec)
  }.as[QueryChannelRange]

  val replyChannelRangeCodec = {
    ("chainHash" | bytes32) ::
      ("firstBlockNum" | uint32) ::
      ("numberOfBlocks" | uint32) ::
      ("syncComplete" | byte) ::
      ("shortChannelIds" | variableSizeBytes(
        uint16,
        encodedShortChannelIdsCodec
      )) ::
      ("tlvStream" | ReplyChannelRangeTlv.codec)
  }.as[ReplyChannelRange]

  val gossipTimestampFilterCodec = {
    ("chainHash" | bytes32) ::
      ("firstTimestamp" | uint32) ::
      ("timestampRange" | uint32)
  }.as[GossipTimestampFilter]

  val unknownMessageCodec = {
    ("tag" | uint16) ::
      ("message" | varsizebinarydata)
  }.as[UnknownMessage]

  // HOSTED CHANNELS

  val invokeHostedChannelCodec = {
    (bytes32 withContext "chainHash") ::
      (varsizebinarydata withContext "refundScriptPubKey") ::
      (varsizebinarydata withContext "secret")
  }.as[InvokeHostedChannel]

  lazy val initHostedChannelCodec = {
    (uint64 withContext "maxHtlcValueInFlightMsat") ::
      (millisatoshi withContext "htlcMinimumMsat") ::
      (uint16 withContext "maxAcceptedHtlcs") ::
      (millisatoshi withContext "channelCapacityMsat") ::
      (millisatoshi withContext "initialClientBalanceMsat") ::
      (listOfN(uint16, uint16) withContext "features")
  }.as[InitHostedChannel]

  lazy val hostedChannelBrandingCodec = {
    (rgb withContext "rgbColor") ::
      (optional(bool8, varsizebinarydata) withContext "pngIcon") ::
      (variableSizeBytes(uint16, utf8) withContext "contactInfo")
  }.as[HostedChannelBranding]

  lazy val lastCrossSignedStateCodec = {
    (bool8 withContext "isHost") ::
      (varsizebinarydata withContext "refundScriptPubKey") ::
      (lengthDelimited(
        initHostedChannelCodec
      ) withContext "initHostedChannel") ::
      (uint32 withContext "blockDay") ::
      (millisatoshi withContext "localBalanceMsat") ::
      (millisatoshi withContext "remoteBalanceMsat") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (listOfN(
        uint16,
        lengthDelimited(LightningMessageCodecs.updateAddHtlcCodec)
      ) withContext "incomingHtlcs") ::
      (listOfN(
        uint16,
        lengthDelimited(LightningMessageCodecs.updateAddHtlcCodec)
      ) withContext "outgoingHtlcs") ::
      (bytes64 withContext "remoteSigOfLocal") ::
      (bytes64 withContext "localSigOfRemote")
  }.as[LastCrossSignedState]

  val stateUpdateCodec = {
    (uint32 withContext "blockDay") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (bytes64 withContext "localSigOfRemoteLCSS")
  }.as[StateUpdate]

  val stateOverrideCodec = {
    (uint32 withContext "blockDay") ::
      (millisatoshi withContext "localBalanceMsat") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (bytes64 withContext "localSigOfRemoteLCSS")
  }.as[StateOverride]

  lazy val announcementSignatureCodec = {
    (bytes64 withContext "nodeSignature") ::
      (bool8 withContext "wantsReply")
  }.as[AnnouncementSignature]

  val resizeChannelCodec = {
    (satoshi withContext "newCapacity") ::
      (bytes64 withContext "clientSig")
  }.as[ResizeChannel]

  val askBrandingInfoCodec =
    (bytes32 withContext "chainHash").as[AskBrandingInfo]

  val queryPublicHostedChannelsCodec =
    (bytes32 withContext "chainHash").as[QueryPublicHostedChannels]

  val replyPublicHostedChannelsEndCodec =
    (bytes32 withContext "chainHash").as[ReplyPublicHostedChannelsEnd]

  lazy val queryPreimagesCodec =
    (listOfN(uint16, bytes32) withContext "hashes").as[QueryPreimages]

  lazy val replyPreimagesCodec =
    (listOfN(uint16, bytes32) withContext "preimages").as[ReplyPreimages]

  final val HC_INVOKE_HOSTED_CHANNEL_TAG = 65535
  final val HC_INIT_HOSTED_CHANNEL_TAG = 65533
  final val HC_LAST_CROSS_SIGNED_STATE_TAG = 65531
  final val HC_STATE_UPDATE_TAG = 65529
  final val HC_STATE_OVERRIDE_TAG = 65527
  final val HC_HOSTED_CHANNEL_BRANDING_TAG = 65525
  final val HC_ANNOUNCEMENT_SIGNATURE_TAG = 65523
  final val HC_RESIZE_CHANNEL_TAG = 65521
  final val HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG = 65519
  final val HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG = 65517
  final val HC_QUERY_PREIMAGES_TAG = 65515
  final val HC_REPLY_PREIMAGES_TAG = 65513
  final val HC_ASK_BRANDING_INFO = 65511
  final val PHC_ANNOUNCE_GOSSIP_TAG = 64513
  final val PHC_ANNOUNCE_SYNC_TAG = 64511
  final val PHC_UPDATE_GOSSIP_TAG = 64509
  final val PHC_UPDATE_SYNC_TAG = 64507
  final val HC_UPDATE_ADD_HTLC_TAG = 63505
  final val HC_UPDATE_FULFILL_HTLC_TAG = 63503
  final val HC_UPDATE_FAIL_HTLC_TAG = 63501
  final val HC_UPDATE_FAIL_MALFORMED_HTLC_TAG = 63499
  final val HC_ERROR_TAG = 63497

  // TRAMPOLINE STATUS

  val trampolineOnCodec = {
    (millisatoshi withContext "minMsat") ::
      (millisatoshi withContext "maxMsat") ::
      (uint32 withContext "feeProportionalMillionths") ::
      (double withContext "exponent") ::
      (double withContext "logExponent") ::
      (cltvExpiryDelta withContext "cltvExpiryDelta")
  }.as[TrampolineOn]

  val nodeIdTrampolineParamsCodec = {
    (publicKey withContext "nodeId") ::
      (trampolineOnCodec withContext "trampolineOn")
  }.as[NodeIdTrampolineParams]

  private val trampolineRouteCodec = {
    val innerList = listOfN(uint16, nodeIdTrampolineParamsCodec)
    listOfN(valueCodec = innerList, countCodec = uint16)
  }

  val trampolineStatusInitCodec = {
    (trampolineRouteCodec withContext "routes") ::
      (trampolineOnCodec withContext "peerParams")
  }.as[TrampolineStatusInit]

  val trampolineStatusUpdateCodec = {
    (trampolineRouteCodec withContext "newRoutes") ::
      (mapCodec(publicKey, trampolineOnCodec) withContext "updatedParams") ::
      (optional(bool, trampolineOnCodec) withContext "updatedPeerParams") ::
      (setCodec(publicKey) withContext "removed")
  }.as[TrampolineStatusUpdate]

  final val TRAMPOLINE_STATUS_INIT_TAG = 44789
  final val TRAMPOLINE_STATUS_UPDATE_TAG = 44791
  final val TRAMPOLINE_STATUS_UNDESIRED_TAG = 44793

  val lightningMessageCodec: DiscriminatorCodec[LightningMessage, Int] =
    discriminated[LightningMessage]
      .by(uint16)
      .\(1) { case v: Warning => v }(warningCodec)
      .\(16) { case v: Init => v }(initCodec)
      .\(17) { case v: Fail => v }(failCodec)
      .\(18) { case v: Ping => v }(pingCodec)
      .\(19) { case v: Pong => v }(pongCodec)
      .\(32) { case v: OpenChannel => v }(openChannelCodec)
      .\(33) { case v: AcceptChannel => v }(acceptChannelCodec)
      .\(34) { case v: FundingCreated => v }(fundingCreatedCodec)
      .\(35) { case v: FundingSigned => v }(fundingSignedCodec)
      .\(36) { case v: FundingLocked => v }(fundingLockedCodec)
      .\(38) { case v: Shutdown => v }(shutdownCodec)
      .\(39) { case v: ClosingSigned => v }(closingSignedCodec)
      .\(128) { case v: UpdateAddHtlc => v }(updateAddHtlcCodec)
      .\(130) { case v: UpdateFulfillHtlc => v }(updateFulfillHtlcCodec)
      .\(131) { case v: UpdateFailHtlc => v }(updateFailHtlcCodec)
      .\(132) { case v: CommitSig => v }(commitSigCodec)
      .\(133) { case v: RevokeAndAck => v }(revokeAndAckCodec)
      .\(134) { case v: UpdateFee => v }(updateFeeCodec)
      .\(135) { case v: UpdateFailMalformedHtlc => v }(
        updateFailMalformedHtlcCodec
      )
      .\(136) { case v: ChannelReestablish => v }(channelReestablishCodec)
      .\(256) { case v: ChannelAnnouncement => v }(channelAnnouncementCodec)
      .\(257) { case v: NodeAnnouncement => v }(nodeAnnouncementCodec)
      .\(258) { case v: ChannelUpdate => v }(channelUpdateCodec)
      .\(259) { case v: AnnouncementSignatures => v }(
        announcementSignaturesCodec
      )
      .\(261) { case v: QueryShortChannelIds => v }(queryShortChannelIdsCodec)
      .\(262) { case v: ReplyShortChannelIdsEnd => v }(
        replyShortChannelIdsEndCodec
      )
      .\(263) { case v: QueryChannelRange => v }(queryChannelRangeCodec)
      .\(264) { case v: ReplyChannelRange => v }(replyChannelRangeCodec)
      .\(265) { case v: GossipTimestampFilter => v }(gossipTimestampFilterCodec)

  //

  val lightningMessageCodecWithFallback: Codec[LightningMessage] =
    discriminatorWithDefault(lightningMessageCodec, unknownMessageCodec.upcast)

  // EXTENDED MESSAGE UTILS
  def decode(msg: UnknownMessage): LightningMessage = {
    val codec = msg.tag match {
      case HC_HOSTED_CHANNEL_BRANDING_TAG => hostedChannelBrandingCodec
      case HC_LAST_CROSS_SIGNED_STATE_TAG => lastCrossSignedStateCodec
      case HC_INVOKE_HOSTED_CHANNEL_TAG   => invokeHostedChannelCodec
      case HC_INIT_HOSTED_CHANNEL_TAG     => initHostedChannelCodec
      case HC_STATE_OVERRIDE_TAG          => stateOverrideCodec
      case HC_RESIZE_CHANNEL_TAG          => resizeChannelCodec
      case HC_STATE_UPDATE_TAG            => stateUpdateCodec

      case HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG => queryPublicHostedChannelsCodec
      case HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG =>
        replyPublicHostedChannelsEndCodec
      case HC_QUERY_PREIMAGES_TAG => queryPreimagesCodec
      case HC_REPLY_PREIMAGES_TAG => replyPreimagesCodec
      case HC_ASK_BRANDING_INFO   => askBrandingInfoCodec

      case PHC_ANNOUNCE_GOSSIP_TAG => channelAnnouncementCodec
      case PHC_ANNOUNCE_SYNC_TAG   => channelAnnouncementCodec
      case PHC_UPDATE_GOSSIP_TAG   => channelUpdateCodec
      case PHC_UPDATE_SYNC_TAG     => channelUpdateCodec

      case HC_UPDATE_FAIL_MALFORMED_HTLC_TAG => updateFailMalformedHtlcCodec
      case HC_UPDATE_FULFILL_HTLC_TAG        => updateFulfillHtlcCodec
      case HC_UPDATE_FAIL_HTLC_TAG           => updateFailHtlcCodec
      case HC_UPDATE_ADD_HTLC_TAG            => updateAddHtlcCodec
      case HC_ERROR_TAG                      => failCodec

      case TRAMPOLINE_STATUS_INIT_TAG      => trampolineStatusInitCodec
      case TRAMPOLINE_STATUS_UPDATE_TAG    => trampolineStatusUpdateCodec
      case TRAMPOLINE_STATUS_UNDESIRED_TAG => provide(TrampolineUndesired)

      case _ => throw new RuntimeException
    }

    codec.decode(msg.data.toBitVector).require.value
  }

  // Extended messages need to be wrapped in UnknownMessage
  def prepare(msg: LightningMessage): LightningMessage = msg match {
    case msg: HostedChannelBranding =>
      UnknownMessage(
        HC_HOSTED_CHANNEL_BRANDING_TAG,
        hostedChannelBrandingCodec.encode(msg).require.toByteVector
      )
    case msg: LastCrossSignedState =>
      UnknownMessage(
        HC_LAST_CROSS_SIGNED_STATE_TAG,
        lastCrossSignedStateCodec.encode(msg).require.toByteVector
      )
    case msg: InvokeHostedChannel =>
      UnknownMessage(
        HC_INVOKE_HOSTED_CHANNEL_TAG,
        invokeHostedChannelCodec.encode(msg).require.toByteVector
      )
    case msg: InitHostedChannel =>
      UnknownMessage(
        HC_INIT_HOSTED_CHANNEL_TAG,
        initHostedChannelCodec.encode(msg).require.toByteVector
      )
    case msg: StateOverride =>
      UnknownMessage(
        HC_STATE_OVERRIDE_TAG,
        stateOverrideCodec.encode(msg).require.toByteVector
      )
    case msg: ResizeChannel =>
      UnknownMessage(
        HC_RESIZE_CHANNEL_TAG,
        resizeChannelCodec.encode(msg).require.toByteVector
      )
    case msg: StateUpdate =>
      UnknownMessage(
        HC_STATE_UPDATE_TAG,
        stateUpdateCodec.encode(msg).require.toByteVector
      )

    case msg: QueryPublicHostedChannels =>
      UnknownMessage(
        HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG,
        queryPublicHostedChannelsCodec.encode(msg).require.toByteVector
      )
    case msg: ReplyPublicHostedChannelsEnd =>
      UnknownMessage(
        HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG,
        replyPublicHostedChannelsEndCodec.encode(msg).require.toByteVector
      )
    case msg: QueryPreimages =>
      UnknownMessage(
        HC_QUERY_PREIMAGES_TAG,
        queryPreimagesCodec.encode(msg).require.toByteVector
      )
    case msg: ReplyPreimages =>
      UnknownMessage(
        HC_REPLY_PREIMAGES_TAG,
        replyPreimagesCodec.encode(msg).require.toByteVector
      )
    case msg: AskBrandingInfo =>
      UnknownMessage(
        HC_ASK_BRANDING_INFO,
        askBrandingInfoCodec.encode(msg).require.toByteVector
      )

    case msg: TrampolineStatusInit =>
      UnknownMessage(
        TRAMPOLINE_STATUS_INIT_TAG,
        trampolineStatusInitCodec.encode(msg).require.toByteVector
      )
    case msg: TrampolineStatusUpdate =>
      UnknownMessage(
        TRAMPOLINE_STATUS_UPDATE_TAG,
        trampolineStatusUpdateCodec.encode(msg).require.toByteVector
      )
    case TrampolineUndesired =>
      UnknownMessage(
        TRAMPOLINE_STATUS_UNDESIRED_TAG,
        provide(TrampolineUndesired)
          .encode(TrampolineUndesired)
          .require
          .toByteVector
      )
    case _ => msg
  }

  // HC uses the following protocol-defined messages, but they still need to be wrapped in UnknownMessage
  def prepareNormal(msg: LightningMessage): LightningMessage = msg match {
    case msg: Fail =>
      UnknownMessage(
        HC_ERROR_TAG,
        LightningMessageCodecs.failCodec.encode(msg).require.toByteVector
      )
    case msg: ChannelUpdate =>
      UnknownMessage(
        PHC_UPDATE_SYNC_TAG,
        LightningMessageCodecs.channelUpdateCodec
          .encode(msg)
          .require
          .toByteVector
      )
    case msg: UpdateAddHtlc =>
      UnknownMessage(
        HC_UPDATE_ADD_HTLC_TAG,
        LightningMessageCodecs.updateAddHtlcCodec
          .encode(msg)
          .require
          .toByteVector
      )
    case msg: UpdateFailHtlc =>
      UnknownMessage(
        HC_UPDATE_FAIL_HTLC_TAG,
        LightningMessageCodecs.updateFailHtlcCodec
          .encode(msg)
          .require
          .toByteVector
      )
    case msg: UpdateFulfillHtlc =>
      UnknownMessage(
        HC_UPDATE_FULFILL_HTLC_TAG,
        LightningMessageCodecs.updateFulfillHtlcCodec
          .encode(msg)
          .require
          .toByteVector
      )
    case msg: UpdateFailMalformedHtlc =>
      UnknownMessage(
        HC_UPDATE_FAIL_MALFORMED_HTLC_TAG,
        LightningMessageCodecs.updateFailMalformedHtlcCodec
          .encode(msg)
          .require
          .toByteVector
      )
    case _ => msg
  }
}
