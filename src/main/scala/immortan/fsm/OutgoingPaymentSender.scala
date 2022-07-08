package immortan.fsm

import scala.collection.mutable
import scala.util.Random.shuffle
import scala.util.{Success, Failure}
import scodec.bits.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.router.{Announcements, ChannelUpdateExt}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.PacketAndSecrets
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.channel.{
  CMD_ADD_HTLC,
  ChannelOffline,
  InPrincipleNotSendable,
  LocalReject
}
import fr.acinq.eclair.payment.OutgoingPaymentPacket
import fr.acinq.eclair.transactions.{
  RemoteFulfill,
  RemoteReject,
  RemoteUpdateFail,
  RemoteUpdateMalform
}
import immortan.{LNParams, InFlightPayments, Channel, ChanAndCommits}
import immortan.PaymentStatus._
import immortan.fsm.PaymentFailure._
import immortan.fsm.OutgoingPaymentMaster._
import immortan.crypto.Tools._
import immortan.crypto.StateMachine

object PaymentFailure {
  type Failures = List[PaymentFailure]
  final val NO_ROUTES_FOUND = "no-routes-found"
  final val NOT_ENOUGH_FUNDS = "not-enough-funds"
  final val PAYMENT_NOT_SENDABLE = "payment-not-sendable"
  final val RUN_OUT_OF_RETRY_ATTEMPTS = "run-out-of-retry-attempts"
  final val RUN_OUT_OF_CAPABLE_CHANNELS = "run-out-of-capable-channels"
  final val NODE_COULD_NOT_PARSE_ONION = "node-could-not-parse-onion"
  final val NOT_RETRYING_NO_DETAILS = "not-retrying-no-details"
  final val ONION_CREATION_FAILURE = "onion-creation-failure"
  final val TIMED_OUT = "timed-out"
}

sealed trait PaymentFailure {
  def asString: String
}

case class LocalFailure(status: String, amount: MilliSatoshi)
    extends PaymentFailure {
  override def asString: String = {
    val extra =
      if (LNParams.cm.pf.isIncompleteGraph)
        "; the channel graph is not fully synced yet, please wait a little and try again."
      else ""

    s"- Local failure: $status$extra"
  }
}

case class UnreadableRemoteFailure(route: Route) extends PaymentFailure {
  override def asString: String =
    s"- Remote failure at unknown channel.\n${route.asString}"
}

case class RemoteFailure(packet: Sphinx.DecryptedFailurePacket, route: Route)
    extends PaymentFailure {
  lazy val originShortChanId: Option[Long] = route
    .getEdgeForNode(packet.originNode)
    .map(_.updExt.update.shortChannelId)

  def chanString = originShortChanId
    .map(ShortChannelId.asString(_))
    .getOrElse("first hop")

  override def asString: String = {
    s"- ${packet.failureMessage.message} at ${originShortChanId}.\n${route.asString}"
  }
}

// Individual outgoing payment status
case class OutgoingPaymentSenderData(
    cmd: SendMultiPart,
    parts: Map[ByteVector, PartStatus],
    preimage: Option[ByteVector32] = None,
    failures: Failures = Nil
) {
  def withLocalFailure(
      reason: String,
      amount: MilliSatoshi
  ): OutgoingPaymentSenderData =
    copy(failures = LocalFailure(reason, amount) +: failures)
  def withoutPartId(failedPartId: ByteVector): OutgoingPaymentSenderData =
    copy(parts = parts - failedPartId)
  def usedRoutesAsString: String =
    inFlightParts.map(_.route.asString).mkString("\n\n")
  def failuresAsString: String =
    failures.reverse.map(_.asString).mkString("\n\n")

  lazy val waitOnlinePart: Option[WaitForChanOnline] =
    parts.values.collectFirst { case wait: WaitForChanOnline => wait }
  lazy val inFlightParts: Iterable[InFlightInfo] = parts.values.flatMap {
    case wait: WaitForRouteOrInFlight => wait.flight
    case _                            => None
  }
  lazy val successfulUpdates: Iterable[ChannelUpdateExt] =
    inFlightParts.flatMap(_.route.routedPerHop).secondItems.map(_.edge.updExt)
  lazy val usedFee: MilliSatoshi = inFlightParts.map(_.route.fee).sum
}

