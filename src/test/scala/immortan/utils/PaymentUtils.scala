package immortan.utils

import fr.acinq.eclair._
import immortan.utils.GraphUtils._
import fr.acinq.eclair.wire.{GenericTlv, Onion, OnionTlv, UpdateAddHtlc}
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.router.Router.{ChannelDesc, ChannelHop, NodeHop}
import fr.acinq.eclair.payment.{OutgoingPacket, PaymentRequest}
import immortan.ChannelMaster.{OutgoingAdds, ReasonableResolutions}
import immortan.{ChannelMaster, InFlightPayments, LNParams, PaymentBag, PlainMetaDescription, RemoteNodeInfo, UpdateAddHtlcExt}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.{IncomingResolution, ReasonableLocal}
import fr.acinq.eclair.router.ChannelUpdateExt
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge

import scala.util.Failure


object PaymentUtils {
  def createFinalAdd(partAmount: MilliSatoshi, totalAmount: MilliSatoshi, paymentHash: ByteVector32, paymentSecret: ByteVector32, from: PublicKey, to: PublicKey, cltvDelta: Int, tlvs: Seq[GenericTlv] = Nil): UpdateAddHtlc = {
    val update = makeUpdate(ShortChannelId("100x100x100"), from, to, 1.msat, 10, cltvDelta = CltvExpiryDelta(cltvDelta), minHtlc = 10L.msat, maxHtlc = 50000000.msat)
    val finalHop = ChannelHop(GraphEdge(ChannelDesc(update.shortChannelId, from, to), updExt = ChannelUpdateExt fromUpdate update))

    val finalPayload = Onion.createMultiPartPayload(partAmount, totalAmount, update.cltvExpiryDelta.toCltvExpiry(0L), paymentSecret, userCustomTlvs = tlvs)
    val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(Sphinx.PaymentPacket)(randomKey, paymentHash, finalHop :: Nil, finalPayload)
    UpdateAddHtlc(randomBytes32, System.currentTimeMillis + secureRandom.nextInt(1000), firstAmount, paymentHash, firstExpiry, onion.packet)
  }

  def recordIncomingPaymentToFakeNodeId(amount: Option[MilliSatoshi], preimage: ByteVector32, secret: ByteVector32, payBag: PaymentBag, remoteInfo: RemoteNodeInfo): PaymentRequest = {
    val invoice = PaymentRequest(Block.TestnetGenesisBlock.hash, amount, Crypto.sha256(preimage), secret, remoteInfo.nodeSpecificPrivKey, "Invoice", CltvExpiryDelta(18), Nil)
    val prExt = PaymentRequestExt(uri = Failure(new RuntimeException), invoice, PaymentRequest.write(invoice))
    val desc = PlainMetaDescription(None, None, None, None, "Invoice", "Invoice meta")
    payBag.replaceIncomingPayment(prExt, preimage, desc, balanceSnap = 1000L.msat, fiatRateSnap = Map("USD" -> 12D))
    invoice
  }

