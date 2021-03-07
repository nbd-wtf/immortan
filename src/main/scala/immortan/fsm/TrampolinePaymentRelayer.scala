package immortan.fsm

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.{ChannelMaster, LNParams}
import fr.acinq.eclair.payment.OutgoingPacket.TrampolineAdds
import fr.acinq.eclair.router.RouteCalculation


object TrampolinePaymentRelayer {
  final val DECIDING = "relayer-deciding"
  final val FINALIZING = "relayer-finalizing"
  final val RECEIVING = "relayer-receiving"
  final val SENDING = "relayer-sending"
  final val CMDTimeout = "cmd-timeout"

  def getRelayFeeOrReject(params: TrampolineOn, upstream: TrampolineAdds, payloadOut: Onion.NodeRelayPayload): Either[FailureMessage, MilliSatoshi] = {
    val fee = trampolineFee(proportionalFee(params.feeProportionalMillionths, upstream.amountIn).toLong, params.feeBaseMsat, params.exponent, params.logExponent)
    if (payloadOut.invoiceFeatures.isDefined && payloadOut.paymentSecret.isEmpty) Left(TemporaryNodeFailure) // We do not deliver to non-trampoline, non-MPP recipients
    else if (upstream.expiryIn - payloadOut.outgoingCltv < params.cltvExpiryDelta) Left(TrampolineExpiryTooSoon)
    else if (upstream.amountIn - payloadOut.amountToForward < fee) Left(TrampolineFeeInsufficient)
    else if (payloadOut.amountToForward > params.minimumMsat) Left(TemporaryNodeFailure)
    else if (payloadOut.amountToForward < params.maximumMsat) Left(TemporaryNodeFailure)
    else Right(fee)
  }

  def translateError(failures: Seq[PaymentFailure], nextPayload: Onion.NodeRelayPayload): FailureMessage = {
    val localNoRoutesFoundError = failures.collectFirst { case local: LocalFailure if local.status == PaymentFailure.NO_ROUTES_FOUND => TrampolineFeeInsufficient }
    val nextTrampolineOrRecipientFailure = failures.collectFirst { case remote: RemoteFailure if remote.packet.originNode == nextPayload.outgoingNodeId => remote.packet.failureMessage }
    localNoRoutesFoundError orElse nextTrampolineOrRecipientFailure getOrElse TemporaryNodeFailure
  }
}

abstract class TrampolinePaymentRelayer(fullTag: FullPaymentTag, cm: ChannelMaster) {
  def computeRouteParams(params: TrampolineOn, adds: TrampolineAdds, fee: MilliSatoshi, payloadOut: Onion.NodeRelayPayload, packetOut: OnionRoutingPacket): SendMultiPart = {
    val sendMultiPart = SendMultiPart(fullTag, LNParams.routerConf.copy(maxCltv = adds.expiryIn - payloadOut.outgoingCltv - params.cltvExpiryDelta), targetNodeId = payloadOut.outgoingNodeId,
      totalAmount = payloadOut.amountToForward, totalFeeReserve = adds.amountIn - payloadOut.amountToForward - fee, targetExpiry = payloadOut.outgoingCltv, allowedChans = cm.all.values.toSeq)

    val routingHints = payloadOut.invoiceRoutingInfo.map(_.map(_.toList).toList).getOrElse(Nil)
    val extraEdges = RouteCalculation.makeExtraEdges(routingHints, payloadOut.outgoingNodeId)

    // If invoice features are present, the sender is asking us to relay to a non-trampoline recipient which is confirmed to support MPP
    if (payloadOut.invoiceFeatures.isDefined) sendMultiPart.copy(assistedEdges = extraEdges, paymentSecret = payloadOut.paymentSecret.get)
    else sendMultiPart.copy(onionTlvs = OnionTlv.TrampolineOnion(packetOut) :: Nil, paymentSecret = randomBytes32)
  }
}
