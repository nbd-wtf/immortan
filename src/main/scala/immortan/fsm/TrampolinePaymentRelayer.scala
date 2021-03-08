package immortan.fsm

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.fsm.TrampolinePaymentRelayer._
import immortan.{ChannelMaster, InFlightPayments, LNParams}
import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.bitcoin.ByteVector32
import immortan.ChannelMaster.{OutgoingAdds, PreimageTry, ReasonableTrampolines}
import immortan.crypto.StateMachine


object TrampolinePaymentRelayer {
  final val RESOLVING = "relayer-resolving"
  final val FINALIZING = "relayer-finalizing"
  final val RECEIVING = "relayer-receiving"
  final val SENDING = "relayer-sending"
  final val CMDTimeout = "cmd-timeout"

  case class TrampolineAdds(adds: ReasonableTrampolines) {
    val amountIn: MilliSatoshi = adds.map(_.packet.add.amountMsat).sum
    val expiryIn: CltvExpiry = adds.map(_.packet.add.cltvExpiry).min
  }

  def getRelayFeeOrReject(params: TrampolineOn, upstream: TrampolineAdds, payloadOut: Onion.NodeRelayPayload): Either[FailureMessage, MilliSatoshi] = {
    val fee = trampolineFee(proportionalFee(params.feeProportionalMillionths, upstream.amountIn).toLong, params.feeBaseMsat, params.exponent, params.logExponent)
    if (payloadOut.invoiceFeatures.isDefined && payloadOut.paymentSecret.isEmpty) Left(TemporaryNodeFailure) // We do not deliver to non-trampoline, non-MPP recipients
    else if (upstream.expiryIn - payloadOut.outgoingCltv < params.cltvExpiryDelta) Left(TrampolineExpiryTooSoon)
    else if (upstream.amountIn - payloadOut.amountToForward < fee) Left(TrampolineFeeInsufficient)
    else if (payloadOut.amountToForward < params.minimumMsat) Left(TemporaryNodeFailure)
    else if (payloadOut.amountToForward > params.maximumMsat) Left(TemporaryNodeFailure)
    else Right(fee)
  }

  def translateError(failures: Seq[PaymentFailure], nextPayload: Onion.NodeRelayPayload): FailureMessage = {
    val nextTrampolineOrRecipientFailure = failures.collectFirst { case remote: RemoteFailure if remote.packet.originNode == nextPayload.outgoingNodeId => remote.packet.failureMessage }
    val localNoRoutesFoundError = failures.collectFirst { case local: LocalFailure if local.status == PaymentFailure.NO_ROUTES_FOUND => TrampolineFeeInsufficient }
    nextTrampolineOrRecipientFailure orElse localNoRoutesFoundError getOrElse TemporaryNodeFailure
  }
}

trait TrampolinePaymentRelayerData
case class TrampolineRevealed(preimage: ByteVector32) extends TrampolinePaymentRelayerData
case class TrampolineRejected(failure: Option[FailureMessage], retry: Boolean) extends TrampolinePaymentRelayerData

abstract class TrampolinePaymentRelayer(fullTag: FullPaymentTag, cm: ChannelMaster) extends StateMachine[TrampolinePaymentRelayerData] with OutgoingPaymentMasterListener { me =>
  override def outgoingPartAborted(data: OutgoingPaymentSenderData): Unit = if (data.cmd.fullTag == fullTag && noInChanOutgoingLeft && data.inFlightParts.isEmpty) doProcess(data)
  override def gotPreimage(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill, isFirst: Boolean): Unit = if (data.cmd.fullTag == fullTag && isFirst) doProcess(fulfill)

  become(null, RESOLVING)
  cm.opm.getSender(fullTag)
  cm.opm.listeners += me

  def doProcess(msg: Any): Unit = (msg, data, state) match {
    case (inFlight: InFlightPayments, _, RESOLVING | FINALIZING | RECEIVING | SENDING) if inFlight.nothingLeftForTag(fullTag) =>
      // Payment as a whole has been failed or fulfilled and neither outgoing nor incoming parts are left by now


    case (inFlight: InFlightPayments, null, RESOLVING) =>
      val preimageTry: PreimageTry = cm.getPreimageMemo.get(fullTag.paymentHash)
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      val outs: OutgoingAdds = inFlight.out.getOrElse(fullTag, Nil)

  }

  def noInChanOutgoingLeft: Boolean = !cm.allInChannelOutgoing.contains(fullTag)

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
