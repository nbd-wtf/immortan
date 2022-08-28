package immortan.fsm

import scala.collection.mutable
import scala.concurrent.Future
import com.softwaremill.quicklens._
import scodec.bits.ByteVector
import scoin._
import scoin.Crypto.PublicKey
import scoin.ln._

import immortan._
import immortan.fsm.OutgoingPaymentMaster._
import immortan.channel._
import immortan.router._
import immortan.router.Graph.GraphStructure.{DescAndCapacity, GraphEdge}
import immortan.router.Router.{
  ChannelDesc,
  RouterConf,
  NodeDirectionDesc,
  RouteRequest,
  RouteResponse
}

// Master commands and data
case class CutIntoHalves(amount: MilliSatoshi)
case class TrampolinePeerUpdated(from: PublicKey, status: TrampolineStatus)
case class TrampolinePeerDisconnected(from: PublicKey)

case class ChannelNotRoutable(failedDesc: ChannelDesc)
case class ChannelFailedAtAmount(failedDescAndCap: DescAndCapacity)
case class StampedChannelFailed(amount: MilliSatoshi, stamp: Long)
case class NodeFailed(failedNodeId: PublicKey, increment: Int)

case class SplitInfo(totalSum: MilliSatoshi, myPart: MilliSatoshi) {
  val sentRatio: Long = ratio(totalSum, myPart).toLong
}

// For locally initiated payments outerPaymentSecret and fullTag.paymentSecret are same
// For trampoline-routed payments fullTag.paymentSecret is taken from upstream incoming payment
case class SendMultiPart(
    fullTag: FullPaymentTag,
    chainExpiry: Either[CltvExpiry, CltvExpiryDelta],
    split: SplitInfo,
    routerConf: RouterConf,
    targetNodeId: PublicKey,
    expectedRouteFees: Option[PathFinder.ExpectedRouteFees],
    payeeMetadata: Option[ByteVector],
    totalFeeReserve: MilliSatoshi = MilliSatoshi(0L),
    allowedChans: Seq[Channel] = Nil,
    outerPaymentSecret: ByteVector32 = ByteVector32.Zeroes,
    assistedEdges: Set[GraphEdge] = Set.empty,
    onionTlvs: Seq[OnionPaymentPayloadTlv] = Nil,
    userCustomTlvs: Seq[GenericTlv] = Nil
)

case class OutgoingPaymentMasterData(
    trampolineStates: TrampolineRoutingStates,
    paymentSenders: Map[FullPaymentTag, OutgoingPaymentSender],
    chanFailedAtAmount: Map[DescAndCapacity, StampedChannelFailed] = Map.empty,
    nodeFailedWithUnknownUpdateTimes: Map[PublicKey, Int] = Map.empty,
    directionFailedTimes: Map[NodeDirectionDesc, Int] = Map.empty,
    chanNotRoutable: Set[ChannelDesc] = Set.empty
) { me =>
  def withNewTrampolineStates(
      trampolineStates: TrampolineRoutingStates
  ): OutgoingPaymentMasterData = copy(trampolineStates = trampolineStates)

  def withoutTrampolineStates(nodeId: PublicKey): OutgoingPaymentMasterData =
    me.modify(_.trampolineStates.states).using(_ - nodeId)

  def withFailuresReduced(stampInFuture: Long): OutgoingPaymentMasterData = {
    // Reduce failure times to give previously failing channels a chance
    // failed-at-amount is restored gradually within a time window
    val acc = Map.empty[DescAndCapacity, StampedChannelFailed]

    copy(
      nodeFailedWithUnknownUpdateTimes =
        nodeFailedWithUnknownUpdateTimes.view.mapValues(_ / 2).toMap,
      directionFailedTimes = directionFailedTimes.view.mapValues(_ / 2).toMap,
      chanFailedAtAmount =
        chanFailedAtAmount.foldLeft(acc) { case (acc, dac ~ failed) =>
          val restoredRatio: Double =
            (stampInFuture - failed.stamp) / LNParams.failedChanRecoveryMsec
          val failed1 = failed.copy(amount =
            failed.amount + (dac.capacity - failed.amount) * restoredRatio
          )
          if (failed1.amount >= dac.capacity) acc
          else acc.updated(dac, failed1)
        },
      chanNotRoutable = Set.empty
    )
  }
}

