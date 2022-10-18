package immortan.utils

import scala.util.{Failure, Try}
import scoin.Crypto.{PublicKey, randomBytes}
import scoin._
import scoin.ln._

import immortan.channel.{IncomingResolution, ReasonableLocal}
import immortan.router.ChannelUpdateExt
import immortan.router.Graph.GraphStructure.GraphEdge
import immortan.router.Router.{ChannelDesc, ChannelHop, NodeHop}
import immortan.ChannelMaster.{OutgoingAdds, ReasonableResolutions}
import immortan._
import immortan.utils.GraphUtils._

object PaymentUtils {
  def createFinalAdd(
      partAmount: MilliSatoshi,
      totalAmount: MilliSatoshi,
      paymentHash: ByteVector32,
      paymentSecret: ByteVector32,
      from: PublicKey,
      to: PublicKey,
      cltvDelta: Int,
      tlvs: Seq[GenericTlv] = Nil
  ): UpdateAddHtlc = {
    val update = makeUpdate(
      ShortChannelId("100x100x100").toLong,
      from,
      to,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(cltvDelta),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(50000000)
    )
    val finalHop = ChannelHop(
      GraphEdge(
        ChannelDesc(update.shortChannelId, from, to),
        updExt = ChannelUpdateExt fromUpdate update
      )
    )

    val finalPayload = PaymentOnion.createMultiPartPayload(
      partAmount,
      totalAmount,
      update.cltvExpiryDelta.toCltvExpiry(0L),
      paymentSecret,
      None,
      userCustomTlvs = tlvs
    )
    val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket
      .buildPaymentPacket(
        paymentHash,
        finalHop :: Nil,
        finalPayload
      )
      .toOption
      .get

    UpdateAddHtlc(
      randomBytes32(),
      System.currentTimeMillis +
        randomBytes(2).toLong() % 1000,
      firstAmount,
      paymentHash,
      firstExpiry,
      onion.packet
    )
  }

  def recordIncomingPaymentToFakeNodeId(
      amount: Option[MilliSatoshi],
      preimage: ByteVector32,
      secret: ByteVector32,
      payBag: PaymentBag,
      remoteInfo: RemoteNodeInfo
  ): Bolt11Invoice = {
    val invoice = Bolt11Invoice(
      Block.TestnetGenesisBlock.hash,
      amount,
      Crypto.sha256(preimage),
      remoteInfo.nodeSpecificPrivKey,
      Left("Invoice"),
      CltvExpiryDelta(18),
      paymentSecret = secret
    )
    val prExt = PaymentRequestExt(
      uri = Failure(new RuntimeException),
      invoice,
      invoice.toString
    )
    val desc = PaymentDescription(None, None, None, "Invoice", None)
    payBag.replaceIncomingPayment(
      prExt,
      preimage,
      desc,
      balanceSnap = MilliSatoshi(1000L),
      fiatRateSnap = Map("USD" -> 12d)
    )
    invoice
  }

  def makeRemoteAddToFakeNodeId(
      partAmount: MilliSatoshi,
      totalAmount: MilliSatoshi,
      paymentHash: ByteVector32,
      paymentSecret: ByteVector32,
      remoteInfo: RemoteNodeInfo,
      cm: ChannelMaster,
      cltvDelta: Int = LNParams.cltvRejectThreshold,
      tlvs: Seq[GenericTlv] = Nil
  ): ReasonableLocal = {
    val addFromRemote1 = createFinalAdd(
      partAmount,
      totalAmount,
      paymentHash,
      paymentSecret,
      from = remoteInfo.nodeId,
      to = remoteInfo.nodeSpecificPubKey,
      cltvDelta,
      tlvs
    )
    cm.initResolve(
      UpdateAddHtlcExt(theirAdd = addFromRemote1, remoteInfo = remoteInfo)
    ).asInstanceOf[ReasonableLocal]
  }

  def makeInFlightPayments(
      out: OutgoingAdds,
      in: ReasonableResolutions
  ): InFlightPayments =
    InFlightPayments(out.groupBy(_.fullTag), in.groupBy(_.fullTag))

  //