  def makeRemoteAddToFakeNodeId(partAmount: MilliSatoshi, totalAmount: MilliSatoshi, paymentHash: ByteVector32, paymentSecret: ByteVector32,
                                remoteInfo: RemoteNodeInfo, cm: ChannelMaster, cltvDelta: Int = LNParams.cltvRejectThreshold, tlvs: Seq[GenericTlv] = Nil): ReasonableLocal = {

    val addFromRemote1 = createFinalAdd(partAmount, totalAmount, paymentHash, paymentSecret, from = remoteInfo.nodeId, to = remoteInfo.nodeSpecificPubKey, cltvDelta, tlvs)
    cm.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote1, remoteInfo = remoteInfo)).asInstanceOf[ReasonableLocal]
  }

  def makeInFlightPayments(out: OutgoingAdds, in: ReasonableResolutions): InFlightPayments = InFlightPayments(out.groupBy(_.fullTag), in.groupBy(_.fullTag))

  //

  def createInnerLegacyTrampoline(pr: PaymentRequest,
                                  from: PublicKey, toTrampoline: PublicKey, toFinal: PublicKey, trampolineExpiryDelta: CltvExpiryDelta,
                                  trampolineFees: MilliSatoshi): (MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets) = {

    val trampolineRoute = Seq(
      NodeHop(from, toTrampoline, CltvExpiryDelta(0), 0.msat), // a hop from us to our peer, only needed because of our NodeId
      NodeHop(toTrampoline, toFinal, trampolineExpiryDelta, trampolineFees) // for now we only use a single trampoline hop
    )

    // We send to a receiver who does not support trampoline, so relay node will send a basic MPP with inner payment secret provided and revealed
    val finalInnerPayload = Onion.createSinglePartPayload(pr.amount.get, CltvExpiry(18), pr.paymentSecret.get) // Final CLTV is supposed to be taken from invoice (+ assuming tip = 0 when testing)
    OutgoingPacket.buildTrampolineToLegacyPacket(randomKey, pr, trampolineRoute, finalInnerPayload)
  }

  def createInnerNativeTrampoline(partAmount: MilliSatoshi, pr: PaymentRequest,
                                  from: PublicKey, toTrampoline: PublicKey, toFinal: PublicKey, trampolineExpiryDelta: CltvExpiryDelta,
                                  trampolineFees: MilliSatoshi): (MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets) = {

    val trampolineRoute = Seq(
      NodeHop(from, toTrampoline, CltvExpiryDelta(0), 0.msat), // a hop from us to our peer, only needed because of our NodeId
      NodeHop(toTrampoline, toFinal, trampolineExpiryDelta, trampolineFees) // for now we only use a single trampoline hop
    )

    // We send to a receiver who does not support trampoline, so relay node will send a basic MPP with inner payment secret provided and revealed
    val finalInnerPayload = Onion.createMultiPartPayload(partAmount, pr.amount.get, CltvExpiry(18), pr.paymentSecret.get) // Final CLTV is supposed to be taken from invoice (+ assuming tip = 0 when testing)
    OutgoingPacket.buildPacket(Sphinx.TrampolinePacket)(randomKey, pr.paymentHash, trampolineRoute, finalInnerPayload)
  }

  def createTrampolineAdd(pr: PaymentRequest, outerPartAmount: MilliSatoshi, from: PublicKey, toTrampoline: PublicKey, trampolineAmountTotal: MilliSatoshi,
                          trampolineExpiry: CltvExpiry, trampolineOnion: Sphinx.PacketAndSecrets, outerPaymentSecret: ByteVector32): UpdateAddHtlc = {

    val update = makeUpdate(ShortChannelId("100x100x100"), from, toTrampoline, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 50000000.msat)
    val finalHop = ChannelHop(GraphEdge(ChannelDesc(update.shortChannelId, from, toTrampoline), updExt = ChannelUpdateExt.fromUpdate(update)))

    val finalOuterPayload = Onion.createMultiPartPayload(outerPartAmount, trampolineAmountTotal, trampolineExpiry, outerPaymentSecret, OnionTlv.TrampolineOnion(trampolineOnion.packet) :: Nil)
    val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(Sphinx.PaymentPacket)(randomKey, pr.paymentHash, finalHop :: Nil, finalOuterPayload)
    UpdateAddHtlc(randomBytes32, System.currentTimeMillis + secureRandom.nextInt(1000), firstAmount, pr.paymentHash, firstExpiry, onion.packet)
  }

  def createResolution(pr: PaymentRequest, partAmount: MilliSatoshi, info: RemoteNodeInfo, tAmountTotal: MilliSatoshi,
                       tExpiry: CltvExpiry, tOnion: Sphinx.PacketAndSecrets, secret: ByteVector32, cm: ChannelMaster): IncomingResolution = {
    val remoteAdd = createTrampolineAdd(pr, partAmount, from = info.nodeId, toTrampoline = info.nodeSpecificPubKey, tAmountTotal, tExpiry, tOnion, secret)
    cm.initResolve(UpdateAddHtlcExt(theirAdd = remoteAdd, remoteInfo = info))
  }
}
