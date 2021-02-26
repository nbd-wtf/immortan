package immortan.payment

import immortan.crypto.Tools._
import com.google.common.cache.LoadingCache
import immortan.payment.IncomingPaymentReceiver._
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.IncomingPacket
import fr.acinq.eclair.wire._
import immortan._
import immortan.crypto.StateMachine
import scodec.bits.ByteVector


object IncomingPaymentReceiver {
  val AWAITING = "receiver-awaiting"
  val REVEALED = "receiver-revealed"
  val REJECTED = "receiver-rejected"

  def isRealPreimage(preimage: ByteVector32): Boolean = preimage != ByteVector32.Zeroes

  // Right(preimage) is can be fulfilled, Left(false) is should be failed, Left(true) is keep waiting
  def decideOnFinal(adds: List[ReasonableFinal], info: PaymentDbInfo): Either[Boolean, ByteVector32] = info match {
    case _ if adds.exists(_.packet.add.cltvExpiry.toLong < LNParams.blockCount.get + LNParams.cltvRejectThreshold) => Left(false)
    case PaymentDbInfo(Some(local), _, _) if !adds.flatMap(_.packet.payload.paymentSecret).forall(local.pr.paymentSecret.contains) => Left(false)
    case PaymentDbInfo(Some(local), _, _) if local.pr.amount.forall(requestedAmount => adds.map(_.packet.payload.totalAmount).min < requestedAmount) => Left(false)
    case PaymentDbInfo(Some(local), _, _) if adds.map(_.packet.add.amountMsat).sum >= adds.head.packet.payload.totalAmount && isRealPreimage(local.preimage) => Right(local.preimage)
    case PaymentDbInfo(_, Some(relayed), _) => Right(relayed.preimage)
    case info => Left(info.local.isEmpty)
  }
}

trait IncomingPaymentReceiverData

case class IncomingRejected(failure: FailureMessage) extends IncomingPaymentReceiverData

case class IncomingRevealed(preimage: ByteVector32) extends IncomingPaymentReceiverData

abstract class IncomingPaymentReceiver(paymentType: PaymentType, cm: ChannelMaster) extends StateMachine[IncomingPaymentReceiverData] {
  def incomingFinalized(paymentType: PaymentType, status: String)
  def incomingRevealed(paymentType: PaymentType)

  cm getPaymentDbInfoMemo paymentType.paymentHash match {
    case PaymentDbInfo(Some(local), _, _) if local.isIncoming && PaymentStatus.SUCCEEDED == local.status => become(IncomingRevealed(local.preimage), REVEALED)
    case PaymentDbInfo(Some(local), _, _) if !local.isIncoming && isRealPreimage(local.preimage) => become(IncomingRevealed(local.preimage), REVEALED)
    case PaymentDbInfo(_, Some(relayed), _) => become(IncomingRevealed(relayed.preimage), REVEALED)
    case _ => become(null, AWAITING)
  }
}
