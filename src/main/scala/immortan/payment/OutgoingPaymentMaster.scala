package immortan.payment

import java.util.concurrent.Executors

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CMDException, CMD_ADD_HTLC}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Graph.GraphStructure.{DescAndCapacity, GraphEdge}
import immortan.{ChanAndCommits, Channel, ChannelListener, LNParams, PathFinder}
import fr.acinq.eclair.router.Router.{ChannelDesc, Route, RouteRequest, RouteResponse, RouterConf}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import fr.acinq.eclair.wire.{ChannelUpdate, OnionTlv, PaymentType, UpdateFulfillHtlc}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.router.ChannelUpdateExt
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteReject}
import immortan.ChannelListener.{Malfunction, Transition}
import immortan.PaymentStatus.{ABORTED, SUCCEEDED}
import immortan.crypto.Tools.mapKeys
import immortan.crypto.{CanBeRepliedTo, StateMachine}
import immortan.payment.OutgoingPaymentMaster._
import immortan.payment.PaymentFailure.Failures
import immortan.utils.Denomination
import immortan.Channel._
import immortan.PaymentStatus._
import scodec.bits.ByteVector

import scala.collection.mutable

// Remote failures

object PaymentFailure {
  type Failures = List[PaymentFailure]
  final val NOT_ENOUGH_CAPACITY = "not-enough-capacity"
  final val RUN_OUT_OF_RETRY_ATTEMPTS = "run-out-of-retry-attempts"
  final val PEER_COULD_NOT_PARSE_ONION = "peer-could-not-parse-onion"
  final val NOT_RETRYING_NO_DETAILS = "not-retrying-no-details"
}

sealed trait PaymentFailure {
  def asString(denom: Denomination): String
}

case class LocalFailure(status: String, amount: MilliSatoshi) extends PaymentFailure {
  override def asString(denom: Denomination): String = s"-- Local failure: $status"
}

case class UnreadableRemoteFailure(route: Route) extends PaymentFailure {
  override def asString(denom: Denomination): String = s"-- Remote failure at unknown channel: ${route asString denom}"
}

case class RemoteFailure(packet: Sphinx.DecryptedFailurePacket, route: Route) extends PaymentFailure {
  def originChannelId: String = route.getEdgeForNode(packet.originNode).map(_.updExt.update.shortChannelId.toString).getOrElse("Trampoline")
  override def asString(denom: Denomination): String = s"-- ${packet.failureMessage.message} @ $originChannelId: ${route asString denom}"
}

// Master commands and data

case class SplitIntoHalves(amount: MilliSatoshi)
case class NodeFailed(failedNodeId: PublicKey, increment: Int)
case class ChannelFailed(failedDescAndCap: DescAndCapacity, increment: Int)

case class SendMultiPart(paymentType: PaymentType, routerConf: RouterConf, targetNodeId: PublicKey, totalAmount: MilliSatoshi = 0L.msat,
                         paymentSecret: ByteVector32 = ByteVector32.Zeroes, targetExpiry: CltvExpiry = CltvExpiry(0), allowedChans: Seq[Channel] = Nil,
                         assistedEdges: Set[GraphEdge] = Set.empty, onionTlvs: Seq[OnionTlv] = Nil)

case class OutgoingPaymentMasterData(payments: Map[PaymentType, OutgoingPaymentSender],
                                     chanFailedAtAmount: Map[ChannelDesc, MilliSatoshi] = Map.empty withDefaultValue Long.MaxValue.msat,
                                     nodeFailedWithUnknownUpdateTimes: Map[PublicKey, Int] = Map.empty withDefaultValue 0,
                                     chanFailedTimes: Map[ChannelDesc, Int] = Map.empty withDefaultValue 0) {

  def withFailuresReduced: OutgoingPaymentMasterData =
    copy(nodeFailedWithUnknownUpdateTimes = nodeFailedWithUnknownUpdateTimes.mapValues(_ / 2),
      chanFailedTimes = chanFailedTimes.mapValues(_ / 2), chanFailedAtAmount = Map.empty)
}

// All current outgoing in-flight payments