  def createInnerLegacyTrampoline(
      pr: Bolt11Invoice,
      from: PublicKey,
      toTrampoline: PublicKey,
      toFinal: PublicKey,
      trampolineExpiryDelta: CltvExpiryDelta,
      trampolineFees: MilliSatoshi
  ): (MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets) = {

    val trampolineRoute = Seq(
      NodeHop(
        from,
        toTrampoline,
        CltvExpiryDelta(0),
        MilliSatoshi(0)
      ), // a hop from us to our peer, only needed because of our NodeId
      NodeHop(
        toTrampoline,
        toFinal,
        trampolineExpiryDelta,
        trampolineFees
      ) // for now we only use a single trampoline hop
    )

    // We send to a receiver who does not support trampoline, so relay node will send a basic MPP with inner payment secret provided and revealed
    val finalInnerPayload = PaymentOnion.createSinglePartPayload(
      pr.amountOpt.get,
      CltvExpiry(18),
      pr.paymentSecret.get,
      None
    ) // Final CLTV is supposed to be taken from invoice (+ assuming tip = 0 when testing)
    OutgoingPaymentPacket
      .buildTrampolineToLegacyPacket(
        pr,
        trampolineRoute,
        finalInnerPayload
      )
      .toOption
      .get
  }

  def createInnerNativeTrampoline(
      partAmount: MilliSatoshi,
      pr: Bolt11Invoice,
      from: PublicKey,
      toTrampoline: PublicKey,
      toFinal: PublicKey,
      trampolineExpiryDelta: CltvExpiryDelta,
      trampolineFees: MilliSatoshi
  ): (MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets) = {

    val trampolineRoute = Seq(
      NodeHop(
        from,
        toTrampoline,
        CltvExpiryDelta(0),
        MilliSatoshi(0)
      ), // a hop from us to our peer, only needed because of our NodeId
      NodeHop(
        toTrampoline,
        toFinal,
        trampolineExpiryDelta,
        trampolineFees
      ) // for now we only use a single trampoline hop
    )

    // We send to a receiver who does not support trampoline, so relay node will send a basic MPP with inner payment secret provided and revealed
    val finalInnerPayload = PaymentOnion.createMultiPartPayload(
      partAmount,
      pr.amountOpt.get,
      CltvExpiry(18),
      pr.paymentSecret.get,
      None
    ) // Final CLTV is supposed to be taken from invoice (+ assuming tip = 0 when testing)
    OutgoingPaymentPacket
      .buildTrampolinePacket(
        pr.paymentHash,
        trampolineRoute,
        finalInnerPayload
      )
      .toOption
      .get
  }

  def createTrampolineAdd(
      pr: Bolt11Invoice,
      outerPartAmount: MilliSatoshi,
      from: PublicKey,
      toTrampoline: PublicKey,
      trampolineAmountTotal: MilliSatoshi,
      trampolineExpiry: CltvExpiry,
      trampolineOnion: Sphinx.PacketAndSecrets,
      outerPaymentSecret: ByteVector32
  ): UpdateAddHtlc = {

    val update = makeUpdate(
      ShortChannelId("100x100x100").toLong,
      from,
      toTrampoline,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(144),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(50000000)
    )
    val finalHop = ChannelHop(
      GraphEdge(
        ChannelDesc(update.shortChannelId, from, toTrampoline),
        updExt = ChannelUpdateExt.fromUpdate(update)
      )
    )

    val finalOuterPayload = PaymentOnion.createMultiPartPayload(
      outerPartAmount,
      trampolineAmountTotal,
      trampolineExpiry,
      outerPaymentSecret,
      None,
      OnionPaymentPayloadTlv.TrampolineOnion(trampolineOnion.packet) :: Nil
    )
    val (firstAmount, firstExpiry, onion) =
      OutgoingPaymentPacket
        .buildPaymentPacket(
          pr.paymentHash,
          finalHop :: Nil,
          finalOuterPayload
        )
        .toOption
        .get
    UpdateAddHtlc(
      randomBytes32(),
      System.currentTimeMillis +
        randomBytes(2).toLong() % 1000,
      firstAmount,
      pr.paymentHash,
      firstExpiry,
      onion.packet
    )
  }

  def createResolution(
      pr: Bolt11Invoice,
      partAmount: MilliSatoshi,
      info: RemoteNodeInfo,
      tAmountTotal: MilliSatoshi,
      tExpiry: CltvExpiry,
      tOnion: Sphinx.PacketAndSecrets,
      secret: ByteVector32,
      cm: ChannelMaster
  ): IncomingResolution = {
    val remoteAdd = createTrampolineAdd(
      pr,
      partAmount,
      from = info.nodeId,
      toTrampoline = info.nodeSpecificPubKey,
      tAmountTotal,
      tExpiry,
      tOnion,
      secret
    )
    cm.initResolve(UpdateAddHtlcExt(theirAdd = remoteAdd, remoteInfo = info))
  }
}
