package immortan

import immortan.fsm._
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
import fr.acinq.bitcoin.ByteVector32
import scala.util.Try


object ChannelMaster {
  type PreimageTry = Try[ByteVector32]
  type PaymentInfoTry = Try[PaymentInfo]

  type OutgoingAdds = Iterable[UpdateAddHtlc]
  type ReasonableResolutions = Iterable[ReasonableResolution]
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
    case packet: IncomingPacket.ChannelRelayPacket => CMD_FAIL_HTLC(Right(LNParams incorrectDetails packet.add.amountMsat), secret, packet.add)
    case packet: IncomingPacket.NodeRelayPacket => CMD_FAIL_HTLC(Right(LNParams incorrectDetails packet.add.amountMsat), secret, packet.add)
    case packet: IncomingPacket.FinalPacket => CMD_FAIL_HTLC(Right(LNParams incorrectDetails packet.add.amountMsat), secret, packet.add)
  }
}

case class InFlightPayments(out: Map[FullPaymentTag, OutgoingAdds], in: Map[FullPaymentTag, ReasonableResolutions] = Map.empty) {
  // Incoming HTLC tag is extracted from onion, corresponsing outgoing HTLC tag is stored in TLV, this way in/out can be linked
  val allTags: Set[FullPaymentTag] = out.keySet ++ in.keySet
}

abstract class ChannelMaster(val payBag: PaymentBag, val chanBag: ChannelBag, val dataBag: DataBag, val pf: PathFinder) extends ChannelListener with OutgoingPaymentMasterListener { me =>
  val getPaymentInfoMemo: LoadingCache[ByteVector32, PaymentInfoTry] = memoize(payBag.getPaymentInfo)
  val initResolveMemo: LoadingCache[UpdateAddHtlcExt, IncomingResolution] = memoize(initResolve)
  val getPreimageMemo: LoadingCache[ByteVector32, PreimageTry] = memoize(payBag.getPreimage)

  val sockBrandingBridge: ConnectionListener
  val sockChannelBridge: ConnectionListener

  val opm: OutgoingPaymentMaster = new OutgoingPaymentMaster(me)
  val connectionListeners = Set(sockBrandingBridge, sockChannelBridge)
  var inProcessors = Map.empty[FullPaymentTag, IncomingPaymentProcessor]
  var all = Map.empty[ByteVector32, Channel]

  pf.listeners += opm
  opm.listeners += me

  // Initial run to create FSMs for in-flight payments, including locally initiated outgoing payments
  notifyFSMs(allInChannelOutgoing, allIncomingResolutions, rejects = Nil, makeMissingOutgoingFSM = true)

  // CHANNEL MANAGEMENT

  def initConnect: Unit =
    all.values.filter(Channel.isOperationalOrWaiting).flatMap(Channel.chanAndCommitsOpt).map(_.commits).foreach {
      case cs: NormalCommits => CommsTower.listen(connectionListeners, cs.remoteInfo.nodeSpecificPair, cs.remoteInfo, LNParams.normInit)
      case cs: HostedCommits => CommsTower.listen(connectionListeners, cs.remoteInfo.nodeSpecificPair, cs.remoteInfo, LNParams.hcInit)
      case _ => throw new RuntimeException
    }

  def currentLocalSentPayments: Map[FullPaymentTag, OutgoingPaymentSender] = opm.data.payments.filterKeys(_.tag == PaymentTagTlv.LOCALLY_SENT)
  def currentFinalIncomingPayments: Map[FullPaymentTag, IncomingPaymentProcessor] = inProcessors.filterKeys(_.tag == PaymentTagTlv.FINAL_INCOMING)
  def currentTrampolineRoutedPayments: Map[FullPaymentTag, IncomingPaymentProcessor] = inProcessors.filterKeys(_.tag == PaymentTagTlv.TRAMPLOINE_ROUTED)