object OutgoingPaymentMaster {
  type PartIdToAmount = Map[ByteVector, MilliSatoshi]
  val EXPECTING_PAYMENTS = "state-expecting-payments"
  val WAITING_FOR_ROUTE = "state-waiting-for-route"

  val CMDClearFailHistory = "cmd-clear-fail-history"
  val CMDChanGotOnline = "cmd-chan-got-online"
  val CMDAskForRoute = "cmd-ask-for-route"
}

class OutgoingPaymentMaster(pf: PathFinder) extends StateMachine[OutgoingPaymentMasterData] with CanBeRepliedTo { self =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(self doProcess changeMessage)
  become(OutgoingPaymentMasterData(payments = Map.empty), EXPECTING_PAYMENTS)

  def doProcess(change: Any): Unit = (change, state) match {
    case (CMDClearFailHistory, _) if data.payments.values.forall(fsm => SUCCEEDED == fsm.state || ABORTED == fsm.state) =>
      // This should be sent BEFORE sending another payment IF there are no active payments currently
      become(data.withFailuresReduced, state)

    case (cmd: SendMultiPart, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      for (assistedEdge <- cmd.assistedEdges) pf process assistedEdge
      relayOrCreateSender(cmd.paymentType, cmd)
      self process CMDAskForRoute

    case (CMDChanGotOnline, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Payments may still have awaiting parts due to offline channels
      data.payments.values.foreach(_ doProcess CMDChanGotOnline)
      self process CMDAskForRoute

    case (CMDAskForRoute | PathFinder.NotifyOperational, EXPECTING_PAYMENTS) =>
      // This is a proxy to always send command in payment master thread
      // IMPLICIT GUARD: this message is ignored in all other states
      data.payments.values.foreach(_ doProcess CMDAskForRoute)

    case (req: RouteRequest, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: ignore in other states, payment will be able to re-send later
      val currentUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = getUsedCapacities
      val currentUsedDescs = mapKeys[DescAndCapacity, MilliSatoshi, ChannelDesc](currentUsedCapacities, _.desc, defVal = 0L.msat)
      val ignoreChansFailedTimes = data.chanFailedTimes collect { case (desc, failTimes) if failTimes >= LNParams.routerConf.maxChannelFailures => desc }
      val ignoreChansCanNotHandle = currentUsedCapacities collect { case (DescAndCapacity(desc, capacity), used) if used + req.amount >= capacity - req.amount / 32 => desc }
      val ignoreChansFailedAtAmount = data.chanFailedAtAmount collect { case (desc, failedAt) if failedAt - currentUsedDescs(desc) - req.amount / 8 <= req.amount => desc }
      val ignoreNodes = data.nodeFailedWithUnknownUpdateTimes collect { case (nodeId, failTimes) if failTimes >= LNParams.routerConf.maxStrangeNodeFailures => nodeId }
      val ignoreChans = ignoreChansFailedTimes.toSet ++ ignoreChansCanNotHandle ++ ignoreChansFailedAtAmount
      val request1 = req.copy(ignoreNodes = ignoreNodes.toSet, ignoreChannels = ignoreChans)
      pf process Tuple2(self, request1)
      become(data, WAITING_FOR_ROUTE)

    case (PathFinder.NotifyRejected, WAITING_FOR_ROUTE) =>
      // Pathfinder is not yet ready, switch local state back
      // pathfinder is expected to notify us once it gets ready
      become(data, EXPECTING_PAYMENTS)

    case (response: RouteResponse, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      data.payments.get(response.paymentType).foreach(_ doProcess response)
      // Switch state to allow new route requests to come through
      become(data, EXPECTING_PAYMENTS)
      self process CMDAskForRoute

    case (ChannelFailed(descAndCapacity, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // At this point an affected InFlight status IS STILL PRESENT so failedAtAmount = sum(inFlight)
      val newChanFailedAtAmount = data.chanFailedAtAmount(descAndCapacity.desc) min getUsedCapacities(descAndCapacity)
      val atTimes1 = data.chanFailedTimes.updated(descAndCapacity.desc, data.chanFailedTimes(descAndCapacity.desc) + increment)
      val atAmount1 = data.chanFailedAtAmount.updated(descAndCapacity.desc, newChanFailedAtAmount)
      become(data.copy(chanFailedAtAmount = atAmount1, chanFailedTimes = atTimes1), state)

    case (NodeFailed(nodeId, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      val newNodeFailedTimes = data.nodeFailedWithUnknownUpdateTimes(nodeId) + increment
      val atTimes1 = data.nodeFailedWithUnknownUpdateTimes.updated(nodeId, newNodeFailedTimes)
      become(data.copy(nodeFailedWithUnknownUpdateTimes = atTimes1), state)

    case (exception @ CMDException(_, cmd: CMD_ADD_HTLC), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      data.payments.get(cmd.paymentType).foreach(_ doProcess exception)
      self process CMDAskForRoute

    case (fulfill: RemoteFulfill, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayOrCreateSender(fulfill.ourAdd.paymentType, fulfill)
      self process CMDAskForRoute

    case (reject: RemoteReject, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayOrCreateSender(reject.ourAdd.paymentType, reject)
      self process CMDAskForRoute

    case _ =>
  }

  // Utils

  def getUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = {
    // This gets supposedly used capacities of external channels in a routing graph
    // we need this to exclude channels which definitely can't route a given amount right now
    val accumulator = mutable.Map.empty[DescAndCapacity, MilliSatoshi] withDefaultValue 0L.msat
    val descsAndCaps = data.payments.values.flatMap(_.data.inFlights).flatMap(_.route.routedPerChannelHop)
    descsAndCaps.foreach { case (amount, chanHop) => accumulator(chanHop.edge.toDescAndCapacity) += amount }
    accumulator
  }

  private def relayOrCreateSender(paymentType: PaymentType, msg: Any): Unit = data.payments.get(paymentType) match {
    case None => withSender(new OutgoingPaymentSender(paymentType), msg) // Can happen after restart with leftovers in channels
    case Some(sender) => sender doProcess msg // Normal case, sender FSM is present
  }

  private def withSender(sender: OutgoingPaymentSender, msg: Any): Unit = {
    val payments1 = data.payments.updated(sender.paymentType, sender)
    become(data.copy(payments = payments1), state)
    sender doProcess msg
  }

  def inPrincipleSendable(chans: List[Channel] = Nil, conf: RouterConf): MilliSatoshi = getSendable(chans filter isOperational, conf).values.sum

  def rightNowSendable(chans: List[Channel] = Nil, conf: RouterConf): mutable.Map[ChanAndCommits, MilliSatoshi] = getSendable(chans filter isOperationalAndOpen, conf)

  // What can be sent through given channels with waiting parts taken into account
  private def getSendable(chans: List[Channel] = Nil, routerConf: RouterConf): mutable.Map[ChanAndCommits, MilliSatoshi] = {
    val finals: mutable.Map[ChanAndCommits, MilliSatoshi] = mutable.Map.empty[ChanAndCommits, MilliSatoshi] withDefaultValue 0L.msat
    val waits: mutable.Map[ByteVector32, PartIdToAmount] = mutable.Map.empty[ByteVector32, PartIdToAmount] withDefaultValue Map.empty
    // Wait part may have no route yet (but we expect a route to arrive) or it may be sent to channel but not processed by channel yet
    def waitPartsNotYetInChannel(cnc: ChanAndCommits): PartIdToAmount = waits(cnc.commits.channelId) -- cnc.commits.allOutgoing.map(_.partId)
    data.payments.values.flatMap(_.data.parts.values).collect { case wait: WaitForRouteOrInFlight => waits(wait.cnc.commits.channelId) += wait.partId -> wait.amount }
    // Example 1: chan toLocal=100, 10 in-flight AND IS present in channel already, resulting sendable = 90 (toLocal with in-flight) - 0 (in-flight - partId) = 90
    // Example 2: chan toLocal=100, 10 in-flight AND IS NOT YET preset in channel yet, resulting sendable = 100 (toLocal) - 10 (in-flight - nothing) = 90
    chans.flatMap(Channel.chanAndCommitsOpt).foreach(cnc => finals(cnc) = feeFreeBalance(cnc, routerConf) - waitPartsNotYetInChannel(cnc).values.sum)
    finals.filter { case (cnc, sendable) => sendable >= cnc.commits.minSendable }
  }

  def feeFreeBalance(cnc: ChanAndCommits, routerConf: RouterConf): MilliSatoshi = {
    // This adds maximum off-chain fee on top of next on-chain fee after another HTLC gets added
    val spaceLeft = cnc.commits.maxInFlight - cnc.commits.allOutgoing.foldLeft(0L.msat)(_ + _.amountMsat)
    val withoutBaseFee = cnc.commits.availableBalanceForSend - routerConf.searchMaxFeeBase
    val withoutAllFees = withoutBaseFee - withoutBaseFee * routerConf.searchMaxFeePct
    spaceLeft.min(withoutAllFees)
  }
}

// Individual outgoing part status

sealed trait PartStatus { me =>
  final val partId: ByteVector = onionKey.publicKey.value
  def tuple: (ByteVector, PartStatus) = (partId, me)
  def onionKey: PrivateKey
}

case class InFlightInfo(cmd: CMD_ADD_HTLC, route: Route)

case class WaitForBetterConditions(onionKey: PrivateKey, amount: MilliSatoshi) extends PartStatus

case class WaitForRouteOrInFlight(onionKey: PrivateKey, amount: MilliSatoshi, cnc: ChanAndCommits, flight: Option[InFlightInfo] = None, localFailed: List[Channel] = Nil, remoteAttempts: Int = 0) extends PartStatus {
  def oneMoreRemoteAttempt(cnc1: ChanAndCommits): WaitForRouteOrInFlight = copy(flight = None, remoteAttempts = remoteAttempts + 1, cnc = cnc1)
  def oneMoreLocalAttempt(cnc1: ChanAndCommits): WaitForRouteOrInFlight = copy(flight = None, localFailed = localFailedChans, cnc = cnc1)
  lazy val localFailedChans: List[Channel] = cnc.chan :: localFailed
}

// Individual outgoing payment status

case class OutgoingPaymentSenderData(cmd: SendMultiPart, parts: Map[ByteVector, PartStatus], failures: Failures = Nil) {
  def withRemoteFailure(route: Route, pkt: Sphinx.DecryptedFailurePacket): OutgoingPaymentSenderData = copy(failures = RemoteFailure(pkt, route) +: failures)
  def withLocalFailure(reason: String, amount: MilliSatoshi): OutgoingPaymentSenderData = copy(failures = LocalFailure(reason, amount) +: failures)
  def withoutPartId(partId: ByteVector): OutgoingPaymentSenderData = copy(parts = parts - partId)

  def inFlights: Iterable[InFlightInfo] = parts.values flatMap { case wait: WaitForRouteOrInFlight => wait.flight case _ => None }
  def successfulUpdates: Iterable[ChannelUpdateExt] = inFlights.flatMap(_.route.routedPerChannelHop).toMap.values.map(_.edge.updExt)
  def closestCltvExpiry: InFlightInfo = inFlights.minBy(_.route.weight.cltv)
  def totalFee: MilliSatoshi = inFlights.map(_.route.fee).sum

  def asString(denom: Denomination): String = {
    val failByAmount: Map[String, Failures] = failures.groupBy {
      case fail: UnreadableRemoteFailure => denom.asString(fail.route.weight.costs.head)
      case fail: RemoteFailure => denom.asString(fail.route.weight.costs.head)
      case fail: LocalFailure => denom.asString(fail.amount)
    }

    val usedRoutes = inFlights.map(_.route asString denom).mkString("\n\n")
    def translateFailures(failureList: Failures): String = failureList.map(_ asString denom).mkString("\n\n")
    val errors = failByAmount.mapValues(translateFailures).map { case (amount, fails) => s"- $amount:\n\n$fails" }.mkString("\n\n")
    s"Routes:\n\n$usedRoutes\n\n\n\nErrors:\n\n$errors"
  }
}

class OutgoingPaymentSender(val paymentType: PaymentType) extends StateMachine[OutgoingPaymentSenderData] { self =>
  become(OutgoingPaymentSenderData(SendMultiPart(paymentType, LNParams.routerConf, invalidPubKey), Map.empty), INIT)
}
