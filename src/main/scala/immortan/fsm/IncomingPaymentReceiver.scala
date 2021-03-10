package immortan.fsm

import immortan._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import immortan.fsm.IncomingPaymentReceiver._
import immortan.ChannelMaster.{PreimageTry, ReasonableLocals}
import fr.acinq.eclair.channel.ReasonableLocal
import fr.acinq.bitcoin.ByteVector32
import immortan.crypto.StateMachine


object IncomingPaymentReceiver {
  final val FINALIZING = "receiver-finalizing"
  final val RECEIVING = "receiver-receiving"
  final val CMDTimeout = "cmd-timeout"
}

trait IncomingPaymentReceiverData
case class IncomingRevealed(preimage: ByteVector32) extends IncomingPaymentReceiverData
case class IncomingAborted(failure: Option[FailureMessage] = None) extends IncomingPaymentReceiverData

abstract class IncomingPaymentReceiver(fullTag: FullPaymentTag, cm: ChannelMaster) extends StateMachine[IncomingPaymentReceiverData] {
  def incomingFinalized(fullTag: FullPaymentTag) // Called when we receive a bag of cross-signed incoming payments which has no related incoming HTLCs

  // Start timeout countdown right away
  delayedCMDWorker.replaceWork(CMDTimeout)
  become(freshData = null, RECEIVING)

  def doProcess(msg: Any): Unit = (msg, data, state) match {
    case (inFlight: InFlightPayments, _, RECEIVING | FINALIZING) if !inFlight.in.contains(fullTag) =>
      // We have previously failed or fulfilled an incoming payment and all parts have been cleared
      incomingFinalized(fullTag)

    case (inFlight: InFlightPayments, null, RECEIVING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      val preimageTry: PreimageTry = cm.getPreimageMemo.get(fullTag.paymentHash)

      cm.getPaymentInfoMemo.get(fullTag.paymentHash).toOption match {
        case None => if (preimageTry.isSuccess) becomeRevealed(preimageTry.get, adds) else becomeAborted(IncomingAborted(None), adds)
        case Some(alreadyRevealed) if alreadyRevealed.isIncoming && PaymentStatus.SUCCEEDED == alreadyRevealed.status => becomeRevealed(alreadyRevealed.preimage, adds)
        case _ if adds.exists(_.add.cltvExpiry.toLong < LNParams.blockCount.get + LNParams.cltvRejectThreshold) => becomeAborted(IncomingAborted(None), adds)
        case Some(covered) if covered.isIncoming && covered.pr.amount.isDefined && askCovered(adds, covered) => becomeRevealed(covered.preimage, adds)
        case _ => // Do nothing
      }

    case (_: ReasonableLocal, null, RECEIVING) =>
      // Just saw another related add so prolong timeout
      delayedCMDWorker.replaceWork(CMDTimeout)

    case (CMDTimeout, null, RECEIVING) =>
      become(null, FINALIZING)
      cm.stateUpdated(Nil)

    // We need this extra RECEIVING -> FINALIZING step instead of failing right away
    // in case if we ever decide to use an amount-less fast crowd-fund invoices

    case (inFlight: InFlightPayments, null, FINALIZING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      val preimageTry: PreimageTry = cm.getPreimageMemo.get(fullTag.paymentHash)

      cm.getPaymentInfoMemo.get(fullTag.paymentHash).toOption match {
        case Some(alreadyRevealed) if alreadyRevealed.isIncoming && PaymentStatus.SUCCEEDED == alreadyRevealed.status => becomeRevealed(alreadyRevealed.preimage, adds)
        case Some(coveredAll) if coveredAll.isIncoming && coveredAll.pr.amount.isDefined && askCovered(adds, coveredAll) => becomeRevealed(coveredAll.preimage, adds)
        case Some(collectedSome) if collectedSome.isIncoming && collectedSome.pr.amount.isEmpty && gotSome(adds) => becomeRevealed(collectedSome.preimage, adds)
        case None => if (preimageTry.isSuccess) becomeRevealed(preimageTry.get, adds) else becomeAborted(IncomingAborted(PaymentTimeout.toSome), adds)
      }

    case (inFlight: InFlightPayments, revealed: IncomingRevealed, FINALIZING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      fulfill(revealed.preimage, adds)

    case (inFlight: InFlightPayments, aborted: IncomingAborted, FINALIZING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      abort(aborted, adds)
  }

  // Utils

  def gotSome(adds: ReasonableLocals): Boolean = adds.nonEmpty && adds.map(_.add.amountMsat).sum >= adds.head.packet.payload.totalAmount

  def askCovered(adds: ReasonableLocals, info: PaymentInfo): Boolean = adds.map(_.add.amountMsat).sum >= info.amountOrMin

  def fulfill(preimage: ByteVector32, adds: ReasonableLocals): Unit = {
    for (local <- adds) cm.sendTo(local.fulfillCommand(preimage), local.add.channelId)
  }

  def abort(data1: IncomingAborted, adds: ReasonableLocals): Unit = data1.failure match {
    case None => for (local <- adds) cm.sendTo(local.failCommand(local.add.incorrectDetails), local.add.channelId)
    case Some(fail) => for (local <- adds) cm.sendTo(local.failCommand(fail), local.add.channelId)
  }

  def becomeAborted(data1: IncomingAborted, adds: ReasonableLocals): Unit = {
    // Fail parts and retain a failure message to maybe re-fail using the same error
    become(data1, FINALIZING)
    abort(data1, adds)
  }

  def becomeRevealed(preimage: ByteVector32, adds: ReasonableLocals): Unit = {
    cm.payBag.updOkIncoming(adds.map(_.add.amountMsat).sum, fullTag.paymentHash)
    cm.getPaymentInfoMemo.invalidate(fullTag.paymentHash)
    cm.getPreimageMemo.invalidate(fullTag.paymentHash)
    become(IncomingRevealed(preimage), FINALIZING)
    fulfill(preimage, adds)
  }
}
