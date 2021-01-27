package immortan

import fr.acinq.eclair._
import immortan.Channel._
import fr.acinq.eclair.wire._
import immortan.HCErrorCodes._
import immortan.crypto.Tools._
import fr.acinq.eclair.channel._

import fr.acinq.eclair.transactions.{CommitmentSpec, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.SatoshiLong
import scodec.bits.ByteVector


object HostedChannel {
  def make(initListeners: Set[ChannelListener], hostedData: HostedCommits, bag: ChannelBag): HostedChannel = new HostedChannel {
    def SEND(msg: LightningMessage *): Unit = CommsTower.workers.get(hostedData.announce.nodeSpecificPkap).foreach(msg foreach _.handler.process)
    def STORE(hostedData: PersistentChannelData): PersistentChannelData = bag.put(hostedData)
    listeners = initListeners
    doProcess(hostedData)
  }
}

abstract class HostedChannel extends Channel { me =>
  private var isChainHeightKnown: Boolean = false
  private var isSocketConnected: Boolean = false

  def isBlockDayOutOfSync(blockDay: Long): Boolean =
    math.abs(blockDay - LNParams.currentBlockDay) > 1

  def doProcess(change: Any): Unit = {
    Tuple3(data, change, state) match {
      case (wait @ WaitRemoteHostedReply(_, refundScriptPubKey, secret), CMD_SOCKET_ONLINE, WAIT_FOR_INIT) =>
        if (isChainHeightKnown) me SEND InvokeHostedChannel(LNParams.chainHash, refundScriptPubKey, secret)
        if (isChainHeightKnown) BECOME(wait, WAIT_FOR_ACCEPT)
        isSocketConnected = true


      case (wait @ WaitRemoteHostedReply(_, refundScriptPubKey, secret), CMD_CHAIN_TIP_KNOWN, WAIT_FOR_INIT) =>
        if (isSocketConnected) me SEND InvokeHostedChannel(LNParams.chainHash, refundScriptPubKey, secret)
        if (isSocketConnected) BECOME(wait, WAIT_FOR_ACCEPT)
        isChainHeightKnown = true


      case (WaitRemoteHostedReply(announceExt, refundScriptPubKey, _), init: InitHostedChannel, WAIT_FOR_ACCEPT) =>
        if (init.liabilityDeadlineBlockdays < LNParams.minHostedLiabilityBlockdays) throw new RuntimeException("Their liability deadline is too low")
        if (init.initialClientBalanceMsat > init.channelCapacityMsat) throw new RuntimeException("Their init balance for us is larger than capacity")
        if (init.minimalOnchainRefundAmountSatoshis > LNParams.minHostedOnChainRefund) throw new RuntimeException("Their min refund is too high")
        if (init.channelCapacityMsat < LNParams.minHostedOnChainRefund) throw new RuntimeException("Their proposed channel capacity is too low")
        if (UInt64(100000000L) > init.maxHtlcValueInFlightMsat) throw new RuntimeException("Their max value in-flight is too low")
        if (init.htlcMinimumMsat > 546000L.msat) throw new RuntimeException("Their minimal payment size is too high")
        if (init.maxAcceptedHtlcs < 1) throw new RuntimeException("They can accept too few payments")

        val localHalfSignedHC =
          restoreCommits(LastCrossSignedState(isHost = false, refundScriptPubKey, init, LNParams.currentBlockDay, init.initialClientBalanceMsat,
            init.channelCapacityMsat - init.initialClientBalanceMsat, localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil,
            localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(announceExt.nodeSpecificPrivKey), announceExt)

        BECOME(WaitRemoteHostedStateUpdate(announceExt, localHalfSignedHC), WAIT_FOR_ACCEPT)
        SEND(localHalfSignedHC.lastCrossSignedState.stateUpdate)


      case (WaitRemoteHostedStateUpdate(_, localHalfSignedHC), remoteSU: StateUpdate, WAIT_FOR_ACCEPT) =>
        val localCompleteLCSS = localHalfSignedHC.lastCrossSignedState.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
        val isRightRemoteUpdateNumber = localHalfSignedHC.lastCrossSignedState.remoteUpdates == remoteSU.localUpdates
        val isRightLocalUpdateNumber = localHalfSignedHC.lastCrossSignedState.localUpdates == remoteSU.remoteUpdates
        val isRemoteSigOk = localCompleteLCSS.verifyRemoteSig(localHalfSignedHC.announce.na.nodeId)
        val isBlockDayWrong = isBlockDayOutOfSync(remoteSU.blockDay)

        if (isBlockDayWrong) throw new RuntimeException("Their blockday is wrong")
        if (!isRemoteSigOk) throw new RuntimeException("Their signature is wrong")
        if (!isRightRemoteUpdateNumber) throw new RuntimeException("Their remote update number is wrong")
        if (!isRightLocalUpdateNumber) throw new RuntimeException("Their local update number is wrong")
        become(me STORE localHalfSignedHC.copy(lastCrossSignedState = localCompleteLCSS), OPEN)


      case (wait: WaitRemoteHostedReply, remoteLCSS: LastCrossSignedState, WAIT_FOR_ACCEPT) =>
        // We have expected InitHostedChannel but got LastCrossSignedState so this channel exists already
        // make sure our signature match and if so then become OPEN using host supplied state data
        val isLocalSigOk = remoteLCSS.verifyRemoteSig(wait.announce.nodeSpecificPubKey)
        val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(wait.announce.na.nodeId)
        val hc = restoreCommits(remoteLCSS.reverse, wait.announce)

        if (!isRemoteSigOk) localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
        else if (!isLocalSigOk) localSuspend(hc, ERR_HOSTED_WRONG_LOCAL_SIG)
        else {
          STORE_BECOME_SEND(hc, OPEN, hc.lastCrossSignedState)
          // We may have incoming HTLCs to fail or fulfill
          events.stateUpdated(hc)
        }

      // CHANNEL IS ESTABLISHED

      case (hc: HostedCommits, addHtlc: UpdateAddHtlc, OPEN) =>
        // They have sent us an incoming payment, do not store yet
        BECOME(hc.receiveAdd(addHtlc), OPEN)


      // Process their fulfill in any state to make sure we always get a preimage
      // fails/fulfills when SUSPENDED are ignored because they may fulfill afterwards
      case (hc: HostedCommits, fulfill: UpdateFulfillHtlc, SLEEPING | OPEN | SUSPENDED) =>
        // Technically peer may send a preimage any time, even if new LCSS has not been reached yet
        val isPresent = hc.nextLocalSpec.findIncomingHtlcById(fulfill.id).isDefined
        if (isPresent) BECOME(hc.addRemoteProposal(fulfill), state)
        events.fulfillReceived(fulfill)


      case (hc: HostedCommits, fail: UpdateFailHtlc, OPEN) =>
        // For both types of Fail we only consider them when channel is OPEN and only accept them if our outgoing payment has not been resolved already
        val isNotResolvedYet = hc.localSpec.findOutgoingHtlcById(fail.id).isDefined && hc.nextLocalSpec.findOutgoingHtlcById(fail.id).isDefined
        if (isNotResolvedYet) BECOME(hc.addRemoteProposal(fail), OPEN) else throw UnknownHtlcId(hc.channelId, fail.id)


      case (hc: HostedCommits, fail: UpdateFailMalformedHtlc, OPEN) =>
        if (fail.failureCode.&(FailureMessageCodecs.BADONION) == 0) throw InvalidFailureCode(hc.channelId)
        val isNotResolvedYet = hc.localSpec.findOutgoingHtlcById(fail.id).isDefined && hc.nextLocalSpec.findOutgoingHtlcById(fail.id).isDefined
        if (isNotResolvedYet) BECOME(hc.addRemoteProposal(fail), OPEN) else throw UnknownHtlcId(hc.channelId, fail.id)


      case (hc: HostedCommits, cmd: CMD_ADD_HTLC, notOpenState) if OPEN != notOpenState =>
        val unavailable = ChannelUnavailable(hc.channelId)
        throw HtlcAddImpossible(unavailable, cmd)


      case (hc: HostedCommits, cmd: CMD_ADD_HTLC, OPEN) =>
        val (hostedCommits1, updateAddHtlcMsg) = hc.sendAdd(cmd)
        BECOME(hostedCommits1, OPEN)
        SEND(updateAddHtlcMsg)
        doProcess(CMD_SIGN)


      case (hc: HostedCommits, CMD_SIGN, OPEN) if hc.nextLocalUpdates.nonEmpty || hc.resizeProposal.isDefined =>
        val nextLocalLCSS = hc.resizeProposal.map(hc.withResize).getOrElse(hc).nextLocalUnsignedLCSS(LNParams.currentBlockDay)
        SEND(nextLocalLCSS.withLocalSigOfRemote(hc.announce.nodeSpecificPrivKey).stateUpdate)


      // CMD_SIGN will be sent by ChannelMaster
      case (hc: HostedCommits, remoteSU: StateUpdate, OPEN) if hc.lastCrossSignedState.remoteSigOfLocal != remoteSU.localSigOfRemoteLCSS =>
        // First attempt a normal state update, then a resized one if signature check fails and we have a pending resize proposal
        attemptStateUpdate(remoteSU, hc)


      // In SLEEPING | SUSPENDED state we still send a preimage to get it resolved, then notify user on UI because normal resolution is impossible
      case (hc: HostedCommits, cmd: CMD_FULFILL_HTLC, SLEEPING | OPEN | SUSPENDED) if hc.unansweredIncoming.contains(cmd.add) =>
        val updateFulfill = UpdateFulfillHtlc(hc.channelId, cmd.add.id, cmd.preimage)
        STORE_BECOME_SEND(hc.addLocalProposal(updateFulfill), state, updateFulfill)


      // In SLEEPING | SUSPENDED state this will not be accepted by peer, but will make pending shard invisible to `unansweredIncoming` method
      case (hc: HostedCommits, cmd: CMD_FAIL_MALFORMED_HTLC, SLEEPING | OPEN | SUSPENDED) if hc.unansweredIncoming.contains(cmd.add) =>
        val updateFailMalformed = UpdateFailMalformedHtlc(hc.channelId, cmd.add.id, cmd.onionHash, cmd.failureCode)
        STORE_BECOME_SEND(hc.addLocalProposal(updateFailMalformed), state, updateFailMalformed)

      // TODO: CMD_FAIL_HTLC

      case (hc: HostedCommits, CMD_SOCKET_ONLINE, SLEEPING | SUSPENDED) =>
        if (isChainHeightKnown) SEND(hc.getError getOrElse hc.invokeMsg)
        isSocketConnected = true


      case (hc: HostedCommits, CMD_CHAIN_TIP_KNOWN, SLEEPING | SUSPENDED) =>
        if (isSocketConnected) SEND(hc.getError getOrElse hc.invokeMsg)
        isChainHeightKnown = true


      case (hc: HostedCommits, CMD_SOCKET_OFFLINE, OPEN) =>
        isSocketConnected = false
        BECOME(hc, SLEEPING)


      case (hc: HostedCommits, CMD_CHAIN_TIP_LOST, OPEN) =>
        isChainHeightKnown = false
        BECOME(hc, SLEEPING)


      case (hc: HostedCommits, _: InitHostedChannel, SLEEPING) =>
        // Peer has lost this channel, they may re-sync from our LCSS
        SEND(hc.lastCrossSignedState)


      // CMD_SIGN will be sent by ChannelMaster
      case (hc: HostedCommits, remoteLCSS: LastCrossSignedState, SLEEPING) if isChainHeightKnown =>
        val localLCSS: LastCrossSignedState = hc.lastCrossSignedState // In any case our LCSS is the current one
        val hc1 = hc.resizeProposal.filter(_ isRemoteResized remoteLCSS).map(hc.withResize).getOrElse(hc) // But they may have a resized one
        val weAreEven = localLCSS.remoteUpdates == remoteLCSS.localUpdates && localLCSS.localUpdates == remoteLCSS.remoteUpdates
        val weAreAhead = localLCSS.remoteUpdates > remoteLCSS.localUpdates || localLCSS.localUpdates > remoteLCSS.remoteUpdates
        val isLocalSigOk = remoteLCSS.verifyRemoteSig(hc.announce.nodeSpecificPubKey)
        val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(hc.announce.na.nodeId)

        if (!isRemoteSigOk) localSuspend(hc1, ERR_HOSTED_WRONG_REMOTE_SIG)
        else if (!isLocalSigOk) localSuspend(hc1, ERR_HOSTED_WRONG_LOCAL_SIG)
        else if (weAreAhead || weAreEven) {
          SEND(List(localLCSS) ++ hc1.resizeProposal ++ hc1.nextLocalUpdates:_*)
          BECOME(hc1.copy(nextRemoteUpdates = Nil), OPEN)
        } else {
          val localUpdatesAcked = remoteLCSS.remoteUpdates - hc1.lastCrossSignedState.localUpdates
          val remoteUpdatesAcked = remoteLCSS.localUpdates - hc1.lastCrossSignedState.remoteUpdates

          val remoteUpdatesAccounted = hc1.nextRemoteUpdates take remoteUpdatesAcked.toInt
          val localUpdatesAccounted = hc1.nextLocalUpdates take localUpdatesAcked.toInt
          val localUpdatesLeftover = hc1.nextLocalUpdates drop localUpdatesAcked.toInt

          val hc2 = hc1.copy(nextLocalUpdates = localUpdatesAccounted, nextRemoteUpdates = remoteUpdatesAccounted)
          val syncedLCSS = hc2.nextLocalUnsignedLCSS(remoteLCSS.blockDay).copy(localSigOfRemote = remoteLCSS.remoteSigOfLocal, remoteSigOfLocal = remoteLCSS.localSigOfRemote)
          val syncedCommits = hc2.copy(lastCrossSignedState = syncedLCSS, localSpec = hc2.nextLocalSpec, nextLocalUpdates = localUpdatesLeftover, nextRemoteUpdates = Nil)
          if (syncedLCSS.reverse != remoteLCSS) STORE_BECOME_SEND(restoreCommits(remoteLCSS.reverse, hc2.announce), OPEN, remoteLCSS.reverse) // We are too far behind, restore from their data
          else STORE_BECOME_SEND(syncedCommits, OPEN, List(syncedLCSS) ++ hc2.resizeProposal ++ localUpdatesLeftover:_*) // We are behind but our own future cross-signed state is reachable
        }


      case (hc: HostedCommits, upd: ChannelUpdate, OPEN | SLEEPING) if hc.updateOpt.forall(_.timestamp < upd.timestamp) =>
        val shortIdMatches = hostedShortChanId(hc.announce.nodeSpecificPubKey.value, hc.announce.na.nodeId.value) == upd.shortChannelId
        if (shortIdMatches) data = me STORE hc.copy(updateOpt = upd.toSome)


      case (hc: HostedCommits, cmd: HC_CMD_RESIZE, OPEN | SLEEPING) if hc.resizeProposal.isEmpty =>
        val capacitySat = hc.lastCrossSignedState.initHostedChannel.channelCapacityMsat.truncateToSatoshi
        val resize = ResizeChannel(capacitySat + cmd.delta).sign(hc.announce.nodeSpecificPrivKey)
        STORE_BECOME_SEND(hc.copy(resizeProposal = resize.toSome), state, resize)
        doProcess(CMD_SIGN)


      case (hc: HostedCommits, resize: ResizeChannel, OPEN | SLEEPING) if hc.resizeProposal.isEmpty =>
        // Can happen if we have sent a resize earlier, but then lost channel data and restored from their
        val isLocalSigOk = resize.verifyClientSig(hc.announce.nodeSpecificPubKey)
        if (isLocalSigOk) me STORE hc.copy(resizeProposal = resize.toSome)
        else localSuspend(hc, ERR_HOSTED_INVALID_RESIZE)


      case (hc: HostedCommits, remoteError: Error, WAIT_FOR_ACCEPT | OPEN | SLEEPING) if hc.remoteError.isEmpty =>
        BECOME(me STORE hc.copy(remoteError = remoteError.toSome), SUSPENDED)


      case (hc: HostedCommits, CMD_HOSTED_STATE_OVERRIDE(remoteSO), SUSPENDED) if isSocketConnected =>
        // User has manually accepted a proposed remote override, now make sure all remote-provided parameters check out
        val localBalance: MilliSatoshi = hc.lastCrossSignedState.initHostedChannel.channelCapacityMsat - remoteSO.localBalanceMsat

        val completeLocalLCSS =
          hc.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil, localBalanceMsat = localBalance, remoteBalanceMsat = remoteSO.localBalanceMsat,
            localUpdates = remoteSO.remoteUpdates, remoteUpdates = remoteSO.localUpdates, blockDay = remoteSO.blockDay, remoteSigOfLocal = remoteSO.localSigOfRemoteLCSS)
            .withLocalSigOfRemote(hc.announce.nodeSpecificPrivKey)

        if (localBalance < 0L.msat) throw new RuntimeException("Provided updated local balance is larger than capacity")
        if (remoteSO.localUpdates < hc.lastCrossSignedState.remoteUpdates) throw new RuntimeException("Provided local update number from remote host is wrong")
        if (remoteSO.remoteUpdates < hc.lastCrossSignedState.localUpdates) throw new RuntimeException("Provided remote update number from remote host is wrong")
        if (remoteSO.blockDay < hc.lastCrossSignedState.blockDay) throw new RuntimeException("Provided override blockday from remote host is not acceptable")
        require(completeLocalLCSS.verifyRemoteSig(hc.announce.na.nodeId), "Provided override signature from remote host is wrong")
        STORE_BECOME_SEND(restoreCommits(completeLocalLCSS, hc.announce), OPEN, completeLocalLCSS.stateUpdate)


      case (null, wait: WaitRemoteHostedReply, null) => super.become(wait, WAIT_FOR_INIT)
      case (null, hc: HostedCommits, null) if hc.getError.isDefined => super.become(hc, SUSPENDED)
      case (null, hc: HostedCommits, null) => super.become(hc, SLEEPING)
      case _ =>
    }

    // Change has been processed without failures
    events onProcessSuccess Tuple3(me, data, change)
  }

  def restoreCommits(localLCSS: LastCrossSignedState, ext: NodeAnnouncementExt): HostedCommits = {
    val inFlightHtlcs = localLCSS.incomingHtlcs.map(IncomingHtlc) ++ localLCSS.outgoingHtlcs.map(OutgoingHtlc)
    val localSpec = CommitmentSpec(FeeratePerKw(0L.sat), localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat, inFlightHtlcs.toSet)
    HostedCommits(ext, localLCSS, nextLocalUpdates = Nil, nextRemoteUpdates = Nil, localSpec, updateOpt = None, localError = None, remoteError = None)
  }

  def localSuspend(hc: HostedCommits, errCode: String): Unit = {
    val localError = Error(hc.channelId, ByteVector fromValidHex errCode)
    val hc1 = if (hc.localError.isDefined) hc else hc.copy(localError = localError.toSome)
    STORE_BECOME_SEND(hc1, SUSPENDED, localError)
  }

  def attemptStateUpdate(remoteSU: StateUpdate, hc: HostedCommits): Unit = {
    val lcss1 = hc.nextLocalUnsignedLCSS(remoteSU.blockDay).copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS).withLocalSigOfRemote(hc.announce.nodeSpecificPrivKey)
    val hc1 = hc.copy(lastCrossSignedState = lcss1, localSpec = hc.nextLocalSpec, nextLocalUpdates = Nil, nextRemoteUpdates = Nil)
    val isRemoteSigOk = lcss1.verifyRemoteSig(hc.announce.na.nodeId)
    val isBlockDayWrong = isBlockDayOutOfSync(remoteSU.blockDay)

    if (isBlockDayWrong) {
      localSuspend(hc, ERR_HOSTED_WRONG_BLOCKDAY)
    } else if (remoteSU.remoteUpdates < lcss1.localUpdates) {
      // Persist unsigned remote updates to use them on re-sync
      doProcess(CMD_SIGN)
      me STORE hc
    } else if (!isRemoteSigOk) {
      hc.resizeProposal.map(hc.withResize) match {
        case Some(resizedHC) => attemptStateUpdate(remoteSU, resizedHC)
        case None => localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
      }
    } else {
      // Send an unconditional reply state update
      STORE_BECOME_SEND(hc1, OPEN, lcss1.stateUpdate)
      // Another update once we have anything to resolve
      events.stateUpdated(hc1)
    }
  }
}