package immortan

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try
import com.google.common.cache.LoadingCache
import rx.lang.scala.Subject
import scoin._
import scoin.Crypto.{PrivateKey, PublicKey}
import scoin.ln._
import scoin.ln.{IncomingPaymentPacket, Bolt11Invoice}
import scoin.hc._

import immortan._
import immortan.blockchain.TxConfirmedAt
import immortan.router._
import immortan.channel._
import immortan.Channel._
import immortan.ChannelListener.{Malfunction, Transition}
import immortan.ChannelMaster._
import immortan.fsm._
import immortan.fsm.OutgoingPaymentMaster.CMDChanGotOnline
import immortan.utils.{PaymentRequestExt, Rx}

object ChannelMaster {
  type PreimageTry = Try[ByteVector32]
  type PaymentInfoTry = Try[PaymentInfo]
  type RevealedLocalFulfills = Iterable[LocalFulfill]
  type TxConfirmedAtOpt = Option[TxConfirmedAt]

  type OutgoingAdds = Iterable[UpdateAddHtlc]
  type ReasonableResolutions = Iterable[ReasonableResolution]
  type ReasonableTrampolines = Iterable[ReasonableTrampoline]
  type ReasonableLocals = Iterable[ReasonableLocal]

  final val updateCounter = new AtomicLong(0)
  final val stateUpdateStream: Subject[Long] = Subject[Long]()
  final val statusUpdateStream: Subject[Long] = Subject[Long]()

  final val payMarketDbStream: Subject[Long] = Subject[Long]()
  final val paymentDbStream: Subject[Long] = Subject[Long]()
  final val relayDbStream: Subject[Long] = Subject[Long]()
  final val txDbStream: Subject[Long] = Subject[Long]()

  def next(stream: Subject[Long] = null): Unit =
    stream.onNext(updateCounter.incrementAndGet)
  final val inFinalized: Subject[IncomingProcessorData] =
    Subject[IncomingProcessorData]()

  final val NO_PREIMAGE = ByteVector32.One
  final val NO_SECRET = ByteVector32.Zeroes

  def dangerousHCRevealed(
      allRevealed: Map[ByteVector32, RevealedLocalFulfills],
      tip: Long,
      hash: ByteVector32
  ): Iterable[LocalFulfill] = {
    // Of all incoming payments inside of HCs for which we have revealed a preimage, find those which are dangerously close to expiration, but not expired yet, but not hopelessly close
    allRevealed
      .getOrElse(hash, Iterable.empty)
      .filter(
        tip >= _.theirAdd.cltvExpiry.toLong - LNParams.hcFulfillSafetyBlocks
      )
      .filter(tip <= _.theirAdd.cltvExpiry.toLong - 3)
  }
}

case class InFlightPayments(
    out: Map[FullPaymentTag, OutgoingAdds],
    in: Map[FullPaymentTag, ReasonableResolutions] = Map.empty
) {
  // Incoming HTLC tag is extracted from onion, corresponsing outgoing HTLC tag is stored in TLV, this way in/out can be linked
  val allTags: Set[FullPaymentTag] = out.keySet ++ in.keySet
}

