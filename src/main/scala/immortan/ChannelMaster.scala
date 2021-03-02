package immortan

import fr.acinq.eclair._
import immortan.Channel._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import immortan.PaymentStatus._
import immortan.ChannelMaster._
import fr.acinq.eclair.channel._
import immortan.payment.{OutgoingPaymentMaster, OutgoingPaymentSenderData}
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteReject}
import immortan.ChannelListener.{Malfunction, Transition}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.payment.IncomingPacket
import com.google.common.cache.LoadingCache
import fr.acinq.bitcoin.ByteVector32
import immortan.crypto.{CanBeRepliedTo, StateMachine}
import scodec.bits.ByteVector


object ChannelMaster { me =>
  type OutgoingAdds = Iterable[UpdateAddHtlc]
  type UndeterminedResolutions = Iterable[UndeterminedResolution]
  type ReasonableTrampolines = Iterable[ReasonableTrampoline]
  type ReasonableLocals = Iterable[ReasonableLocal]

  final val NO_CHANNEL =
    new StateMachine[ChannelData] with CanBeRepliedTo {
      // It's possible that user removes an HC from system at runtime
      // or that peer sends a message targeted to non-exiting loca channel
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
  val getPaymentDbInfoMemo: LoadingCache[ByteVector32, PaymentDbInfo] = memoize(getPaymentDbInfo)
  val initResolveMemo: LoadingCache[UpdateAddHtlcExt, IncomingResolution] = memoize(initResolve)

  val sockBrandingBridge: ConnectionListener
  val sockChannelBridge: ConnectionListener
  val opm: OutgoingPaymentMaster

  val connectionListeners = Set(sockBrandingBridge, sockChannelBridge)
  var paymentListeners = Set.empty[ChannelMasterListener]
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

  def getPaymentDbInfo(paymentHash: ByteVector32): PaymentDbInfo = {
    val inRelayDb = payBag.getRelayedPreimageInfo(paymentHash)
    val inPaymentDb = payBag.getPaymentInfo(paymentHash)
    PaymentDbInfo(inPaymentDb, inRelayDb, paymentHash)
  }

  def maxReceivableInfo: Option[CommitsAndMax] = {
    val canReceive = all.values.filter(Channel.isOperational).flatMap(Channel.chanAndCommitsOpt).filter(_.commits.updateOpt.isDefined).toList.sortBy(_.commits.availableBalanceForReceive)
    // Example: (5, 50, 60, 100) -> (50, 60, 100), receivable = 50*3 = 150 (the idea is for smallest remaining operational channel to be able to handle an evenly split amount)
    val withoutSmall = canReceive.dropWhile(_.commits.availableBalanceForReceive * canReceive.size < canReceive.last.commits.availableBalanceForReceive).takeRight(4)
    val candidates = for (cs <- withoutSmall.indices map withoutSmall.drop) yield CommitsAndMax(cs, cs.head.commits.availableBalanceForReceive * cs.size)
    if (candidates.isEmpty) None else candidates.maxBy(_.maxReceivable).toSome
  }

  def checkIfSendable(fullTag: FullPaymentTag, amount: MilliSatoshi): Int =
    getPaymentDbInfoMemo(fullTag.paymentHash) match {
      case _ if allInChannelOutgoing.contains(fullTag) => PaymentInfo.NOT_SENDABLE_IN_FLIGHT // This payment type is still pending in channels (routing and sending at once is OK)
      case _ if opm.data.payments.get(fullTag).exists(fsm => PENDING == fsm.state || INIT == fsm.state) => PaymentInfo.NOT_SENDABLE_IN_FLIGHT // This payment is pending in FSM
      case _ if opm.data.payments.get(fullTag).exists(fsm => SUCCEEDED == fsm.state) => PaymentInfo.NOT_SENDABLE_SUCCESS // This payment has just been fulfilled at runtime
      case _ if opm.inPrincipleSendable(all.values, LNParams.routerConf) < amount => PaymentInfo.NOT_SENDABLE_LOW_FUNDS // We don't have enough money
      case info if info.localOpt.exists(SUCCEEDED == _.status) => PaymentInfo.NOT_SENDABLE_SUCCESS // Successfully sent or received a long time ago
      case info if info.localOpt.exists(_.isIncoming) => PaymentInfo.NOT_SENDABLE_INCOMING // Incoming payment with this hash exists
      case info if info.relayedOpt.isDefined => PaymentInfo.NOT_SENDABLE_RELAYED // Related preimage has been relayed
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

  override def fulfillReceived(fulfill: RemoteFulfill): Unit = opm process fulfill
}

trait ChannelMasterListener {
  def outgoingFailed(data: OutgoingPaymentSenderData): Unit = none
  // Note that it is theoretically possible for first part to get fulfilled and the rest of the parts to get failed
  def outgoingSucceeded(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill, isFirst: Boolean): Unit = none
  // This one is called after preimage has been revealed and no more outgoing payment parts are left
  def outgoingFinalized(data: OutgoingPaymentSenderData): Unit = none
}