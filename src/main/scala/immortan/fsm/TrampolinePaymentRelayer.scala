package immortan.fsm

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.payment.OutgoingPacket.TrampolineAdds
import fr.acinq.eclair.router.Router.RouteParams
import immortan.LNParams


object TrampolinePaymentRelayer {
  final val RESOLVING = "relayer-resolving"
  final val FINALIZING = "relayer-finalizing"
  final val RECEIVING = "relayer-receiving"
  final val SENDING = "relayer-sending"
  final val CMDAbort = "cmd-abort"

  def getRelayFeeOrReject(params: TrampolineOn, upstream: TrampolineAdds, payloadOut: Onion.NodeRelayPayload): Either[FailureMessage, MilliSatoshi] = {
    val fee = trampolineFee(proportionalFee(params.feeProportionalMillionths, upstream.amountIn).toLong, params.feeBaseMsat, params.exponent, params.logExponent)
    if (upstream.expiryIn - payloadOut.outgoingCltv < params.cltvExpiryDelta) Left(TrampolineExpiryTooSoon)
    else if (upstream.amountIn - payloadOut.amountToForward < fee) Left(TrampolineFeeInsufficient)
    else if (payloadOut.amountToForward > params.minimumMsat) Left(TemporaryNodeFailure)
    else if (payloadOut.amountToForward < params.minimumMsat) Left(TemporaryNodeFailure)
    else Right(fee)
  }

  def computeRouteParams(params: TrampolineOn, fee: MilliSatoshi, amountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi, expiryOut: CltvExpiry): RouteParams =
    RouteParams(feeReserve = amountIn - amountOut - fee, routeMaxLength = LNParams.routerConf.routeHopDistance, routeMaxCltv = expiryIn - expiryOut - params.cltvExpiryDelta)

  def translateError(failures: Seq[PaymentFailure], nextPayload: Onion.NodeRelayPayload): FailureMessage = {
    val localNoRoutesFoundError = failures.collectFirst { case local: LocalFailure if local.status == PaymentFailure.NO_ROUTES_FOUND => TrampolineFeeInsufficient }
    val nextTrampolineOrRecipientFailure = failures.collectFirst { case remote: RemoteFailure if remote.packet.originNode == nextPayload.outgoingNodeId => remote.packet.failureMessage }
    localNoRoutesFoundError orElse nextTrampolineOrRecipientFailure getOrElse TemporaryNodeFailure
  }
}