class OutgoingPaymentSender(
    val fullTag: FullPaymentTag,
    val listeners: Iterable[OutgoingPaymentListener],
    opm: OutgoingPaymentMaster
) extends StateMachine[OutgoingPaymentSenderData, Int] { me =>
  def initialState = -1

  become(
    OutgoingPaymentSenderData(
      SendMultiPart(
        fullTag,
        Right(LNParams.minInvoiceExpiryDelta),
        SplitInfo(0L.msat, 0L.msat),
        LNParams.routerConf,
        invalidPubKey,
        None,
        None
      ),
      Map.empty
    ),
    INIT
  )

  def doProcess(msg: Any): Unit = (msg, state) match {
    case (reject: RemoteReject, ABORTED) =>
      me abortMaybeNotify data.withoutPartId(reject.ourAdd.partId)
    case (reject: LocalReject, ABORTED) =>
      me abortMaybeNotify data.withoutPartId(reject.localAdd.partId)
    case (reject: RemoteReject, INIT) =>
      me abortMaybeNotify data.withLocalFailure(
        NOT_RETRYING_NO_DETAILS,
        reject.ourAdd.amountMsat
      )
    case (reject: LocalReject, INIT) =>
      me abortMaybeNotify data.withLocalFailure(
        NOT_RETRYING_NO_DETAILS,
        reject.localAdd.amountMsat
      )
    case (reject: RemoteReject, SUCCEEDED)
        if reject.ourAdd.paymentHash == fullTag.paymentHash =>
      become(data.withoutPartId(reject.ourAdd.partId), SUCCEEDED)
    case (reject: LocalReject, SUCCEEDED)
        if reject.localAdd.paymentHash == fullTag.paymentHash =>
      become(data.withoutPartId(reject.localAdd.partId), SUCCEEDED)
    case (fulfill: RemoteFulfill, SUCCEEDED)
        if fulfill.ourAdd.paymentHash == fullTag.paymentHash =>
      become(data.withoutPartId(fulfill.ourAdd.partId), SUCCEEDED)

    case (cmd: SendMultiPart, INIT | ABORTED) =>
      val chans = opm.rightNowSendable(cmd.allowedChans, cmd.totalFeeReserve)
      assignToChans(
        chans,
        OutgoingPaymentSenderData(cmd, Map.empty),
        cmd.split.myPart
      )

    case (CMDAbort, INIT | PENDING) if data.waitOnlinePart.nonEmpty =>
      // When at least some parts get through we can eventaully expect for remote timeout
      // but if ALL parts are still waiting after local timeout then we need to fail a whole payment locally
      me abortMaybeNotify data
        .copy(parts = Map.empty)
        .withLocalFailure(TIMED_OUT, data.cmd.split.myPart)

    case (fulfill: RemoteFulfill, INIT | PENDING | ABORTED)
        if fulfill.ourAdd.paymentHash == fullTag.paymentHash =>
      // Provide listener with ORIGINAL data which has all used routes intact
      for (listener <- listeners) listener.gotFirstPreimage(data, fulfill)
      become(
        data
          .withoutPartId(fulfill.ourAdd.partId)
          .copy(preimage = Some(fulfill.theirPreimage)),
        SUCCEEDED
      )

    case (CMDChanGotOnline, PENDING) =>
      for (waitOnline <- data.waitOnlinePart) {
        val nowSendable =
          opm.rightNowSendable(data.cmd.allowedChans, feeLeftover)
        assignToChans(
          nowSendable,
          data.withoutPartId(waitOnline.partId),
          waitOnline.amount
        )
      }

    case (CMDAskForRoute, PENDING) =>
      data.parts.values.toList
        .collect {
          case wait: WaitForRouteOrInFlight if wait.flight.isEmpty => wait
        }
        .sortBy(_.amount)
        .lastOption
        .foreach { wait =>
          val routeParams = RouteParams(
            feeReserve = feeLeftover,
            routeMaxLength = data.cmd.routerConf.initRouteMaxLength,
            routeMaxCltv = data.cmd.routerConf.routeMaxCltv
          )
          opm process RouteRequest(
            fullTag,
            wait.partId,
            invalidPubKey,
            data.cmd.targetNodeId,
            wait.amount,
            mkFakeLocalEdge(
              invalidPubKey,
              wait.cnc.commits.remoteInfo.nodeId
            ),
            routeParams
          )
        }

    case (fail: NoRouteAvailable, PENDING) =>
      data.parts.values.collectFirst {
        case wait: WaitForRouteOrInFlight
            if wait.flight.isEmpty && wait.partId == fail.partId =>
          // localFailedChans includes a channel we just tried because there are no routes found starting at this channel
          opm
            .rightNowSendable(
              data.cmd.allowedChans diff wait.localFailedChans,
              feeLeftover
            )
            .collectFirst {
              case (cnc, sendable) if sendable >= wait.amount => cnc
            } match {
            case Some(okCnc) =>
              become(
                data.copy(parts =
                  data.parts + wait.oneMoreLocalAttempt(okCnc).tuple
                ),
                PENDING
              )
            // TODO: case None if <can use trampoline for this shard at affordable price?> =>
            case None if outgoingHtlcSlotsLeft >= 1 =>
              become(
                data.withoutPartId(wait.partId),
                PENDING
              ) doProcess CutIntoHalves(wait.amount)
            case _ =>
              me abortMaybeNotify data
                .withoutPartId(wait.partId)
                .withLocalFailure(NO_ROUTES_FOUND, wait.amount)
          }
      }

    case (found: RouteFound, PENDING) =>
      data.parts.values.collectFirst {
        case wait: WaitForRouteOrInFlight
            if wait.flight.isEmpty && wait.partId == found.partId =>
          // TODO: even if route is found we can compare its fees against trampoline fees here and choose trampoline if its fees are more attractive
          val payeeExpiry = data.cmd.chainExpiry.fold(
            fb = _.toCltvExpiry(LNParams.blockCount.get + 1L),
            fa = identity
          )
          val finalPayload = PaymentOnion.createMultiPartPayload(
            wait.amount,
            data.cmd.split.totalSum,
            payeeExpiry,
            data.cmd.outerPaymentSecret,
            data.cmd.payeeMetadata,
            data.cmd.onionTlvs,
            data.cmd.userCustomTlvs
          )

          OutgoingPaymentPacket.buildPaymentPacket(
            wait.onionKey,
            fullTag.paymentHash,
            found.route.hops,
            finalPayload
          ) match {
            case Success((firstAmount, firstExpiry, onion)) => {
              val cmdAdd = CMD_ADD_HTLC(
                fullTag,
                firstAmount,
                firstExpiry,
                PacketAndSecrets(onion.packet, onion.sharedSecrets),
                finalPayload
              )
              become(
                data.copy(parts =
                  data.parts + wait.withKnownRoute(cmdAdd, found.route).tuple
                ),
                PENDING
              )
              wait.cnc.chan.process(cmdAdd)
            }
            case Failure(err) => {
              // One failure reason could be too much metadata, or too many routing hints if this is a trampoline payment
              me abortMaybeNotify data
                .withoutPartId(wait.partId)
                .withLocalFailure(ONION_CREATION_FAILURE, wait.amount)
            }
          }
      }

    case (reject: LocalReject, PENDING) =>
      data.parts.values.collectFirst {
        case wait: WaitForRouteOrInFlight
            if wait.flight.isDefined && wait.partId == reject.localAdd.partId =>
          // localFailedChans includes a channel we just tried because for whatever reason this channel is incapable now
          opm
            .rightNowSendable(
              data.cmd.allowedChans diff wait.localFailedChans,
              feeLeftover
            )
            .collectFirst {
              case (cnc, sendable) if sendable >= wait.amount => cnc
            } match {
            case _ if reject.isInstanceOf[InPrincipleNotSendable] =>
              me abortMaybeNotify data
                .withoutPartId(wait.partId)
                .withLocalFailure(PAYMENT_NOT_SENDABLE, wait.amount)
            case None if reject.isInstanceOf[ChannelOffline] =>
              assignToChans(
                opm.rightNowSendable(data.cmd.allowedChans, feeLeftover),
                data.withoutPartId(wait.partId),
                wait.amount
              )
            case None =>
              me abortMaybeNotify data
                .withoutPartId(wait.partId)
                .withLocalFailure(RUN_OUT_OF_CAPABLE_CHANNELS, wait.amount)
            case Some(okCnc) =>
              become(
                data.copy(parts =
                  data.parts + wait.oneMoreLocalAttempt(okCnc).tuple
                ),
                PENDING
              )
          }
      }

    case (reject: RemoteUpdateMalform, PENDING) =>
      data.parts.values.collectFirst {
        case wait: WaitForRouteOrInFlight
            if wait.flight.isDefined && wait.partId == reject.ourAdd.partId =>
          // We don't know which node along a route thinks that onion is malformed, but assume it's the 2nd node, by doing this graph will be returning different routes
          for (hop <- wait.flight.get.route.hops.tail.dropRight(1).headOption)
            opm doProcess NodeFailed(
              hop.nodeId,
              data.cmd.routerConf.maxStrangeNodeFailures
            )
          resolveRemoteFail(
            LocalFailure(NODE_COULD_NOT_PARSE_ONION, wait.amount),
            wait
          )
      }

    case (reject: RemoteUpdateFail, PENDING) =>
      data.parts.values.collectFirst {
        case wait: WaitForRouteOrInFlight
            if wait.flight.isDefined && wait.partId == reject.ourAdd.partId =>
          val InFlightInfo(cmd, route) = wait.flight.get

          Sphinx.FailurePacket.decrypt(
            reject.fail.reason,
            cmd.packetAndSecrets.sharedSecrets
          ) map {
            case pkt
                if pkt.originNode == data.cmd.targetNodeId || PaymentTimeout == pkt.failureMessage =>
              me abortMaybeNotify data
                .withoutPartId(wait.partId)
                .copy(failures = RemoteFailure(pkt, route) +: data.failures)

            case pkt @ Sphinx.DecryptedFailurePacket(
                  originNodeId,
                  failure: Update
                ) =>
              // Pathfinder channels must be fully loaded from db at this point since we have already used them to construct a route earlier
              val isSignatureFine = opm.cm.pf
                .nodeIdFromUpdate(failure.update)
                .contains(originNodeId) && Announcements.checkSig(
                failure.update
              )(originNodeId)

              if (isSignatureFine) {
                opm.cm.pf process failure.update
                val edgeOpt = route.getEdgeForNode(originNodeId)
                val isEnabled =
                  Announcements.isEnabled(failure.update.channelFlags)
                for (edge <- edgeOpt if !isEnabled)
                  opm doProcess ChannelNotRoutable(edge.desc)

                edgeOpt match {
                  case Some(edge)
                      if edge.updExt.update.shortChannelId != failure.update.shortChannelId =>
                    // This is fine: remote node has used a different channel than the one we have initially requested
                    // But remote node may send such errors infinitely so increment this specific type of failure
                    // Still fail an originally selected channel since it has most likely been tried too
                    opm doProcess ChannelFailedAtAmount(
                      edge.toDescAndCapacity
                    )
                    opm doProcess NodeFailed(originNodeId, increment = 1)

                  case Some(edge)
                      if edge.updExt.update.core.noPosition == failure.update.core.noPosition =>
                    // Remote node returned EXACTLY same update, this channel is likely imbalanced
                    opm doProcess ChannelFailedAtAmount(
                      edge.toDescAndCapacity
                    )

                  case _ =>
                    // Something like higher feerates or CLTV, channel is updated in graph and may be chosen once again
                    // But remote node may send oscillating updates infinitely so increment this specific type of failure
                    opm doProcess NodeFailed(originNodeId, increment = 1)
                }
              } else {
                // Invalid sig is a severe violation, ban sender node for 6 subsequent MPP sessions
                opm doProcess NodeFailed(
                  originNodeId,
                  data.cmd.routerConf.maxStrangeNodeFailures * 32
                )
              }

              // Record a remote error and keep trying the rest
              resolveRemoteFail(RemoteFailure(pkt, route), wait)

            case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _: Node) =>
              // Node may become fine on next payment, but ban it for current attempts
              opm doProcess NodeFailed(
                nodeId,
                data.cmd.routerConf.maxStrangeNodeFailures
              )
              resolveRemoteFail(RemoteFailure(pkt, route), wait)

            case pkt: Sphinx.DecryptedFailurePacket =>
              // This is not an update failure, better avoid entirely
              route
                .getEdgeForNode(pkt.originNode)
                .map(_.toDescAndCapacity) match {
                case Some(descAndCapacity) =>
                  opm doProcess ChannelNotRoutable(descAndCapacity.desc)
                case None =>
                  opm doProcess NodeFailed(
                    pkt.originNode,
                    data.cmd.routerConf.maxStrangeNodeFailures
                  )
              }

              // Record an error and keep trying out the rest
              resolveRemoteFail(RemoteFailure(pkt, route), wait)

          } getOrElse {
            // We don't know which node along a route is sending garbage, but assume it's the 2nd node, by doing this graph will be returning different routes
            for (hop <- route.hops.tail.dropRight(1).headOption)
              opm doProcess NodeFailed(
                hop.nodeId,
                data.cmd.routerConf.maxStrangeNodeFailures
              )
            resolveRemoteFail(UnreadableRemoteFailure(route), wait)
          }
      }

    case (cut: CutIntoHalves, PENDING) =>
      val partOne: MilliSatoshi = cut.amount / 2
      val partTwo: MilliSatoshi = cut.amount - partOne
      // Run sequentially as this mutates data, both RightNowSendable and Data are updated
      assignToChans(
        opm.rightNowSendable(data.cmd.allowedChans, feeLeftover),
        data,
        partOne
      )
      assignToChans(
        opm.rightNowSendable(data.cmd.allowedChans, feeLeftover),
        data,
        partTwo
      )

    case _ =>
  }

  def stateUpdated(): Unit = {
    if (state == SUCCEEDED && data.inFlightParts.isEmpty) {
      for (listener <- listeners) listener.wholePaymentSucceeded(data)
    }
  }

  def feeLeftover: MilliSatoshi = data.cmd.totalFeeReserve - data.usedFee

  def outgoingHtlcSlotsLeft: Int =
    data.cmd.allowedChans.size * LNParams.maxInChannelHtlcs - data.parts.size

  def assignToChans(
      sendable: mutable.Map[ChanAndCommits, MilliSatoshi],
      senderData: OutgoingPaymentSenderData,
      amount: MilliSatoshi
  ): Unit = {
    // This is a terminal method in a sense that it either successfully assigns a given amount to channels or turns a payment into failed state
    val directChansFirst = shuffle(sendable.toSeq) sortBy { case (cnc, _) =>
      if (cnc.commits.remoteInfo.nodeId == senderData.cmd.targetNodeId) 0 else 1
    }
    // This method always sets a new partId to assigned parts so old payment statuses in data must be cleared before calling it

    directChansFirst.foldLeft(Map.empty[ByteVector, PartStatus] -> amount) {
      case (accumulator ~ leftover, cnc ~ chanSendable) if leftover > 0L.msat =>
        // If leftover becomes less than sendable minimum then we must bump it upwards
        // Example: channel leftover=500, chanSendable=200 -> sending 200
        // Example: channel leftover=300, chanSendable=400 -> sending 300

        val noFeeAmount = leftover.min(chanSendable)
        val wait = WaitForRouteOrInFlight(randomKey, noFeeAmount, cnc)
        (accumulator + wait.tuple, leftover - wait.amount)

      case (collected, _) =>
        // No more amount to assign
        // Propagate what's collected
        collected

    } match {
      case (newParts, rest) if rest <= 0L.msat =>
        // A whole amount has been fully split across our local channels
        // leftover may be slightly negative due to min sendable corrections
        become(senderData.copy(parts = senderData.parts ++ newParts), PENDING)

      case (_, rest)
          if opm
            .getSendable(
              senderData.cmd.allowedChans
                .filter(Channel.isOperationalAndSleeping),
              feeLeftover
            )
            .values
            .sum >= rest =>
        // Amount has not been fully split, but it is possible to further successfully split it once some SLEEPING channel becomes OPEN
        become(
          senderData.copy(parts =
            senderData.parts + WaitForChanOnline(randomKey, amount).tuple
          ),
          PENDING
        )

      case _ =>
        // A positive leftover is present with no more channels left
        // partId should have already been removed from data at this point
        me abortMaybeNotify senderData.withLocalFailure(
          NOT_ENOUGH_FUNDS,
          amount
        )
    }

    // It may happen that all chans are to stay offline indefinitely, payment parts will then await indefinitely
    // so set a timer to abort a payment in case if we have no in-flight parts after some reasonable amount of time
    // note that timer gets reset each time this method gets called
    delayedCMDWorker.replaceWork(CMDAbort)
  }

  // Turn in-flight into waiting-for-route and expect for subsequent CMDAskForRoute
  def resolveRemoteFail(
      failure: PaymentFailure,
      wait: WaitForRouteOrInFlight
  ): Unit = {
    // Remove pending part from data right away to not interfere with sendable calculations
    become(
      data.withoutPartId(wait.partId).copy(failures = failure +: data.failures),
      PENDING
    )

    shuffle(opm.rightNowSendable(data.cmd.allowedChans, feeLeftover).toSeq)
      .collectFirst {
        case (cnc, sendable) if sendable >= wait.amount => cnc
      } match {
      case Some(okCnc)
          if wait.remoteAttempts < data.cmd.routerConf.maxRemoteAttempts =>
        become(
          data.copy(parts =
            data.parts + wait.oneMoreRemoteAttempt(okCnc).tuple
          ),
          PENDING
        )
      // TODO: case None if <can use trampoline for this shard at affordable price?> =>
      case _ if outgoingHtlcSlotsLeft >= 2 =>
        become(data, PENDING) doProcess CutIntoHalves(wait.amount)
      case _ =>
        me abortMaybeNotify data.withLocalFailure(
          RUN_OUT_OF_RETRY_ATTEMPTS,
          wait.amount
        )
    }
  }

  def abortMaybeNotify(senderData: OutgoingPaymentSenderData): Unit = {
    val isFinalized =
      senderData.inFlightParts.isEmpty && !opm.cm.allInChannelOutgoing.contains(
        fullTag
      )
    if (isFinalized)
      for (listener <- listeners) listener.wholePaymentFailed(senderData)
    become(senderData, ABORTED)
  }
}

