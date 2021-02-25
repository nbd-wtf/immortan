package immortan

import fr.acinq.eclair._
import immortan.Channel._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import immortan.PaymentStatus._
import fr.acinq.eclair.channel._
import immortan.payment.{OutgoingPaymentMaster, OutgoingPaymentSenderData}
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteReject}
import immortan.ChannelListener.{Malfunction, Transition}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.payment.IncomingPacket
import com.google.common.cache.LoadingCache
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.payment.PaymentRequest.PaymentHash
import scodec.bits.ByteVector


object ChannelMaster {
  def incorrectDetails(add: UpdateAddHtlc): Either[ByteVector, FailureMessage] = {
    val failure = IncorrectOrUnknownPaymentDetails(add.amountMsat, LNParams.blockCount.get)
    Right(failure)
  }

  def initResolve(payment: UpdateAddHtlcExt): IncomingResolution = IncomingPacket.decrypt(payment.theirAdd, payment.remoteInfo.nodeSpecificPrivKey) match {
    case Left(_: BadOnion) => fallbackResolve(LNParams.format.keys.fakeInvoiceKey(payment.theirAdd.paymentHash), payment.theirAdd)
    case Left(failure) => CMD_FAIL_HTLC(Right(failure), payment.remoteInfo.nodeSpecificPrivKey, payment.theirAdd.id)
    case Right(packet: IncomingPacket) => defineResolution(payment.remoteInfo.nodeSpecificPrivKey, packet)
  }

  def fallbackResolve(secret: PrivateKey, theirAdd: UpdateAddHtlc): IncomingResolution = IncomingPacket.decrypt(theirAdd, secret) match {
    case Left(failure: BadOnion) => CMD_FAIL_MALFORMED_HTLC(failure.onionHash, failure.code, theirAdd.id)
    case Left(failure) => CMD_FAIL_HTLC(Right(failure), secret, theirAdd.id)
    case Right(packet: IncomingPacket) => defineResolution(secret, packet)
  }

  def defineResolution(secret: PrivateKey, pkt: IncomingPacket): IncomingResolution = pkt match {
    case packet: IncomingPacket.ChannelRelayPacket => CMD_FAIL_HTLC(incorrectDetails(packet.add), secret, packet.add.id)
    case packet: IncomingPacket.NodeRelayPacket if packet.outerPayload.paymentSecret.nonEmpty => ReasonableTrampoline(packet)
    case packet: IncomingPacket.NodeRelayPacket => CMD_FAIL_HTLC(incorrectDetails(packet.add), secret, packet.add.id)
    case packet: IncomingPacket.FinalPacket => ReasonableFinal(packet)
  }
}

abstract class ChannelMaster(val payBag: PaymentBag, val chanBag: ChannelBag, val pf: PathFinder) extends ChannelListener { me =>
  val sockBrandingBridge: ConnectionListener
  val sockChannelBridge: ConnectionListener
  val opm: OutgoingPaymentMaster

  val connectionListeners = Set(sockBrandingBridge, sockChannelBridge)
  var paymentListeners = Set.empty[ChannelMasterListener]
  var all = List.empty[Channel]

  val initResolveMemo: LoadingCache[UpdateAddHtlcExt, IncomingResolution] = memoize(ChannelMaster.initResolve)
  val getPaymentDbInfoMemo: LoadingCache[ByteVector32, PaymentDbInfo] = memoize(getPaymentDbInfo)

  // CHANNEL MANAGEMENT

  def initConnect: Unit = all.filter(Channel.isOperationalOrWaiting).flatMap(Channel.chanAndCommitsOpt).map(_.commits).foreach {
    case cs: HostedCommits => CommsTower.listen(connectionListeners, cs.remoteInfo.nodeSpecificPair, cs.remoteInfo, LNParams.hcInit)
    case cs: NormalCommits => CommsTower.listen(connectionListeners, cs.remoteInfo.nodeSpecificPair, cs.remoteInfo, LNParams.normInit)
    case _ => throw new RuntimeException
  }

