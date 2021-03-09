package immortan.fsm

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.fsm.TrampolinePaymentRelayer._
import immortan.{ChannelMaster, InFlightPayments, LNParams}
import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.ReasonableTrampoline
import immortan.ChannelMaster.{OutgoingAdds, PreimageTry, ReasonableTrampolines}
import immortan.crypto.StateMachine
import immortan.fsm.PaymentFailure.Failures

import scala.util.Success


object TrampolinePaymentRelayer {
  final val FINALIZING = "relayer-finalizing"
  final val RECEIVING = "relayer-receiving"
  final val SENDING = "relayer-sending"
  final val CMDTimeout = "cmd-timeout"

  def amountIn(adds: ReasonableTrampolines): MilliSatoshi = adds.map(_.packet.add.amountMsat).sum
  def expiryIn(adds: ReasonableTrampolines): CltvExpiry = if (adds.isEmpty) CltvExpiry(0) else adds.map(_.packet.add.cltvExpiry).min

  def relayFee(params: TrampolineOn, adds: ReasonableTrampolines): MilliSatoshi = {
    val linearProportional = proportionalFee(amountIn(adds), params.feeProportionalMillionths)
    trampolineFee(linearProportional.toLong, params.feeBaseMsat, params.exponent, params.logExponent)
  }

  def validateRelay(params: TrampolineOn, adds: ReasonableTrampolines, fee: MilliSatoshi, payloadOut: Onion.NodeRelayPayload, blockHeight: Long): Option[FailureMessage] =
    if (adds.map(_.packet.outerPayload.totalAmount).toSet.size > 1) Some(adds.head.packet.add.incorrectDetails) // All payment parts MUST have identical TotalAmount values
    else if (payloadOut.invoiceFeatures.isDefined && payloadOut.paymentSecret.isEmpty) Some(TemporaryNodeFailure) // We do not deliver to non-trampoline, non-MPP recipients
    else if (expiryIn(adds) - payloadOut.outgoingCltv < params.cltvExpiryDelta) Some(TrampolineExpiryTooSoon) // Proposed delta is less than required by our node
    else if (amountIn(adds) - payloadOut.amountToForward < fee) Some(TrampolineFeeInsufficient) // Proposed trampoline fee is less than required by our node
    else if (CltvExpiry(blockHeight) > payloadOut.outgoingCltv) Some(TrampolineExpiryTooSoon) // Recepient's CLTV expiry is below current chain height
    else if (payloadOut.amountToForward < params.minimumMsat) Some(TemporaryNodeFailure) // Too small payment
    else if (payloadOut.amountToForward > params.maximumMsat) Some(TemporaryNodeFailure) // Too big payment
    else None

  def translateError(failures: Seq[PaymentFailure], nextPayload: Onion.NodeRelayPayload): FailureMessage = {
    val nextTrampolineOrRecipientFailure = failures.collectFirst { case remote: RemoteFailure if remote.packet.originNode == nextPayload.outgoingNodeId => remote.packet.failureMessage }
    val localNoRoutesFoundError = failures.collectFirst { case local: LocalFailure if local.status == PaymentFailure.NO_ROUTES_FOUND => TrampolineFeeInsufficient }
    nextTrampolineOrRecipientFailure orElse localNoRoutesFoundError getOrElse TemporaryNodeFailure
  }
}

trait TrampolinePaymentRelayerData
case class TrampolineAborted(failures: Failures, retry: Boolean) extends TrampolinePaymentRelayerData
case class TrampolineRevealed(preimage: ByteVector32) extends TrampolinePaymentRelayerData

abstract class TrampolinePaymentRelayer(fullTag: FullPaymentTag, cm: ChannelMaster) extends StateMachine[TrampolinePaymentRelayerData] with OutgoingPaymentMasterListener { me =>
  override def outgoingPartAborted(data: OutgoingPaymentSenderData): Unit = if (data.cmd.fullTag == fullTag && noInChanOutgoingLeft && data.inFlightParts.isEmpty) doProcess(data)
  override def gotPreimage(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill, isFirst: Boolean): Unit = if (data.cmd.fullTag == fullTag && isFirst) doProcess(fulfill)
  def relayFinalized(fullTag: FullPaymentTag) // Called when we receive a bag of cross-signed incoming and sent outgoing payments which has no related HTLCs

  // Create sender and start timer right away
  private val sender = cm.opm.getSender(fullTag)
  delayedCMDWorker.replaceWork(CMDTimeout)
  become(null, RECEIVING)
  cm.opm.listeners += me

