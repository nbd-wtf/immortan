package immortan.fsm

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.fsm.TrampolinePaymentRelayer._
import immortan.{ChannelMaster, InFlightPayments, LNParams}
import immortan.ChannelMaster.{OutgoingAdds, PreimageTry, ReasonableTrampolines}
import fr.acinq.eclair.channel.ReasonableTrampoline
import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.eclair.router.RouteCalculation
import immortan.fsm.PaymentFailure.Failures
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import immortan.crypto.StateMachine
import scala.util.Success


trait TrampolinePaymentRelayerData
case class TrampolineProcessing(finalNodeId: PublicKey) extends TrampolinePaymentRelayerData // SENDING
case class TrampolineStopping(retryOnceFinalized: Boolean) extends TrampolinePaymentRelayerData // SENDING
case class TrampolineRevealed(preimage: ByteVector32) extends TrampolinePaymentRelayerData // SENDING | FINALIZING
case class TrampolineAborted(failure: FailureMessage) extends TrampolinePaymentRelayerData // FINALIZING

object TrampolinePaymentRelayer {
  final val FINALIZING = "relayer-finalizing"
  final val RECEIVING = "relayer-receiving"
  final val SENDING = "relayer-sending"
  final val CMDTimeout = "cmd-timeout"

  def amountIn(adds: ReasonableTrampolines): MilliSatoshi = adds.map(_.add.amountMsat).sum
  def expiryIn(adds: ReasonableTrampolines): CltvExpiry = if (adds.isEmpty) CltvExpiry(0) else adds.map(_.add.cltvExpiry).min
  def collectedEnough(adds: ReasonableTrampolines): Boolean = adds.nonEmpty && amountIn(adds) >= adds.head.packet.outerPayload.totalAmount

  def relayFee(params: TrampolineOn, ins: ReasonableTrampolines): MilliSatoshi = {
    val linearProportional = proportionalFee(amountIn(ins), params.feeProportionalMillionths)
    trampolineFee(linearProportional.toLong, params.feeBaseMsat, params.exponent, params.logExponent)
  }

  def validateRelay(params: TrampolineOn, ins: ReasonableTrampolines, blockHeight: Long): Option[FailureMessage] =
    if (ins.head.packet.innerPayload.invoiceFeatures.isDefined && ins.head.packet.innerPayload.paymentSecret.isEmpty) Some(TemporaryNodeFailure) // We do not deliver to non-MPP recipients
    else if (relayFee(params, ins) > amountIn(ins) - ins.head.packet.innerPayload.amountToForward) Some(TrampolineFeeInsufficient) // Proposed trampoline fee is less than required by our node
    else if (expiryIn(ins) - ins.head.packet.innerPayload.outgoingCltv < params.cltvExpiryDelta) Some(TrampolineExpiryTooSoon) // Proposed delta is less than required by our node
    else if (CltvExpiry(blockHeight) > ins.head.packet.innerPayload.outgoingCltv) Some(TrampolineExpiryTooSoon) // Recepient's CLTV expiry is below current chain height
    else if (ins.map(_.packet.outerPayload.totalAmount).toSet.size != 1) Some(ins.head.add.incorrectDetails) // All payment parts MUST have the same TotalAmount value
    else if (ins.head.packet.innerPayload.amountToForward < params.minimumMsat) Some(TemporaryNodeFailure) // Too small payment
    else if (ins.head.packet.innerPayload.amountToForward > params.maximumMsat) Some(TemporaryNodeFailure) // Too big payment
    else None

  def abortedWithError(failures: Failures, finalNodeId: PublicKey): TrampolineAborted = {
    val finalNodeFailure = failures.collectFirst { case remote: RemoteFailure if remote.packet.originNode == finalNodeId => remote.packet.failureMessage }
    val routingNodeFailure = failures.collectFirst { case remote: RemoteFailure if remote.packet.originNode != finalNodeId => remote.packet.failureMessage }
    val localNoRoutesFoundError = failures.collectFirst { case local: LocalFailure if local.status == PaymentFailure.NO_ROUTES_FOUND => TrampolineFeeInsufficient }
    TrampolineAborted(finalNodeFailure orElse localNoRoutesFoundError orElse routingNodeFailure getOrElse TemporaryNodeFailure)
  }
}

abstract class TrampolinePaymentRelayer(fullTag: FullPaymentTag, cm: ChannelMaster) extends StateMachine[TrampolinePaymentRelayerData] with OutgoingPaymentMasterListener { me =>
  override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = if (data.cmd.fullTag == fullTag) doProcess(data)
  def relayFinalized(fullTag: FullPaymentTag)

  private val sender = cm.opm.getSender(fullTag)
  delayedCMDWorker.replaceWork(CMDTimeout)
  become(freshData = null, RECEIVING)
  cm.opm.listeners += me