  def allIncomingResolutions: Iterable[IncomingResolution] = all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.crossSignedIncoming).map(initResolveMemo.get)
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
    // Subtract max send fee from EACH channel since ANY channel MAY use all of it
    opm.getSendable(chans, maxFee = sendableNoFee * LNParams.offChainFeeRatio).values.sum
  }

  def checkIfSendable(tag: FullPaymentTag, amount: MilliSatoshi): Int = opm.data.payments.get(tag) match {
    case Some(outgoingFSM) if PENDING == outgoingFSM.state || INIT == outgoingFSM.state => PaymentInfo.NOT_SENDABLE_IN_FLIGHT // This payment is pending in FSM
    case Some(outgoingFSM) if SUCCEEDED == outgoingFSM.state => PaymentInfo.NOT_SENDABLE_SUCCESS // This payment has just been fulfilled at runtime
    case _ if getPreimageMemo(tag.paymentHash).isSuccess => PaymentInfo.NOT_SENDABLE_SUCCESS // Preimage is revealed
    case _ if amount > maxSendable => PaymentInfo.NOT_SENDABLE_LOW_FUNDS // Not enough funds in a wallet
    case _ => PaymentInfo.SENDABLE // Has never been sent or ABORTED by now
  }

  def notifyFSMs(out: Map[FullPaymentTag, OutgoingAdds], in: Iterable[IncomingResolution], rejects: Seq[RemoteReject], makeMissingOutgoingFSM: Boolean): Unit = {
    in.foreach { case finalResolve: FinalResolution => sendTo(finalResolve, finalResolve.theirAdd.channelId) case _ => } // First, immediately resolve invalid adds
    val partialIncoming = in.collect { case resolve: ReasonableResolution => resolve }.groupBy(_.fullTag) // Then, collect reasonable adds which need further analysis
    val bag = InFlightPayments(out, partialIncoming)

    bag.allTags.foreach {
      case fullTag if PaymentTagTlv.TRAMPLOINE_ROUTED == fullTag.tag && !inProcessors.contains(fullTag) => inProcessors += new TrampolinePaymentRelayer(fullTag, me).tuple
      case fullTag if PaymentTagTlv.FINAL_INCOMING == fullTag.tag && !inProcessors.contains(fullTag) => inProcessors += new IncomingPaymentReceiver(fullTag, me).tuple
      case fullTag if PaymentTagTlv.LOCALLY_SENT == fullTag.tag && makeMissingOutgoingFSM => opm process CreateSenderFSM(fullTag)
      case _ => // Do nothing
    }

    // Incoming FSM may have been created, but now no related incoming or outgoing payments are left
    // this is used by incoming FSMs to finalize themselves including removal from `inProcessors` map
    inProcessors.values.foreach(_ doProcess bag)
    rejects.foreach(opm.process)
  }

  // These are executed in Channel context

  override def onException: PartialFunction[Malfunction, Unit] = { case (_, error: CMDException) => opm process error }

  override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, _, SLEEPING | SUSPENDED, OPEN) => opm process OutgoingPaymentMaster.CMDChanGotOnline }

  override def stateUpdated(rejects: Seq[RemoteReject] = Nil): Unit = notifyFSMs(allInChannelOutgoing, allIncomingResolutions, rejects, makeMissingOutgoingFSM = false)

  override def fulfillReceived(fulfill: RemoteFulfill): Unit = opm process fulfill

  // Mainly to prolong timeouts
  override def addReceived(add: UpdateAddHtlcExt): Unit = initResolveMemo(add) match {
    case resolve: ReasonableTrampoline => inProcessors.values.find(_.fullTag == resolve.fullTag).foreach(_ doProcess resolve)
    case resolve: ReasonableLocal => inProcessors.values.find(_.fullTag == resolve.fullTag).foreach(_ doProcess resolve)
    case _ => // Do nothing
  }

  // These are executed in OutgoingPaymentMaster context
  // Currently local outgoing FSM stays in memory forever on becoming SUCCEEDED/ABORTED
  // this is harmless because in-flight routes are getting cleared as payment is winding down

  // Only treat local payments here, relays are handled in trampoline FSM
  override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = if (data.cmd.fullTag.tag == PaymentTagTlv.LOCALLY_SENT) {
    if (data.failures.nonEmpty) dataBag.putReport(data.cmd.fullTag.paymentHash, data failuresAsString LNParams.denomination)
    payBag.updAbortedOutgoing(data.cmd.fullTag.paymentHash)
  }

  override def preimageRevealed(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill): Unit = {
    // We have just seen a first preimage for locally initiated OR trampoline-routed outgoing payment

    chanBag.db txWrap {
      // First, unconditionally persist a preimage before doing anything else
      payBag.addPreimage(fulfill.ourAdd.paymentHash, fulfill.preimage)
      // Should be silently disregarded if this is a routed payment
      payBag.updOkOutgoing(fulfill, data.usedFee)
    }

    if (data.cmd.fullTag.tag == PaymentTagTlv.LOCALLY_SENT) chanBag.db txWrap {
      // Then, persist various metadata when this is a locally initiated payment
      payBag.getPaymentInfo(fulfill.ourAdd.paymentHash).foreach { outgoingPaymentInfo =>
        payBag.addSearchablePayment(outgoingPaymentInfo.description.queryText, fulfill.ourAdd.paymentHash)
        if (data.inFlightParts.nonEmpty) dataBag.putReport(fulfill.ourAdd.paymentHash, data usedRoutesAsString LNParams.denomination)
        for (successfullUpdateExt <- data.successfulUpdates) pf.normalStore.incrementChannelScore(successfullUpdateExt.update)
      }
    }

    getPaymentInfoMemo.invalidate(fulfill.ourAdd.paymentHash)
    getPreimageMemo.invalidate(fulfill.ourAdd.paymentHash)
  }
}
