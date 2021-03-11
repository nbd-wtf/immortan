package immortan

import fr.acinq.eclair._
import immortan.Channel._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import immortan.PaymentStatus._
import immortan.ChannelMaster._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteReject}
import immortan.ChannelListener.{Malfunction, Transition}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import immortan.crypto.{CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.payment.IncomingPacket
import com.google.common.cache.LoadingCache
import immortan.fsm.OutgoingPaymentMaster
import fr.acinq.bitcoin.ByteVector32
import scala.util.Try


object ChannelMaster {
  type PreimageTry = Try[ByteVector32]
  type PaymentInfoTry = Try[PaymentInfo]

  type OutgoingAdds = Iterable[UpdateAddHtlc]
  type UndeterminedResolutions = Iterable[UndeterminedResolution]
  type ReasonableTrampolines = Iterable[ReasonableTrampoline]
  type ReasonableLocals = Iterable[ReasonableLocal]

  final val NO_CHANNEL =
    new StateMachine[ChannelData] with CanBeRepliedTo {
      // It's possible that user removes an HC from system at runtime
      // or that peer sends a message targeted to non-exiting local channel
      def process(change: Any): Unit = doProcess(change)
      def doProcess(change: Any): Unit = none
    }

  final val NO_SECRET = ByteVector32.Zeroes

  final val NO_PREIMAGE = ByteVector32.One

  def initResolve(ext: UpdateAddHtlcExt): IncomingResolution = IncomingPacket.decrypt(ext.theirAdd, ext.remoteInfo.nodeSpecificPrivKey) match {
    case Left(_: BadOnion) => fallbackResolve(secret = LNParams.format.keys.fakeInvoiceKey(ext.theirAdd.paymentHash), ext.theirAdd)
    case Left(onionFailure) => CMD_FAIL_HTLC(Right(onionFailure), ext.remoteInfo.nodeSpecificPrivKey, ext.theirAdd)
    case Right(packet: IncomingPacket) => defineResolution(ext.remoteInfo.nodeSpecificPrivKey, packet)
  }

  def fallbackResolve(secret: PrivateKey, theirAdd: UpdateAddHtlc): IncomingResolution = IncomingPacket.decrypt(theirAdd, secret) match {
    case Left(failure: BadOnion) => CMD_FAIL_MALFORMED_HTLC(failure.onionHash, failureCode = failure.code, theirAdd)
    case Left(onionFailure) => CMD_FAIL_HTLC(Right(onionFailure), secret, theirAdd)
    case Right(packet: IncomingPacket) => defineResolution(secret, packet)
  }

  // Make sure incoming payment secret is always present
  def defineResolution(secret: PrivateKey, pkt: IncomingPacket): IncomingResolution = pkt match {
    case packet: IncomingPacket.FinalPacket if packet.payload.paymentSecret.exists(_ != NO_SECRET) => ReasonableLocal(packet, secret)
    case packet: IncomingPacket.NodeRelayPacket if packet.outerPayload.paymentSecret.exists(_ != NO_SECRET) => ReasonableTrampoline(packet, secret)
    case packet: IncomingPacket.ChannelRelayPacket => CMD_FAIL_HTLC(Right(packet.add.incorrectDetails), secret, packet.add)
    case packet: IncomingPacket.NodeRelayPacket => CMD_FAIL_HTLC(Right(packet.add.incorrectDetails), secret, packet.add)
    case packet: IncomingPacket.FinalPacket => CMD_FAIL_HTLC(Right(packet.add.incorrectDetails), secret, packet.add)
  }
}

case class InFlightPayments(out: Map[FullPaymentTag, OutgoingAdds], in: Map[FullPaymentTag, UndeterminedResolutions] = Map.empty) {
  // Incoming HTLC tag is extracted from onion, corresponsing outgoing HTLC tag is stored in TLV, this way in/out can be linked
  val allTags: Set[FullPaymentTag] = out.keySet ++ in.keySet
}

abstract class ChannelMaster(val payBag: PaymentBag, val chanBag: ChannelBag, val pf: PathFinder) extends ChannelListener { me =>
  val getPaymentInfoMemo: LoadingCache[ByteVector32, PaymentInfoTry] = memoize(payBag.getPaymentInfo)
  val initResolveMemo: LoadingCache[UpdateAddHtlcExt, IncomingResolution] = memoize(initResolve)
  val getPreimageMemo: LoadingCache[ByteVector32, PreimageTry] = memoize(payBag.getPreimage)

  val sockBrandingBridge: ConnectionListener
  val sockChannelBridge: ConnectionListener

  val opm: OutgoingPaymentMaster = new OutgoingPaymentMaster(me)
  val connectionListeners = Set(sockBrandingBridge, sockChannelBridge)
  var all = Map.empty[ByteVector32, Channel]

  // CHANNEL MANAGEMENT

  def initConnect: Unit =
    all.values.filter(Channel.isOperationalOrWaiting).flatMap(Channel.chanAndCommitsOpt).map(_.commits).foreach {
      case cs: NormalCommits => CommsTower.listen(connectionListeners, cs.remoteInfo.nodeSpecificPair, cs.remoteInfo, LNParams.normInit)
      case cs: HostedCommits => CommsTower.listen(connectionListeners, cs.remoteInfo.nodeSpecificPair, cs.remoteInfo, LNParams.hcInit)
      case _ => throw new RuntimeException
    }

  def allInChannelOutgoing: Map[FullPaymentTag, OutgoingAdds] = all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing).groupBy(_.fullTag)
  def fromNode(nodeId: PublicKey): Iterable[ChanAndCommits] = all.values.flatMap(Channel.chanAndCommitsOpt).filter(_.commits.remoteInfo.nodeId == nodeId)
  def sendTo(change: Any, chanId: ByteVector32): Unit = all.getOrElse(chanId, NO_CHANNEL) process change

  // RECEIVE/SEND UTILITIES

  def maxReceivable: Option[CommitsAndMax] = {
    val canReceive = all.values.filter(Channel.isOperational).flatMap(Channel.chanAndCommitsOpt).filter(_.commits.updateOpt.isDefined).toList.sortBy(_.commits.availableForReceive)
    // Example: (5, 50, 60, 100) -> (50, 60, 100), receivable = 50*3 = 150 (the idea is for smallest remaining operational channel to be able to handle an evenly split amount)
    val withoutSmall = canReceive.dropWhile(_.commits.availableForReceive * canReceive.size < canReceive.last.commits.availableForReceive).takeRight(4)
    val candidates = for (cs <- withoutSmall.indices map withoutSmall.drop) yield CommitsAndMax(cs, cs.head.commits.availableForReceive * cs.size)
    if (candidates.isEmpty) None else candidates.maxBy(_.maxReceivable).toSome
  }

  def maxSendable: MilliSatoshi = {
    val chans = all.values.filter(Channel.isOperational)
    val sendableNoFee = opm.getSendable(chans, maxFee = 0L.msat).values.sum
    // Subtract max send fee from each channel since each channel may use all of it
    opm.getSendable(chans, maxFee = sendableNoFee * LNParams.offChainFeeRatio).values.sum
  }

  def checkIfSendable(tag: FullPaymentTag, amount: MilliSatoshi): Int = getPreimageMemo(tag.paymentHash) match {
    case _ if opm.data.payments.get(tag).exists(fsm => PENDING == fsm.state || INIT == fsm.state) => PaymentInfo.NOT_SENDABLE_IN_FLIGHT // This payment is pending in FSM
    case _ if opm.data.payments.get(tag).exists(fsm => SUCCEEDED == fsm.state) => PaymentInfo.NOT_SENDABLE_SUCCESS // This payment has just been fulfilled at runtime
    case _ if amount > maxSendable => PaymentInfo.NOT_SENDABLE_LOW_FUNDS // Not enough funds in a wallet
    case info if info.isSuccess => PaymentInfo.NOT_SENDABLE_SUCCESS // Preimage is revealed
    case _ => PaymentInfo.SENDABLE // Has never been sent or ABORTED by now
  }

  // These are executed in Channel context

  override def onException: PartialFunction[Malfunction, Unit] = { case (_, commandError: CMDException) => opm process commandError }

  override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, _, SLEEPING | SUSPENDED, OPEN) => opm process OutgoingPaymentMaster.CMDChanGotOnline }

  override def stateUpdated(rejects: Seq[RemoteReject] = Nil): Unit = {
    val allIncomingResolves = all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.crossSignedIncoming).map(initResolveMemo.get)
    allIncomingResolves.foreach { case finalResolve: FinalResolution => sendTo(finalResolve, finalResolve.theirAdd.channelId) case _ => }
    val partialIncoming = allIncomingResolves.collect { case resolve: UndeterminedResolution => resolve }.groupBy(_.fullTag)
    val bag = InFlightPayments(allInChannelOutgoing, partialIncoming)
  }

  override def fulfillReceived(fulfill: RemoteFulfill): Unit = {
    // Anything which matches hash should be notified, tag is not important
    opm process fulfill
  }

  override def addReceived(add: UpdateAddHtlcExt): Unit = ???
}