  def allInChanOutgoingHtlcs: Seq[UpdateAddHtlc] = all.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing)
  def allInChanCrossSignedIncomingHtlcs: Seq[UpdateAddHtlcExt] = all.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.crossSignedIncoming)
  def fromNode(nodeId: PublicKey): Seq[ChanAndCommits] = all.flatMap(Channel.chanAndCommitsOpt).filter(_.commits.remoteInfo.nodeId == nodeId)

  // RECEIVE/SEND UTILITIES

  def getPaymentDbInfo(paymentHash: ByteVector32): PaymentDbInfo = {
    val inRelayDb = payBag.getRelayedPreimageInfo(paymentHash)
    val inPaymentDb = payBag.getPaymentInfo(paymentHash)
    PaymentDbInfo(inPaymentDb, inRelayDb, paymentHash)
  }

  // Right(preimage) is can be fulfilled, Left(false) is should be failed, Left(true) is keep waiting
  def decideOnFinal(adds: List[ReasonableFinal], info: PaymentDbInfo): Either[Boolean, ByteVector32] = info match {
    case _ if adds.exists(_.packet.add.cltvExpiry.toLong < LNParams.blockCount.get + LNParams.cltvRejectThreshold) => Left(false)
    case PaymentDbInfo(Some(local), _, _) if !adds.flatMap(_.packet.payload.paymentSecret).forall(local.pr.paymentSecret.contains) => Left(false)
    case PaymentDbInfo(Some(local), _, _) if local.pr.amount.forall(requestedAmount => adds.map(_.packet.payload.totalAmount).min < requestedAmount) => Left(false)
    case PaymentDbInfo(Some(local), _, _) if adds.map(_.packet.add.amountMsat).sum >= adds.head.packet.payload.totalAmount && local.preimage != ByteVector32.Zeroes => Right(local.preimage)
    case PaymentDbInfo(_, Some(relayed), _) => Right(relayed.preimage)
    case info => Left(info.local.isEmpty)
  }

//  def decideOnTrampoline(adds: List[ReasonableTrampoline] = Nil): Option[Boolean] = {
//
//    case _ if adds.exists(_.packet.innerPayload.outgoingCltv) => Some(false)
//    case _ if adds.exists(_.packet.outerPayload.paymentSecret.isEmpty) => Left(false)
//    case _ if adds.flatMap(_.packet.outerPayload.paymentSecret).toSet.size > 1 => Left(false)
//    case _ if adds.map(_.packet.outerPayload.totalAmount).toSet.size > 1 => Left(false)
//  }

  def maxReceivableInfo: Option[CommitsAndMax] = {
    val canReceive = all.filter(Channel.isOperational).flatMap(Channel.chanAndCommitsOpt).filter(_.commits.updateOpt.isDefined).sortBy(_.commits.availableBalanceForReceive)
    // Example: (5, 50, 60, 100) -> (50, 60, 100), receivable = 50*3 = 150 (the idea is for smallest remaining operational channel to be able to handle an evenly split amount)
    val withoutSmall = canReceive.dropWhile(_.commits.availableBalanceForReceive * canReceive.size < canReceive.last.commits.availableBalanceForReceive).takeRight(4)
    val candidates = for (cs <- withoutSmall.indices map withoutSmall.drop) yield CommitsAndMax(cs, cs.head.commits.availableBalanceForReceive * cs.size)
    if (candidates.isEmpty) None else candidates.maxBy(_.maxReceivable).toSome
  }

  def checkIfSendable(paymentType: PaymentType, amount: MilliSatoshi): Int = getPaymentDbInfoMemo(paymentType.paymentHash) match {
    case _ if opm.data.payments.get(paymentType).exists(fsm => SUCCEEDED == fsm.state) => PaymentInfo.NOT_SENDABLE_SUCCESS // This payment has just been fulfilled at runtime
    case _ if opm.data.payments.get(paymentType).exists(fsm => PENDING == fsm.state || INIT == fsm.state) => PaymentInfo.NOT_SENDABLE_IN_FLIGHT // This payment is pending in FSM
    case _ if allInChanOutgoingHtlcs.exists(_.paymentHash == paymentType.paymentHash) => PaymentInfo.NOT_SENDABLE_IN_FLIGHT // This payment is still pending in channels
    case _ if opm.inPrincipleSendable(all, LNParams.routerConf) < amount => PaymentInfo.NOT_SENDABLE_LOW_BALANCE // We don't have enough money to send this one
    case info if info.local.exists(SUCCEEDED == _.status) => PaymentInfo.NOT_SENDABLE_SUCCESS // Successfully sent or received a long time ago
    case info if info.local.exists(_.isIncoming) => PaymentInfo.NOT_SENDABLE_INCOMING // Incoming payment with this hash exists
    case info if info.relayed.isDefined => PaymentInfo.NOT_SENDABLE_RELAYED // Related preimage has been relayed
    case _ => PaymentInfo.SENDABLE // Has never been sent or ABORTED by now
  }

  // These are executed in Channel context

  override def onBecome: PartialFunction[Transition, Unit] = {
    case (_, _, _: Commitments, SLEEPING | SUSPENDED, OPEN) =>
      opm process OutgoingPaymentMaster.CMDChanGotOnline
  }

  override def onException: PartialFunction[Malfunction, Unit] = { case (_, error: CMDException) => opm process error }

  override def stateUpdated(rejects: Seq[RemoteReject] = Nil): Unit = rejects.foreach(opm.process)

  override def fulfillReceived(fulfill: RemoteFulfill): Unit = opm process fulfill
}

trait ChannelMasterListener {
  def outgoingFailed(data: OutgoingPaymentSenderData): Unit = none
  // Note that it is theoretically possible for first part to get fulfilled and the rest of the parts to get failed
  def outgoingSucceeded(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill, isFirst: Boolean, noLeftovers: Boolean): Unit = none
}