class ChannelMaster(
    val payBag: PaymentBag,
    val chanBag: ChannelBag,
    val dataBag: DataBag,
    val pf: PathFinder
) extends ChannelListener
    with ConnectionListener
    with CanBeShutDown { me =>
  val initResolveMemo: LoadingCache[UpdateAddHtlcExt, IncomingResolution] =
    memoize(initResolve)
  val getPreimageMemo: LoadingCache[ByteVector32, PreimageTry] = memoize(
    payBag.getPreimage
  )
  val opm: OutgoingPaymentMaster = new OutgoingPaymentMaster(me)
  val tb: TrampolineBroadcaster = new TrampolineBroadcaster(me)

  val localPaymentListeners: mutable.Set[OutgoingPaymentListener] = {
    val defListener: OutgoingPaymentListener = new OutgoingPaymentListener {
      override def wholePaymentSucceeded(
          data: OutgoingPaymentSenderData
      ): Unit =
        opm.removeSenderFSM(data.cmd.fullTag)

      override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit =
        chanBag.db txWrap {
          // This method gets called after NO payment parts are left in system, irregardless of restarts
          dataBag.putReport(data.cmd.fullTag.paymentHash, data.failuresAsString)
          payBag.updAbortedOutgoing(data.cmd.fullTag.paymentHash)
          opm.removeSenderFSM(data.cmd.fullTag)
        }

      override def gotFirstPreimage(
          data: OutgoingPaymentSenderData,
          fulfill: RemoteFulfill
      ): Unit = chanBag.db txWrap {
        // Note that this method MAY get called multiple times for multipart payments if fulfills happen between restarts
        payBag
          .getPaymentInfo(fulfill.ourAdd.paymentHash)
          .filter(_.status != PaymentStatus.SUCCEEDED)
          .foreach { paymentInfo =>
            // Persist payment metadata if this is ACTUALLY the first preimage (otherwise payment would be marked as successful)
            payBag.addSearchablePayment(
              paymentInfo.description.queryText,
              fulfill.ourAdd.paymentHash
            )
            payBag.updOkOutgoing(fulfill, data.usedFee)

            if (data.inFlightParts.nonEmpty) {
              // Sender FSM won't have in-flight parts after restart
              dataBag.putReport(
                fulfill.ourAdd.paymentHash,
                data.usedRoutesAsString
              )
              // We only increment scores for normal channels, never for HCs
              data.successfulUpdates.foreach(pf.normalBag.incrementScore)
            }
          }

        payBag.setPreimage(fulfill.ourAdd.paymentHash, fulfill.theirPreimage)
        getPreimageMemo.invalidate(fulfill.ourAdd.paymentHash)
      }
    }

    // Mutable set so can be extended
    mutable.Set(defListener)
  }

  var all = Map.empty[ByteVector32, Channel]

  var inProcessors = Map.empty[FullPaymentTag, IncomingPaymentProcessor]

  var sendTo: (Any, ByteVector32) => Unit = (change, channelId) =>
    all.get(channelId).foreach(_ process change)

  private def defineResolution(
      secret: PrivateKey,
      pkt: IncomingPaymentPacket
  ): IncomingResolution = pkt match {
    case packet: IncomingPaymentPacket.FinalPacket
        if packet.payload.paymentSecret != NO_SECRET =>
      ReasonableLocal(packet, secret)
    case packet: IncomingPaymentPacket.NodeRelayPacket
        if packet.outerPayload.paymentSecret != NO_SECRET =>
      ReasonableTrampoline(packet, secret)
    case packet: IncomingPaymentPacket.ChannelRelayPacket =>
      CMD_FAIL_HTLC(
        Right(
          IncorrectOrUnknownPaymentDetails(
            packet.add.amountMsat,
            BlockHeight(LNParams.blockCount.get)
          )
        ),
        secret,
        packet.add
      )
    case packet: IncomingPaymentPacket.NodeRelayPacket =>
      CMD_FAIL_HTLC(
        Right(
          IncorrectOrUnknownPaymentDetails(
            packet.add.amountMsat,
            BlockHeight(LNParams.blockCount.get)
          )
        ),
        secret,
        packet.add
      )
    case packet: IncomingPaymentPacket.FinalPacket =>
      CMD_FAIL_HTLC(
        Right(
          IncorrectOrUnknownPaymentDetails(
            packet.add.amountMsat,
            BlockHeight(LNParams.blockCount.get)
          )
        ),
        secret,
        packet.add
      )
  }

  def initResolve(ext: UpdateAddHtlcExt): IncomingResolution =
    IncomingPaymentPacket.decrypt(
      ext.theirAdd,
      ext.remoteInfo.nodeSpecificPrivKey
    ) match {
      // Attempt to decrypt an onion with our peer-specific nodeId, this would be a routed payment
      // If fails with BadOnion: get all waiting incoming secrets and try to find a matching one

      case Left(routed: BadOnion) =>
        val ephemeralKeys = payBag.listPendingSecrets
          .map(LNParams.secret.keys.fakeInvoiceKey)
          .toList
        val decryptionResults =
          for (ephemeralKey <- ephemeralKeys)
            yield IncomingPaymentPacket.decrypt(ext.theirAdd, ephemeralKey)
        val goodResultFirst = decryptionResults.zip(ephemeralKeys) sortBy {
          case (res, _) if res.isRight => 0
          case _                       => 1
        }

        goodResultFirst.headOption.map {
          case (Right(packet), secret) => defineResolution(secret, packet)
          case (Left(fail: BadOnion), _) =>
            CMD_FAIL_MALFORMED_HTLC(fail.onionHash, fail.code, ext.theirAdd)
          case (Left(onionFail), secret) =>
            CMD_FAIL_HTLC(Right(onionFail), secret, ext.theirAdd)
        } getOrElse CMD_FAIL_MALFORMED_HTLC(
          routed.onionHash,
          routed.code,
          ext.theirAdd
        )

      case Left(onionFail) =>
        CMD_FAIL_HTLC(
          Right(onionFail),
          ext.remoteInfo.nodeSpecificPrivKey,
          ext.theirAdd
        )
      case Right(packet) =>
        defineResolution(ext.remoteInfo.nodeSpecificPrivKey, packet)
    }

  def finalizeIncoming(data: IncomingProcessorData): Unit = {
    // Let subscribers know after no incoming payment parts are left
    // payment itself may be fulfilled with preimage revealed or failed
    inProcessors -= data.fullTag
    inFinalized.onNext(data)
  }

  // CONNECTION LISTENER
  // Note that this may be sent multiple times after chain wallet reconnects
  override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit =
    allFromNode(worker.info.nodeId).foreach(_.chan process CMD_SOCKET_ONLINE)

  override def onMessage(
      worker: CommsTower.Worker,
      message: LightningMessage
  ): Unit = message match {
    case msg: TrampolineStatus =>
      opm process TrampolinePeerUpdated(worker.info.nodeId, msg)
    case msg: ChannelUpdate =>
      allFromNode(worker.info.nodeId).foreach(_.chan process msg)
    case msg: HasChannelId => sendTo(msg, msg.channelId)
    case _                 => // Do nothing
  }

  override def onHostedMessage(
      worker: CommsTower.Worker,
      message: HostedChannelMessage
  ): Unit = message match {
    case msg: HostedChannelBranding =>
      dataBag.putBranding(worker.info.nodeId, msg)
    case _ => hostedFromNode(worker.info.nodeId).foreach(_ process message)
  }

  override def onDisconnect(worker: CommsTower.Worker): Unit = {
    allFromNode(worker.info.nodeId).foreach(_.chan process CMD_SOCKET_OFFLINE)
    opm process TrampolinePeerDisconnected(worker.info.nodeId)
    Rx.ioQueue.delay(5.seconds).foreach(_ => initConnect())
  }

  // CHANNEL MANAGEMENT
  override def becomeShutDown(): Unit = {
    // Outgoing FSMs won't receive anything without channel listeners
    for (channel <- all.values) channel.listeners = Set.empty
    for (fsm <- inProcessors.values) fsm.becomeShutDown()
    for (sub <- pf.subscription) sub.unsubscribe()
    pf.listeners = Set.empty
    tb.becomeShutDown()
  }

  def initConnect(): Unit =
    all.values.flatMap(Channel.chanAndCommitsOpt).foreach { cnc =>
      // Connect to all peers with channels, including CLOSED ones
      CommsTower.listenNative(Set(me, tb), cnc.commits.remoteInfo)
    }

  // Marks as failed those payments which did not make it into channels before an app has been restarted
  def cleanupUntriedPending(): Unit = payBag.listAllPendingOutgoing
    .map(_.fullTag)
    .collect {
      case fullTag
          if fullTag.tag == PaymentTagTlv.LOCALLY_SENT && !allInChannelOutgoing
            .contains(fullTag) =>
        fullTag.paymentHash
    }
    .foreach(payBag.updAbortedOutgoing(_))

  def allInChannelOutgoing: Map[FullPaymentTag, OutgoingAdds] = all.values
    .flatMap(Channel.chanAndCommitsOpt)
    .flatMap(_.commits.allOutgoing)
    .groupBy(_.fullTag)

  def allHostedCommits: Iterable[HostedCommits] =
    all.values.flatMap(Channel.chanAndCommitsOpt).collect {
      case ChanAndCommits(_, commits: HostedCommits) => commits
    }

  def allFromNode(nodeId: PublicKey): Iterable[ChanAndCommits] = all.values
    .flatMap(Channel.chanAndCommitsOpt)
    .filter(_.commits.remoteInfo.nodeId == nodeId)

  def hostedFromNode(nodeId: PublicKey): Option[ChannelHosted] =
    allFromNode(nodeId).collectFirst {
      case ChanAndCommits(chan: ChannelHosted, _) => chan
    }

  def allNormal: Iterable[ChannelNormal] = all.values.collect {
    case chan: ChannelNormal => chan
  }

  def delayedRefunds: DelayedRefunds = {
    val commitsPublished = all.values.map(_.data).flatMap {
      case close: DATA_CLOSING => close.forceCloseCommitPublished
      case _                   => None
    }
    val spentParents = commitsPublished
      .flatMap(_.irrevocablySpent.values)
      .map(confirmedAt => confirmedAt.tx.txid -> confirmedAt)
      .toMap

    val result = for {
      delayedTx <- commitsPublished.flatMap(_.delayedRefundsLeft)
      parentTxid <- delayedTx.txIn.map(_.outPoint.txid)
      parentHeight = spentParents.get(parentTxid)
    } yield (delayedTx, parentHeight)
    DelayedRefunds(result.toMap)
  }

  // RECEIVE/SEND UTILITIES

  // It is correct to only use availableForReceive for both HC/NC and not take their maxHtlcValueInFlightMsat into account because:
  // - in NC case we always set local NC.maxHtlcValueInFlightMsat to channel capacity so NC.availableForReceive is always less than NC.maxHtlcValueInFlightMsat
  // - in HC case we don't have local HC.maxHtlcValueInFlightMsat at all and only look at HC.availableForReceive
  def operationalCncs(chans: Iterable[Channel] = Nil): Seq[ChanAndCommits] =
    chans
      .filter(Channel.isOperational)
      .flatMap(Channel.chanAndCommitsOpt)
      .toList

  def channelsContainHtlc: Boolean = operationalCncs(all.values).exists(cnc =>
    cnc.commits.allOutgoing.nonEmpty || cnc.commits.crossSignedIncoming.nonEmpty
  )

  def sortedReceivable(chans: Iterable[Channel] = Nil): Seq[ChanAndCommits] =
    operationalCncs(chans)
      .filter(_.commits.updateOpt.isDefined)
      .sortBy(_.commits.availableForReceive)

  def maxReceivable(
      sorted: Seq[ChanAndCommits] = Nil
  ): Option[CommitsAndMax] = {
    // Example: we have (5, 50, 60, 100) chans -> (50, 60, 100), receivable = 50*3 = 150, #channels = 3
    // Example: we have (25, 50, 60, 100) chans -> (25, 50, 60, 100), receivable = 50*3 = 150 (because 50*3 > 25*4), but #channels = 4
    val withoutSmall = sorted
      .dropWhile(
        _.commits.availableForReceive * sorted.size < sorted.last.commits.availableForReceive
      )
      .takeRight(4)
    val candidates =
      for (cs <- withoutSmall.indices map withoutSmall.drop)
        yield cs.head.commits.availableForReceive * cs.size
    if (candidates.isEmpty) None
    else Some(CommitsAndMax(sorted.takeRight(4), candidates.max))
  }

  def maxSendable(chans: Iterable[Channel] = Nil): MilliSatoshi = {
    val inPrincipleUsableChans = chans.filter(Channel.isOperational)
    val sendableNoFee =
      opm
        .getSendable(inPrincipleUsableChans, maxFee = MilliSatoshi(0L))
        .values
        .fold(MilliSatoshi(0))(_ + _)
    val fee = LNParams.maxOffChainFeeAboveRatio.max(
      sendableNoFee * LNParams.maxOffChainFeeRatio
    )
    MilliSatoshi(0L).max(sendableNoFee - fee)
  }

  def feeReserve(amount: MilliSatoshi): MilliSatoshi = {
    val maxPossibleFee = amount * LNParams.maxOffChainFeeRatio
    if (maxPossibleFee > LNParams.maxOffChainFeeAboveRatio) maxPossibleFee
    else LNParams.maxOffChainFeeAboveRatio
  }

  // Supply relative cltv expiry in case if we initiate a payment when chain tip is not yet known
  // An assumption is that toSend is at most maxSendable so max theoretically possible off-chain fee is already counted in
  def makeSendCmd(
      prExt: PaymentRequestExt,
      allowedChans: Seq[Channel],
      feeReserve: MilliSatoshi,
      toSend: MilliSatoshi
  ): SendMultiPart = {
    val fullTag = FullPaymentTag(
      prExt.pr.paymentHash,
      prExt.pr.paymentSecret.get,
      PaymentTagTlv.LOCALLY_SENT
    )
    val chainExpiry = Right(prExt.pr.minFinalCltvExpiryDelta)
    val splitInfo = SplitInfo(totalSum = MilliSatoshi(0L), myPart = toSend)

    SendMultiPart(
      fullTag,
      chainExpiry,
      splitInfo,
      LNParams.routerConf,
      prExt.pr.nodeId,
      expectedRouteFees = None,
      prExt.pr.paymentMetadata,
      feeReserve,
      allowedChans,
      fullTag.paymentSecret,
      prExt.extraEdges
    )
  }

  def makePrExt(
      toReceive: MilliSatoshi,
      description: PaymentDescription,
      allowedChans: Seq[ChanAndCommits],
      hash: ByteVector32,
      secret: ByteVector32
  ): PaymentRequestExt = {
    val hops = allowedChans.map(_.commits.updateOpt).zip(allowedChans).collect {
      case Some(usableUpdate) ~ ChanAndCommits(_, commits) =>
        usableUpdate.extraHop(commits.remoteInfo.nodeId) :: Nil
    }
    val pr = Bolt11Invoice(
      chainHash = LNParams.chainHash,
      amount = Some(toReceive),
      paymentHash = hash,
      paymentSecret = secret,
      privateKey = LNParams.secret.keys.fakeInvoiceKey(secret),
      description = Left(description.invoiceText),
      minFinalCltvExpiryDelta = LNParams.incomingFinalCltvExpiry,
      extraHops = hops.toList
    )
    PaymentRequestExt.from(pr)
  }

  def checkIfSendable(paymentHash: ByteVector32): PaymentInfo.PaymentSendable =
    if (
      opm.data.paymentSenders.values
        .exists(fsm =>
          fsm.fullTag.tag == PaymentTagTlv.LOCALLY_SENT && fsm.fullTag.paymentHash == paymentHash
        )
    ) PaymentInfo.NotSendableInFlight
    else if (getPreimageMemo.get(paymentHash).isSuccess)
      PaymentInfo.NotSendableSuccess
    else PaymentInfo.Sendable

  def localSend(
      cmd: SendMultiPart,
      extraListeners: Set[OutgoingPaymentListener] = Set.empty
  ): Unit = {
    // Prepare sender FSM and fetch expected fees for payment
    // these fees will be replied back to FSM for trampoline sends
    opm.createSenderFSM(
      localPaymentListeners ++ extraListeners,
      cmd.fullTag
    )
    pf process PathFinder.GetExpectedPaymentFees(opm, cmd, interHops = 3)
  }

  // These are executed in Channel context
  override def onException: PartialFunction[Malfunction, Unit] = {
    case (
          error: ExpiredHtlcInNormalChannel,
          chan: ChannelNormal,
          _: HasNormalCommitments
        ) =>
      LNParams.logBag.put(
        "channel-force-close-expired-htlc",
        error.stackTraceAsString
      )
      chan doProcess CMD_CLOSE(scriptPubKey = None, force = true)

    case (
          error: ChannelTransitionFail,
          chan: ChannelNormal,
          _: HasNormalCommitments
        ) =>
      LNParams.logBag.put("channel-force-close-error", error.stackTraceAsString)
      chan doProcess CMD_CLOSE(scriptPubKey = None, force = true)

    case (
          error: ChannelTransitionFail,
          chan: ChannelHosted,
          hc: HostedCommits
        ) =>
      LNParams.logBag.put("hosted-channel-suspend", error.stackTraceAsString)
      chan.localSuspend(hc, ErrorCodes.ERR_HOSTED_MANUAL_SUSPEND)
  }

  override def onBecome: PartialFunction[Transition, Unit] = {
    case (_, _, nextNc: DATA_NORMAL, _, _) if nextNc.localShutdown.nonEmpty =>
      next(stateUpdateStream)
    case (_, _: DATA_NORMAL, _: DATA_NEGOTIATING, _, _) =>
      next(stateUpdateStream)
    case (_, _, _, prev, Channel.Closing) if prev != Channel.Closing =>
      next(stateUpdateStream)

    case (_, prevHc: HostedCommits, nextHc: HostedCommits, _, _)
        if prevHc.error.isEmpty && nextHc.error.nonEmpty =>
      // Previously operational HC got suspended
      next(stateUpdateStream)

    case (_, prevHc: HostedCommits, nextHc: HostedCommits, _, _)
        if prevHc.error.nonEmpty && nextHc.error.isEmpty =>
      // Previously suspended HC got operational
      opm process CMDChanGotOnline
      next(stateUpdateStream)

    case (_, _, _, prev, Channel.Sleeping) if prev != Channel.Sleeping =>
      // Channel which was not SLEEPING became SLEEPING
      next(statusUpdateStream)

    case (chan, _, _, prev, Channel.Open) if prev != Channel.Open =>
      // Channel which was not open became operational and open
      // We may get here after getting fresh feerates so check again
      chan process CMD_CHECK_FEERATE
      opm process CMDChanGotOnline
      next(statusUpdateStream)
  }

  // Used to notify about an existance of preimage BEFORE new state is committed in origin channel
  // should always be followed by real or simulated state update to let incoming FSMs finalize properly
  override def fulfillReceived(fulfill: RemoteFulfill): Unit =
    opm process fulfill

  // Used to notify about outgoing adds which can not be committed, or not committed any more
  // also contains invariants which instruct outgoing FSM to abort a payment right away
  override def addRejectedLocally(reason: LocalReject): Unit =
    opm process reason

  // Used to notify about outgoing adds which were failed by peer AFTER new state is committed in origin channel
  // this means it's safe to retry amounts from these failed payments, there will be no cross-signed duplicates
  override def addRejectedRemotely(reason: RemoteReject): Unit =
    opm process reason

  override def notifyResolvers(): Unit = {
    // Used to notify FSMs that we have cross-signed incoming HTLCs which FSMs may somehow act upon
    val allIns = all.values
      .flatMap(Channel.chanAndCommitsOpt)
      .flatMap(_.commits.crossSignedIncoming)
      .map(initResolveMemo.get)
    val reasonableIncoming = allIns
      .collect { case resolution: ReasonableResolution => resolution }
      .groupBy(_.fullTag)
    val inFlightsBag =
      InFlightPayments(allInChannelOutgoing, reasonableIncoming)

    inFlightsBag.allTags.collect {
      case fullTag
          if PaymentTagTlv.TRAMPLOINE_ROUTED == fullTag.tag && !inProcessors
            .contains(fullTag) =>
        inProcessors += new TrampolinePaymentRelayer(fullTag, me).tuple
      case fullTag
          if PaymentTagTlv.FINAL_INCOMING == fullTag.tag && !inProcessors
            .contains(fullTag) =>
        inProcessors += new IncomingPaymentReceiver(fullTag, me).tuple
      case fullTag if PaymentTagTlv.LOCALLY_SENT == fullTag.tag =>
        opm.createSenderFSM(localPaymentListeners, fullTag)
    }

    // First, fail invalid and malformed incoming HTLCs right away
    allIns.foreach {
      case finalResolve: FinalResolution =>
        sendTo(finalResolve, finalResolve.theirAdd.channelId)
      case _ =>
    }
    // FSM exists because there were related HTLCs, none may be left now: this change is used by existing FSMs to finalize
    for (incomingFSM <- inProcessors.values) incomingFSM doProcess inFlightsBag
    // Sign all fails and fulfills that could have been sent from FSMs above
    for (chan <- all.values) chan process CMD_SIGN

    // Maybe remove successful outgoing FSMs
    opm.stateUpdated(inFlightsBag)

    next(stateUpdateStream)
  }

  // Mainly to prolong FSM timeouts once another add is seen (but not yet committed)
  override def addReceived(add: UpdateAddHtlcExt): Unit =
    initResolveMemo.getUnchecked(add) match {
      case resolution: ReasonableResolution =>
        inProcessors.get(resolution.fullTag).foreach(_ doProcess resolution)
      case _ => // Do nothing, invalid add will be failed after it gets committed
    }
}
