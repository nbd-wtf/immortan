package immortan

import scala.util.Try
import scodec.Attempt
import scodec.bits.ByteVector
import scoin._
import scoin.Crypto.{PublicKey, PrivateKey}
import scoin.ln._

import immortan.router.Router._
import immortan.channel._

class ChannelException(val channelId: ByteVector32, message: String)
    extends RuntimeException(message)

case class CannotExtractSharedSecret(
    override val channelId: ByteVector32,
    htlc: UpdateAddHtlc
) extends ChannelException(
      channelId,
      s"can't extract shared secret: paymentHash=${htlc.paymentHash} onion=${htlc.onionRoutingPacket}"
    )

/** Helpers to create outgoing payment packets. */
object OutgoingPaymentPacket {

  /** Build an encrypted onion packet from onion payloads and node public keys.
    */
  private def buildOnion(
      packetPayloadLength: Int,
      nodes: Seq[PublicKey],
      payloads: Seq[PaymentOnion.PerHopPayload],
      associatedData: ByteVector32
  ): Try[Sphinx.PacketAndSecrets] = {
    require(nodes.size == payloads.size)
    val sessionKey = randomKey()
    val payloadsBin: Seq[ByteVector] = payloads
      .map {
        case p: PaymentOnion.FinalPayload =>
          PaymentOnionCodecs.finalPerHopPayloadCodec.encode(p)
        case p: PaymentOnion.ChannelRelayPayload =>
          PaymentOnionCodecs.channelRelayPerHopPayloadCodec.encode(p)
        case p: PaymentOnion.NodeRelayPayload =>
          PaymentOnionCodecs.nodeRelayPerHopPayloadCodec.encode(p)
      }
      .map {
        case Attempt.Successful(bitVector) => bitVector.bytes
        case Attempt.Failure(cause) =>
          throw new RuntimeException(s"serialization error: $cause")
      }
    Sphinx.create(
      sessionKey,
      packetPayloadLength,
      nodes,
      payloadsBin,
      Some(associatedData)
    )
  }

  /** Build the onion payloads for each hop.
    *
    * @param hops
    *   the hops as computed by the router + extra routes from the invoice
    * @param finalPayload
    *   payload data for the final node (amount, expiry, etc)
    * @return
    *   a (firstAmount, firstExpiry, payloads) tuple where:
    *   - firstAmount is the amount for the first htlc in the route
    *   - firstExpiry is the cltv expiry for the first htlc in the route
    *   - a sequence of payloads that will be used to build the onion
    */
  def buildPayloads(
      hops: Seq[Hop],
      finalPayload: PaymentOnion.FinalTlvPayload
  ): (MilliSatoshi, CltvExpiry, Seq[PaymentOnion.PerHopPayload]) = {
    hops.reverse.foldLeft(
      (
        finalPayload.amount,
        finalPayload.expiry,
        Seq[PaymentOnion.PerHopPayload](finalPayload)
      )
    ) { case ((amount, expiry, payloads), hop) =>
      val payload = hop match {
        case hop: ChannelHop =>
          PaymentOnion.ChannelRelayTlvPayload(
            outgoingChannelId = hop.edge.desc.shortChannelId,
            amountToForward = amount,
            outgoingCltv = expiry
          )
        case hop: NodeHop =>
          PaymentOnion.createNodeRelayPayload(amount, expiry, hop.nextNodeId)
      }
      (
        amount + hop.fee(amount),
        expiry + hop.cltvExpiryDelta,
        payload +: payloads
      )
    }
  }

