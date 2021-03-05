package immortan.fsm

import immortan._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import fr.acinq.eclair.channel._
import immortan.fsm.IncomingPaymentReceiver._
import immortan.ChannelMaster.ReasonableLocals
import immortan.crypto.StateMachine


object IncomingPaymentReceiver {
  val PROCESSING = "receiver-processing"
  val REJECTED = "receiver-rejected"
  val REVEALED = "receiver-revealed"
  val CMDAbort = "cmd-abort"
}

trait IncomingPaymentReceiverData
case class IncomingRevealed(info: PaymentInfo) extends IncomingPaymentReceiverData
case class IncomingRejected(commonFailure: Option[FailureMessage] = None) extends IncomingPaymentReceiverData

abstract class IncomingPaymentReceiver(fullTag: FullPaymentTag, cm: ChannelMaster) extends StateMachine[IncomingPaymentReceiverData] {
  def incomingFinalized(fullTag: FullPaymentTag) // Called when we receive a bag of cross-signed incoming payments which has no related incoming HTLCs
  def incomingRevealed(fullTag: FullPaymentTag) // Called when preimage is revealed for the first time (incoming MPP leftovers may be present)

  // Start abort timeout right away
  delayedCMDWorker.replaceWork(CMDAbort)
  become(null, PROCESSING)

  def doProcess(msg: Any): Unit = (msg, data, state) match {
    case (inFlight: InFlightPayments, _, PROCESSING | REVEALED | REJECTED) if inFlight.in.getOrElse(fullTag, Nil).isEmpty =>
      // We have previously failed or fulfilled an incoming payment as a whole and all parts have been cleared by now
      incomingFinalized(fullTag)

    case (inFlight: InFlightPayments, null, PROCESSING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      cm.getPaymentDbInfoMemo.get(fullTag.paymentHash).localOpt match {
        case Some(local) if local.isIncoming && PaymentStatus.SUCCEEDED == local.status => becomeFulfilled(local, adds)
        case Some(local) if adds.exists(_.packet.payload.totalAmount < local.amountOrMin) => becomeRejected(IncomingRejected(None), adds)
        case Some(local) if local.isIncoming && accumulatedEnough(adds) => becomeFulfilled(local, adds)
        case _ if adds.exists(tooFewBlocksUntilExpiry) => becomeRejected(IncomingRejected(None), adds)
        case None => becomeRejected(IncomingRejected(None), adds)
        case _ => // Do nothing
      }

    case (_: UpdateAddHtlcExt, null, PROCESSING) =>
      // Just saw the another add, prolong timeout
      delayedCMDWorker.replaceWork(CMDAbort)

    case (CMDAbort, null, PROCESSING) =>
      // Trigger ChannelMaster to send us pending incoming payments
      become(IncomingRejected(PaymentTimeout.toSome), REJECTED)
      cm.stateUpdated(Nil)

    case (inFlight: InFlightPayments, revealed: IncomingRevealed, REVEALED) =>
      // Re-fulfill all subsequent leftovers forever and consider them donations
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      fulfill(revealed.info, adds)

    case (inFlight: InFlightPayments, rejected: IncomingRejected, REJECTED) =>
      // Keep failing leftovers and any new parts with original failure
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      reject(rejected, adds)
  }

  // Utils

  def tooFewBlocksUntilExpiry(add: ReasonableLocal): Boolean = add.packet.add.cltvExpiry.toLong < LNParams.blockCount.get + LNParams.cltvRejectThreshold

  def accumulatedEnough(adds: Iterable[ReasonableLocal] = Nil): Boolean = adds.nonEmpty && adds.map(_.packet.add.amountMsat).sum >= adds.head.packet.payload.totalAmount

  def fulfill(info: PaymentInfo, adds: Iterable[ReasonableLocal] = None): Unit = {
    for (local <- adds) cm.sendTo(local.fulfillCommand(info.preimage), local.packet.add.channelId)
  }

  def reject(data1: IncomingRejected, adds: Iterable[ReasonableLocal] = None): Unit = data1.commonFailure match {
    case None => for (local <- adds) cm.sendTo(local.failCommand(local.packet.add.incorrectDetails), local.packet.add.channelId)
    case Some(fail) => for (local <- adds) cm.sendTo(local.failCommand(fail), local.packet.add.channelId)
  }

  def becomeRejected(data1: IncomingRejected, adds: Iterable[ReasonableLocal] = None): Unit = {
    // Fail parts and retain a failure message to maybe re-fail using the same error
    become(data1, REJECTED)
    reject(data1, adds)
  }

  def becomeFulfilled(info: PaymentInfo, adds: Iterable[ReasonableLocal] = None): Unit = {
    cm.payBag.updOkIncoming(adds.map(_.packet.add.amountMsat).sum, fullTag.paymentHash)
    cm.getPaymentDbInfoMemo.invalidate(fullTag.paymentHash)
    become(IncomingRevealed(info), REVEALED)
    incomingRevealed(fullTag)
    fulfill(info, adds)
  }
}
