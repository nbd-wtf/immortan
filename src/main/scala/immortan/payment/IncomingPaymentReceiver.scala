package immortan.payment

import immortan._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.channel._
import immortan.payment.IncomingPaymentReceiver._
import immortan.ChannelMaster.ReasonableLocals
import immortan.crypto.StateMachine


object IncomingPaymentReceiver {
  val PROCESSING = "receiver-processing"
  val REJECTED = "receiver-rejected"
}

trait IncomingPaymentReceiverData

case class IncomingRejected(commonFailure: Option[FailureMessage] = None) extends IncomingPaymentReceiverData

abstract class IncomingPaymentReceiver(fullTag: FullPaymentTag, cm: ChannelMaster) extends StateMachine[IncomingPaymentReceiverData] { me =>
  def incomingFinalized(fullTag: FullPaymentTag) // Called when we receive a bag of cross-signed incoming payments which has no related incoming HTLCs
  def incomingRevealed(fullTag: FullPaymentTag) // Called when preimage is revealed for the first time (incoming MPP leftovers may be present)

  become(null, PROCESSING)

  def doProcess(msg: Any): Unit = (msg, data, state) match {
    case (inFlight: InFlightPayments, null, PROCESSING | REJECTED) if inFlight.in.getOrElse(fullTag, Nil).isEmpty =>
      // We have previously failed or fulfilled an incoming payment and all parts are cleared by now
      incomingFinalized(fullTag)

    case (inFlight: InFlightPayments, null, PROCESSING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      cm.getPaymentDbInfoMemo.get(fullTag.paymentHash).localOpt match {
        case Some(local) if adds.exists(_.packet.payload.totalAmount < local.amountOrMin) => failBecome(IncomingRejected(None), adds)
        case Some(local) if local.isIncoming && PaymentStatus.SUCCEEDED == local.status => selectivelyFulfill(local, adds)
        case Some(local) if local.isIncoming && accumulatedEnough(adds) => persistAndFulfill(local, adds)
        case _ if adds.exists(tooFewBlocksUntilExpiry) => failBecome(IncomingRejected(None), adds)
        case None => failBecome(IncomingRejected(None), adds)
        case _ => // Do nothing
      }

    case (inFlight: InFlightPayments, rejected: IncomingRejected, REJECTED) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      failBecome(rejected, adds)
  }

  // Utils

  def tooFewBlocksUntilExpiry(add: ReasonableLocal): Boolean = add.packet.add.cltvExpiry.toLong < LNParams.blockCount.get + LNParams.cltvRejectThreshold

  def accumulatedEnough(adds: Iterable[ReasonableLocal] = Nil): Boolean = adds.nonEmpty && adds.map(_.packet.add.amountMsat).sum >= adds.head.packet.payload.totalAmount

  def fulfill(info: PaymentInfo, adds: Iterable[ReasonableLocal] = None): Unit = for (local <- adds) cm.sendTo(local.fulfillCommand(info.preimage), local.packet.add.channelId)

  def persistAndFulfill(info: PaymentInfo, adds: Iterable[ReasonableLocal] = None): Unit = {
    // Fulfill provided pending incoming payments and snapshot them to maybe re-fulfill later
    cm.payBag.updOkIncoming(adds.map(_.revealedPart), fullTag.paymentHash)
    cm.getPaymentDbInfoMemo.invalidate(fullTag.paymentHash)
    incomingRevealed(fullTag)
    fulfill(info, adds)
  }

  def fail(data1: IncomingRejected, adds: Iterable[ReasonableLocal] = None): Unit = data1.commonFailure match {
    case None => for (local <- adds) cm.sendTo(local.failCommand(local.packet.add.incorrectDetails), local.packet.add.channelId)
    case Some(fail) => for (local <- adds) cm.sendTo(local.failCommand(fail), local.packet.add.channelId)
  }

  def failBecome(data1: IncomingRejected, adds: Iterable[ReasonableLocal] = None): Unit = {
    // Fail provided parts and retain a failure message to maybe re-fail using the same error
    become(data1, REJECTED)
    fail(data1, adds)
  }

  def selectivelyFulfill(info: PaymentInfo, adds: Iterable[ReasonableLocal] = None): Unit = {
    // For example, we could have some incoming payment in offline channel which would have to be fulfilled once it become online
    // at the same time we could get new parts from malicious sender who is reusing an invoice and not going to pay the whole amount
    val (toFulfill, toFail) = adds.partition(add => info.revealedParts contains add.revealedPart)
    failBecome(IncomingRejected(None), toFail)
    fulfill(info, toFulfill)
  }
}