// Individual outgoing part status
sealed trait PartStatus { me =>
  final val partId: ByteVector = onionKey.publicKey.value
  def tuple: (ByteVector, PartStatus) = (partId, me)
  def onionKey: PrivateKey
}

case class InFlightInfo(cmd: CMD_ADD_HTLC, route: Route)

case class WaitForChanOnline(onionKey: PrivateKey, amount: MilliSatoshi)
    extends PartStatus

// First we reserve a channel which will be used, then we hopefully obtain a route for that channel
case class WaitForRouteOrInFlight(
    onionKey: PrivateKey,
    amount: MilliSatoshi,
    cnc: ChanAndCommits,
    flight: Option[InFlightInfo] = None,
    feesTried: List[MilliSatoshi] = Nil,
    localFailed: List[Channel] = Nil,
    remoteAttempts: Int = 0
) extends PartStatus {

  def maxAttemptedFee: MilliSatoshi =
    feesTried.sorted.lastOption.getOrElse(0L.msat)
  def withKnownRoute(cmd: CMD_ADD_HTLC, route: Route): WaitForRouteOrInFlight =
    copy(
      flight = InFlightInfo(cmd, route).asSome,
      feesTried = route.fee :: feesTried
    )
  def oneMoreRemoteAttempt(cnc: ChanAndCommits): WaitForRouteOrInFlight = copy(
    onionKey = randomKey,
    flight = None,
    remoteAttempts = remoteAttempts + 1,
    cnc = cnc
  ) // Session key must be changed
  def oneMoreLocalAttempt(cnc: ChanAndCommits): WaitForRouteOrInFlight = copy(
    flight = None,
    localFailed = localFailedChans,
    cnc = cnc
  ) // Session key may be reused since payment was not tried
  lazy val localFailedChans: List[Channel] = cnc.chan :: localFailed
}

trait OutgoingPaymentListener {
  // With local failures this will be the only way to know
  def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = none
  def wholePaymentSucceeded(data: OutgoingPaymentSenderData): Unit = none
  def gotFirstPreimage(
      data: OutgoingPaymentSenderData,
      fulfill: RemoteFulfill
  ): Unit = none
}