// All current outgoing in-flight payments
object OutgoingPaymentMaster {
  type PartIdToAmount = Map[ByteVector, MilliSatoshi]
  final val CMDChanGotOnline = "cmd-chan-got-online"
  final val CMDAskForRoute = "cmd-ask-for-route"
  final val CMDAbort = "cmd-abort"

  sealed trait State
  case object ExpectingPayments extends State
  case object WaitingForRoute extends State
}

class OutgoingPaymentMaster(val cm: ChannelMaster)
    extends StateMachine[OutgoingPaymentMasterData, OutgoingPaymentMaster.State]
    with CanBeRepliedTo { me =>
  def initialState = OutgoingPaymentMaster.ExpectingPayments

  become(
    OutgoingPaymentMasterData(TrampolineRoutingStates(Map.empty), Map.empty),
    OutgoingPaymentMaster.ExpectingPayments
  )

  def process(change: Any): Unit =
    Future(me doProcess change)(Channel.channelContext)

  var clearFailures: Boolean = true

  def doProcess(change: Any): Unit = (change, state) match {
    case (TrampolinePeerDisconnected(nodeId), _) =>
      become(data withoutTrampolineStates nodeId, state)

    case (TrampolinePeerUpdated(nodeId, TrampolineUndesired), _) =>
      become(data withoutTrampolineStates nodeId, state)

    case (TrampolinePeerUpdated(nodeId, init: TrampolineStatusInit), _) =>
      become(
        data withNewTrampolineStates data.trampolineStates.init(nodeId, init),
        state
      )

    case (TrampolinePeerUpdated(nodeId, update: TrampolineStatusUpdate), _)
        if data.trampolineStates.states.contains(nodeId) =>
      become(
        data withNewTrampolineStates data.trampolineStates
          .merge(nodeId, update),
        state
      )

    case (send: SendMultiPart, _) =>
      if (clearFailures)
        become(data.withFailuresReduced(System.currentTimeMillis), state)
      for (graphEdge <- send.assistedEdges) cm.pf process graphEdge
      data.paymentSenders(send.fullTag) doProcess send
      me process CMDAskForRoute

    case (CMDChanGotOnline, _) =>
      // Payments may still have awaiting parts due to offline channels
      data.paymentSenders.values.foreach(_ doProcess CMDChanGotOnline)
      me process CMDAskForRoute

    case (CMDAskForRoute, OutgoingPaymentMaster.ExpectingPayments) =>
      // This is a proxy to always send command in payment master thread
      // IMPLICIT GUARD: this message is ignored in all other states
      data.paymentSenders.values.foreach(_ doProcess CMDAskForRoute)

    case (req: RouteRequest, OutgoingPaymentMaster.ExpectingPayments) =>
      // IMPLICIT GUARD: this message is ignored in all other states
      val currentUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] =
        usedCapacities
      val currentUsedDescs =
        mapKeys[DescAndCapacity, MilliSatoshi, ChannelDesc](
          currentUsedCapacities,
          _.desc,
          defVal = MilliSatoshi(0L)
        )
      val ignoreChansCanNotHandle = currentUsedCapacities.collect {
        case (dac, used)
            if used + req.amount >= dac.capacity - req.amount / 32 =>
          dac.desc
      }
      val ignoreDirectionsFailedTimes = data.directionFailedTimes.collect {
        case (desc, times)
            if times >= LNParams.routerConf.maxDirectionFailures =>
          desc
      }
      val ignoreChansFailedAtAmount = data.chanFailedAtAmount.collect {
        case (dac, failedAt)
            if failedAt.amount - currentUsedDescs(
              dac.desc
            ) - req.amount / 8 <= req.amount =>
          dac.desc
      }
      val ignoreNodes = data.nodeFailedWithUnknownUpdateTimes.collect {
        case (affectedNodeId, nodeFailedTimes)
            if nodeFailedTimes >= LNParams.routerConf.maxStrangeNodeFailures =>
          affectedNodeId
      }
      // Note: we may get many route request messages from payment FSMs with parts waiting for routes
      // so it is important to immediately switch to WaitingForRoute after seeing a first message
      cm.pf process PathFinder.FindRoute(
        me,
        req.copy(
          ignoreNodes = ignoreNodes.toSet,
          ignoreChannels =
            data.chanNotRoutable ++ ignoreChansCanNotHandle ++ ignoreChansFailedAtAmount,
          ignoreDirections = ignoreDirectionsFailedTimes.toSet
        )
      )
      become(data, OutgoingPaymentMaster.WaitingForRoute)

    case (response: RouteResponse, _) =>
      data.paymentSenders.get(response.fullTag).foreach(_ doProcess response)
      // Switch state to allow new route requests to come through
      become(data, OutgoingPaymentMaster.ExpectingPayments)
      me process CMDAskForRoute

    case (ChannelFailedAtAmount(descAndCapacity), _) =>
      // At this point an affected InFlight status IS STILL PRESENT so failedAtAmount1 = usedCapacities = sum(inFlight)
      become(
        data.copy(
          chanFailedAtAmount = data.chanFailedAtAmount.updated(
            value = StampedChannelFailed(
              data.chanFailedAtAmount
                .get(descAndCapacity)
                .map(_.amount)
                .getOrElse(MilliSatoshi(Long.MaxValue))
                .min(
                  usedCapacities(
                    descAndCapacity
                  )
                ),
              stamp = System.currentTimeMillis
            ),
            key = descAndCapacity
          ),
          directionFailedTimes = data.directionFailedTimes.updated(
            descAndCapacity.desc.toDirection,
            data.directionFailedTimes
              .getOrElse(descAndCapacity.desc.toDirection, 0) + 1
          )
        ),
        state
      )

    case (NodeFailed(nodeId, increment), _) =>
      val newNodeFailedTimes =
        data.nodeFailedWithUnknownUpdateTimes.getOrElse(nodeId, 0) + increment
      become(
        data.copy(nodeFailedWithUnknownUpdateTimes =
          data.nodeFailedWithUnknownUpdateTimes.updated(
            nodeId,
            newNodeFailedTimes
          )
        ),
        state
      )

    case (ChannelNotRoutable(desc), _) =>
      become(data.copy(chanNotRoutable = data.chanNotRoutable + desc), state)

    // Following messages expect that target FSM is always present
    // this won't be the case with failed/fulfilled leftovers in channels on app restart
    // so it has to be made sure that all relevalnt FSMs are manually re-initialized on startup

    case (reject: LocalReject, _) =>
      data.paymentSenders
        .get(reject.localAdd.fullTag)
        .foreach(_ doProcess reject)
      me process CMDAskForRoute

    case (fulfill: RemoteFulfill, _) =>
      // We may have local and multiple routed outgoing payment sets at once, all of them must be notified
      data.paymentSenders.view
        .filterKeys(_.paymentHash == fulfill.ourAdd.paymentHash)
        .values
        .foreach(_ doProcess fulfill)
      me process CMDAskForRoute

    case (remoteReject: RemoteReject, _) =>
      data.paymentSenders
        .get(remoteReject.ourAdd.fullTag)
        .foreach(_ doProcess remoteReject)
      me process CMDAskForRoute

    case _ =>
  }

  def removeSenderFSM(fullTag: FullPaymentTag): Unit = {
    if (data.paymentSenders.contains(fullTag)) {
      // First we get their fail, then stateUpdateStream fires, then we fire it here again if FSM is to be removed
      become(data.copy(paymentSenders = data.paymentSenders - fullTag), state)
      ChannelMaster.next(ChannelMaster.stateUpdateStream)
    }
  }

  def createSenderFSM(
      listeners: Iterable[OutgoingPaymentListener],
      fullTag: FullPaymentTag
  ): Unit = {
    if (!data.paymentSenders.contains(fullTag)) {
      become(
        data.copy(paymentSenders =
          data.paymentSenders.updated(
            value = new OutgoingPaymentSender(fullTag, listeners, me),
            key = fullTag
          )
        ),
        state
      )
    }
  }

  def stateUpdated(bag: InFlightPayments): Unit = Future {
    // We need this to issue "wholePaymentSucceeded" AFTER neither in-flight parts nor leftovers in channels are present
    // because FIRST peer sends a preimage (removing in-flight in FSM), THEN peer sends a state update (clearing channel leftovers)
    data.paymentSenders.foreach { case (fullTag, sender) =>
      if (!bag.out.contains(fullTag))
        sender.stateUpdated()
    }
  }(Channel.channelContext)

  def rightNowSendable(
      chans: Iterable[Channel],
      maxFee: MilliSatoshi
  ): mutable.Map[ChanAndCommits, MilliSatoshi] = {
    // This method is supposed to be used to find channels which are able to currently handle a given amount + fee
    // note that it is possible for remaining fee to be disproportionally large relative to payment amount
    getSendable(chans.filter(Channel.isOperationalAndOpen), maxFee)
  }

  // What can be sent through given channels with yet unprocessed parts taken into account
  def getSendable(
      chans: Iterable[Channel],
      maxFee: MilliSatoshi
  ): mutable.Map[ChanAndCommits, MilliSatoshi] = {
    // Example 1: chan toLocal=100, 10 in-flight AND IS NOT YET preset in channel yet, resulting sendable = 100 (toLocal) - 10 (in-flight - nothing) = 90
    // Example 2: chan toLocal=100, 10 in-flight AND IS present in channel already, resulting sendable = 90 (toLocal with in-flight) - 0 (in-flight - partId) = 90
    val waitParts =
      mutable.Map.empty[ByteVector32, PartIdToAmount] withDefaultValue Map.empty
    val finals =
      mutable.Map
        .empty[ChanAndCommits, MilliSatoshi]
        .withDefaultValue(MilliSatoshi(0L))

    // Wait part may have no route yet (but we expect a route to arrive) or it could be sent to channel but not processed by channel yet
    def waitPartsNotYetInChannel(cnc: ChanAndCommits): PartIdToAmount =
      waitParts(cnc.commits.channelId) -- cnc.commits.allOutgoing.map(_.partId)
    data.paymentSenders.values.flatMap(_.data.parts.values).collect {
      case wait: WaitForRouteOrInFlight =>
        waitParts(wait.cnc.commits.channelId) += wait.partId -> wait.amount
    }
    chans
      .flatMap(Channel.chanAndCommitsOpt)
      .foreach(cnc =>
        finals(cnc) = cnc.commits.maxSendInFlight.min(
          cnc.commits.availableForSend
        ) - maxFee - waitPartsNotYetInChannel(cnc).values
          .fold(MilliSatoshi(0))(_ + _)
      )
    finals.filter { case (cnc, sendable) =>
      sendable >= cnc.commits.minSendable
    }
  }

  def usedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = {
    // This gets supposedly used capacities of external channels in a routing graph
    // we need this to exclude channels which definitely can't route a given amount right now
    val accumulator =
      mutable.Map
        .empty[DescAndCapacity, MilliSatoshi] withDefaultValue MilliSatoshi(0L)
    // This is not always accurate since on restart FSMs will be empty while leftovers may still be in chans
    val descsAndCaps = data.paymentSenders.values
      .flatMap(_.data.inFlightParts)
      .flatMap(_.route.routedPerHop)
    descsAndCaps.foreach { case (amount, hop) =>
      accumulator(hop.edge.toDescAndCapacity) += amount
    }
    accumulator
  }
}