  def doProcess(msg: Any): Unit = (msg, data, state) match {
    case (inFlight: InFlightPayments, revealed: TrampolineRevealed, SENDING) =>
      // A special case after we have just received a first preimage and can become revealed
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      becomeRevealed(revealed.preimage, ins)

    case (fulfill: RemoteFulfill, _, FINALIZING | RECEIVING | SENDING) =>
      // We have outgoing in-flight payments and just got a preimage
      become(TrampolineRevealed(fulfill.preimage), SENDING)
      cm.stateUpdated(Nil)

    case (_: OutgoingPaymentSenderData, TrampolineStopping(true), SENDING) =>
      // We were waiting for all outgoing parts to fail on app restart, try again
      become(null, RECEIVING)
      cm.stateUpdated(Nil)

    case (data: OutgoingPaymentSenderData, _: TrampolineStopping, SENDING) =>
      // We were waiting for all outgoing parts to fail on app restart, fail incoming
      become(abortedWithError(data.failures, invalidPubKey), FINALIZING)
      cm.stateUpdated(Nil)

    case (data: OutgoingPaymentSenderData, processing: TrampolineProcessing, SENDING) =>
      // This was a normal operation where we were trying to deliver a payment to recipient
      become(abortedWithError(data.failures, processing.finalNodeId), FINALIZING)
      cm.stateUpdated(Nil)

    case (inFlight: InFlightPayments, _, FINALIZING | SENDING) if !inFlight.allTags.contains(fullTag) && sender.data.inFlightParts.isEmpty =>
      // We have neither incoming nor outgoing parts left in channels and no in-flight parts in sender FSM, the only option is to finalize a relay
      // this will most likely happen AFTER we have resolved all outgoing payments and started resolving related incoming payments
      relayFinalized(fullTag)

    case (inFlight: InFlightPayments, null, RECEIVING) =>
      // We have either just seen another part or restored an app with parts
      val preimageTry: PreimageTry = cm.getPreimageMemo.get(fullTag.paymentHash)
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      val outs: OutgoingAdds = inFlight.out.getOrElse(fullTag, Nil)

      preimageTry match {
        case Success(preimage) => becomeRevealed(preimage, ins)
        case _ if collectedEnough(ins) && outs.isEmpty => becomeSendingOrAborted(ins)
        case _ if collectedEnough(ins) && outs.nonEmpty => become(TrampolineStopping(retryOnceFinalized = true), SENDING) // App has been restarted midway, fail safely and retry
        case _ if outs.nonEmpty => become(TrampolineStopping(retryOnceFinalized = false), SENDING) // Have not collected enough yet have outgoing (this is pathologic state)
        case _ if !inFlight.allTags.contains(fullTag) => relayFinalized(fullTag) // Somehow no leftovers are present at all, nothing left to do
        case _ => // Do nothing, wait for more parts with a timeout
      }

    case (_: ReasonableTrampoline, null, RECEIVING) =>
      // Just saw another related add so prolong timeout
      delayedCMDWorker.replaceWork(CMDTimeout)

    case (CMDTimeout, null, RECEIVING) =>
      // Sender must not have outgoing payments in this state
      become(TrampolineAborted(PaymentTimeout), FINALIZING)
      cm.stateUpdated(Nil)

    case (inFlight: InFlightPayments, revealed: TrampolineRevealed, FINALIZING) =>
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      fulfill(revealed.preimage, ins)

    case (inFlight: InFlightPayments, aborted: TrampolineAborted, FINALIZING) =>
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      abort(aborted, ins)
  }

  def fulfill(preimage: ByteVector32, adds: ReasonableTrampolines): Unit = {
    for (local <- adds) cm.sendTo(local.fulfillCommand(preimage), local.add.channelId)
  }

  def abort(data1: TrampolineAborted, adds: ReasonableTrampolines): Unit = {
    for (local <- adds) cm.sendTo(local.failCommand(data1.failure), local.add.channelId)
  }

  def becomeSendingOrAborted(adds: ReasonableTrampolines): Unit = {
    // Make sure all supplied parameters are sane before starting a send phase
    val result = validateRelay(LNParams.trampoline, adds, LNParams.blockCount.get)

    result match {
      case Some(failure) =>
        val data1 = TrampolineAborted(failure)
        become(data1, FINALIZING)
        abort(data1, adds)

      case None =>
        val innerPayload = adds.head.packet.innerPayload
        val totalFeeReserve = amountIn(adds) - innerPayload.amountToForward - relayFee(LNParams.trampoline, adds)
        val routerConf = LNParams.routerConf.copy(maxCltv = expiryIn(adds) - innerPayload.outgoingCltv - LNParams.trampoline.cltvExpiryDelta)
        val extraEdges = RouteCalculation.makeExtraEdges(innerPayload.invoiceRoutingInfo.map(_.map(_.toList).toList) getOrElse Nil, innerPayload.outgoingNodeId)
        val send = SendMultiPart(fullTag, routerConf, innerPayload.outgoingNodeId, innerPayload.amountToForward, totalFeeReserve, innerPayload.outgoingCltv, cm.all.values.toSeq)

        become(TrampolineProcessing(innerPayload.outgoingNodeId), SENDING)
        // If invoice features are present, the sender is asking us to relay to a non-trampoline recipient, it is known that recipient supports MPP
        if (innerPayload.invoiceFeatures.isDefined) cm.opm process send.copy(assistedEdges = extraEdges, paymentSecret = innerPayload.paymentSecret.get)
        else cm.opm process send.copy(onionTlvs = OnionTlv.TrampolineOnion(adds.head.packet.nextPacket) :: Nil, paymentSecret = randomBytes32)
    }
  }

  def becomeRevealed(preimage: ByteVector32, adds: ReasonableTrampolines): Unit = {
    // Unconditionally persist an obtained preimage and update relays if we have data
    cm.payBag.storePreimage(fullTag.paymentHash, preimage)
    cm.getPreimageMemo.invalidate(fullTag.paymentHash)
    become(TrampolineRevealed(preimage), FINALIZING)
    fulfill(preimage, adds)

    adds.headOption.foreach { add =>
      // This accounts for pathological case where don't have incoing HTLCs for whatever reason
      val finalFee = add.packet.outerPayload.totalAmount - add.packet.innerPayload.amountToForward - sender.data.usedFee
      cm.payBag.addRelayedPreimageInfo(fullTag.paymentHash, preimage, System.currentTimeMillis, add.packet.innerPayload.totalAmount, finalFee)
    }
  }
}