  def doProcess(msg: Any): Unit = (msg, data, state) match {
    case (_: OutgoingPaymentSenderData, aborted: TrampolineAborted, SENDING) if aborted.retry =>
      // We were waiting for all outgoing payments to get resolved, none of them left by now, we may make another payment attempt
      // it is safe to not look for in-flight payments in sender FSM because we can only get into this state after restart
      become(null, RECEIVING)
      cm.stateUpdated(Nil)

    case (data: OutgoingPaymentSenderData, _: TrampolineAborted, SENDING) =>
      // We were waiting for all outgoing payments to get resolved, none of them left by now, we can start failing incoming payments
      // it is safe to not look for in-flight payments in sender FSM because we can only get into this state after restart
      become(TrampolineAborted(data.failures, retry = false), FINALIZING)
      cm.stateUpdated(Nil)

    case (data: OutgoingPaymentSenderData, null, SENDING) =>


//    case (_: OutgoingPaymentSenderData, _, FINALIZING | RECEIVING | SENDING) =>
//      // Payment as a whole has been failed or fulfilled and neither outgoing nor incoming parts are left by now
//      relayFinalized(fullTag)

    case (inFlight: InFlightPayments, null, RECEIVING) =>
      // We have either just seen a first part or restored an app with parts
      val preimageTry: PreimageTry = cm.getPreimageMemo.get(fullTag.paymentHash)
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      val outs: OutgoingAdds = inFlight.out.getOrElse(fullTag, Nil)

      (preimageTry, ins, outs) match {
        case (Success(preimage) => becomeRevealed(preimage, ins)
        case None if collectedEnough(ins) && outs.isEmpty => // No preimage, got enough but had no time to start sendout => can restart sendout
        case None if collectedEnough(ins) && outs.nonEmpty => // No preimage, got enough and started a sendout => become FINALIZING with retry on failure
        case None if ins.nonEmpty && outs.isEmpty => // No preimage, did not get enough yet => become RECEIVING with timeout
        case None if ins.nonEmpty && outs.nonEmpty => // No preimage, we did not get enough yet outgoing adds are present => become FINALIZING with NO retry after all outgoing parts are resolved
        case None if ins.isEmpty && outs.nonEmpty => // No preimage, we have nothing incoming yet outgoing adds are present => become FINALIZING with NO retry after all outgoing parts are resolved
        case None if ins.isEmpty && outs.isEmpty => // Finalized
      }


    case (inFlight: InFlightPayments, revealed: TrampolineRevealed, FINALIZING) =>
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      fulfill(revealed.preimage, ins)

    case (inFlight: InFlightPayments, aborted: TrampolineAborted, FINALIZING) =>
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      abort(aborted, ins)
  }

  def fulfill(preimage: ByteVector32, adds: ReasonableTrampolines): Unit = {
    for (local <- adds) cm.sendTo(local.fulfillCommand(preimage), local.packet.add.channelId)
  }

  def abort(data1: TrampolineAborted, adds: ReasonableTrampolines): Unit = data1.failure match {
    case None => for (local <- adds) cm.sendTo(local.failCommand(local.packet.add.incorrectDetails), local.packet.add.channelId)
    case Some(fail) => for (local <- adds) cm.sendTo(local.failCommand(fail), local.packet.add.channelId)
  }

  def becomeRevealed(preimage: ByteVector32, adds: ReasonableTrampolines): Unit = {
    // update database
    cm.getPreimageMemo.invalidate(fullTag.paymentHash)
    become(TrampolineRevealed(preimage), FINALIZING)
    fulfill(preimage, adds)
  }

  def noInChanOutgoingLeft: Boolean = !cm.allInChannelOutgoing.contains(fullTag)

  def collectedEnough(adds: ReasonableTrampolines): Boolean = adds.nonEmpty && amountIn(adds) >= adds.head.packet.outerPayload.totalAmount

  def computeRouteParams(params: TrampolineOn, adds: ReasonableTrampolines, fee: MilliSatoshi, payloadOut: Onion.NodeRelayPayload, packetOut: OnionRoutingPacket): SendMultiPart = {
    val sendMultiPart = SendMultiPart(fullTag, LNParams.routerConf.copy(maxCltv = expiryIn(adds) - payloadOut.outgoingCltv - params.cltvExpiryDelta), targetNodeId = payloadOut.outgoingNodeId,
      totalAmount = payloadOut.amountToForward, totalFeeReserve = amountIn(adds) - payloadOut.amountToForward - fee, targetExpiry = payloadOut.outgoingCltv, allowedChans = cm.all.values.toSeq)

    val routingHints = payloadOut.invoiceRoutingInfo.map(_.map(_.toList).toList).getOrElse(Nil)
    val extraEdges = RouteCalculation.makeExtraEdges(routingHints, payloadOut.outgoingNodeId)

    // If invoice features are present, the sender is asking us to relay to a non-trampoline recipient
    // due to previously performed checks it is known that recipient supports MPP, so final payment is safe to exatract from payload
    if (payloadOut.invoiceFeatures.isDefined) sendMultiPart.copy(assistedEdges = extraEdges, paymentSecret = payloadOut.paymentSecret.get)
    else sendMultiPart.copy(onionTlvs = OnionTlv.TrampolineOnion(packetOut) :: Nil, paymentSecret = randomBytes32)
  }
}