  /** Build an encrypted onion packet with the given final payload.
    *
    * @param hops
    *   the hops as computed by the router + extra routes from the invoice,
    *   including ourselves in the first hop
    * @param finalPayload
    *   payload data for the final node (amount, expiry, etc)
    * @return
    *   a (firstAmount, firstExpiry, onion) tuple where:
    *   - firstAmount is the amount for the first htlc in the route
    *   - firstExpiry is the cltv expiry for the first htlc in the route
    *   - the onion to include in the HTLC
    */
  private def buildPacket(
      packetPayloadLength: Int,
      paymentHash: ByteVector32,
      hops: Seq[Hop],
      finalPayload: PaymentOnion.FinalTlvPayload
  ): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] = {
    val (firstAmount, firstExpiry, payloads) =
      buildPayloads(hops.drop(1), finalPayload)
    val nodes = hops.map(_.nextNodeId)
    // BOLT 2 requires that associatedData == paymentHash
    buildOnion(packetPayloadLength, nodes, payloads, paymentHash).map(onion =>
      (firstAmount, firstExpiry, onion)
    )
  }

  def buildPaymentPacket(
      paymentHash: ByteVector32,
      hops: Seq[Hop],
      finalPayload: PaymentOnion.FinalTlvPayload
  ): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] =
    buildPacket(
      PaymentOnionCodecs.paymentOnionPayloadLength,
      paymentHash,
      hops,
      finalPayload
    )

  def buildTrampolinePacket(
      paymentHash: ByteVector32,
      hops: Seq[Hop],
      finalPayload: PaymentOnion.FinalTlvPayload
  ): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] =
    buildPacket(
      PaymentOnionCodecs.trampolineOnionPayloadLength,
      paymentHash,
      hops,
      finalPayload
    )

  /** Build an encrypted trampoline onion packet when the final recipient
    * doesn't support trampoline. The next-to-last trampoline node payload will
    * contain instructions to convert to a legacy payment.
    *
    * @param invoice
    *   Bolt 11 invoice (features and routing hints will be provided to the
    *   next-to-last node).
    * @param hops
    *   the trampoline hops (including ourselves in the first hop, and the
    *   non-trampoline final recipient in the last hop).
    * @param finalPayload
    *   payload data for the final node (amount, expiry, etc)
    * @return
    *   a (firstAmount, firstExpiry, onion) tuple where:
    *   - firstAmount is the amount for the trampoline node in the route
    *   - firstExpiry is the cltv expiry for the first trampoline node in the
    *     route
    *   - the trampoline onion to include in final payload of a normal onion
    */
  def buildTrampolineToLegacyPacket(
      invoice: Bolt11Invoice,
      hops: Seq[NodeHop],
      finalPayload: PaymentOnion.FinalTlvPayload
  ): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] = {
    // NB: the final payload will never reach the recipient, since the next-to-last node in the trampoline route will convert that to a non-trampoline payment.
    // We use the smallest final payload possible, otherwise we may overflow the trampoline onion size.
    val dummyFinalPayload = PaymentOnion.createSinglePartPayload(
      finalPayload.amount,
      finalPayload.expiry,
      finalPayload.paymentSecret,
      None
    )
    val (firstAmount, firstExpiry, payloads) = hops
      .drop(1)
      .reverse
      .foldLeft(
        (
          finalPayload.amount,
          finalPayload.expiry,
          Seq[PaymentOnion.PerHopPayload](dummyFinalPayload)
        )
      ) { case ((amount, expiry, payloads), hop) =>
        // The next-to-last node in the trampoline route must receive invoice data to indicate the conversion to a non-trampoline payment.
        val payload = if (payloads.length == 1) {
          PaymentOnion.createNodeRelayToNonTrampolinePayload(
            finalPayload.amount,
            finalPayload.totalAmount,
            finalPayload.expiry,
            hop.nextNodeId,
            invoice
          )
        } else {
          PaymentOnion.createNodeRelayPayload(amount, expiry, hop.nextNodeId)
        }
        (
          amount + hop.fee(amount),
          expiry + hop.cltvExpiryDelta,
          payload +: payloads
        )
      }
    val nodes = hops.map(_.nextNodeId)
    buildOnion(
      PaymentOnionCodecs.trampolineOnionPayloadLength,
      nodes,
      payloads,
      invoice.paymentHash
    ).map(onion => (firstAmount, firstExpiry, onion))
  }

  def buildHtlcFailure(
      nodeSecret: PrivateKey,
      reason: Either[ByteVector, FailureMessage],
      add: UpdateAddHtlc
  ): Either[CannotExtractSharedSecret, ByteVector] = {
    Sphinx.peel(
      nodeSecret,
      Some(add.paymentHash),
      add.onionRoutingPacket
    ) match {
      case Right(Sphinx.DecryptedPacket(_, _, sharedSecret)) =>
        val encryptedReason = reason match {
          case Left(forwarded) =>
            Sphinx.FailurePacket.wrap(forwarded, sharedSecret)
          case Right(failure) =>
            Sphinx.FailurePacket.create(sharedSecret, failure)
        }
        Right(encryptedReason)
      case Left(_) => Left(CannotExtractSharedSecret(add.channelId, add))
    }
  }

  def buildHtlcFailure(
      cmd: CMD_FAIL_HTLC
  ): Either[CannotExtractSharedSecret, UpdateFailHtlc] = {
    buildHtlcFailure(cmd.nodeSecret, cmd.reason, cmd.theirAdd).map {
      encryptedReason =>
        UpdateFailHtlc(cmd.theirAdd.channelId, cmd.theirAdd.id, encryptedReason)
    }
  }
}
