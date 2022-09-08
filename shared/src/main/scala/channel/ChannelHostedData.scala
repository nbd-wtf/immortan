package immortan.channel

import scodec.bits.ByteVector
import com.softwaremill.quicklens._
import scoin._
import scoin.ln._
import scoin.hc._
import scoin.hc.HostedChannelCodecs._

import immortan._

case class WaitRemoteHostedReply(
    remoteInfo: RemoteNodeInfo,
    refundScriptPubKey: ByteVector,
    secret: ByteVector
) extends ChannelData

case class WaitRemoteHostedStateUpdate(
    remoteInfo: RemoteNodeInfo,
    hc: HostedCommits
) extends ChannelData

case class HostedCommits(
    remoteInfo: RemoteNodeInfo,
    localSpec: CommitmentSpec,
    lastCrossSignedState: LastCrossSignedState,
    nextLocalUpdates: List[UpdateMessage],
    nextRemoteUpdates: List[UpdateMessage],
    updateOpt: Option[ChannelUpdate],
    postErrorOutgoingResolvedIds: Set[Long],
    localError: Option[Error],
    remoteError: Option[Error],
    resizeProposal: Option[ResizeChannel] = None,
    overrideProposal: Option[StateOverride] = None,
    extParams: List[ExtParams] = Nil,
    startedAt: Long = System.currentTimeMillis
) extends PersistentChannelData
    with Commitments {

  lazy val error: Option[Error] = localError.orElse(remoteError)

  lazy val nextTotalLocal: Long =
    lastCrossSignedState.localUpdates + nextLocalUpdates.size

  lazy val nextTotalRemote: Long =
    lastCrossSignedState.remoteUpdates + nextRemoteUpdates.size

  lazy val nextLocalSpec: CommitmentSpec =
    CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)

  lazy val channelId: ByteVector32 =
    hostedChannelId(
      remoteInfo.nodeSpecificPubKey.value,
      remoteInfo.nodeId.value
    )

  lazy val shortChannelId: ShortChannelId =
    hostedShortChannelId(
      remoteInfo.nodeSpecificPubKey.value,
      remoteInfo.nodeId.value
    )

  lazy val allOutgoing: Set[UpdateAddHtlc] = {
    val allOutgoingAdds = localSpec.outgoingAdds ++ nextLocalSpec.outgoingAdds
    allOutgoingAdds.filterNot(add =>
      postErrorOutgoingResolvedIds contains add.id
    )
  }

  lazy val crossSignedIncoming: Set[UpdateAddHtlcExt] =
    for (theirAdd <- localSpec.incomingAdds)
      yield UpdateAddHtlcExt(theirAdd, remoteInfo)

  lazy val revealedFulfills: Set[LocalFulfill] = getPendingFulfills(
    Helpers extractRevealedPreimages nextLocalUpdates
  )

  lazy val maxSendInFlight: MilliSatoshi =
    MilliSatoshi(
      lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat.toLong
    )

  lazy val minSendable: MilliSatoshi =
    lastCrossSignedState.initHostedChannel.htlcMinimumMsat

  lazy val availableForReceive: MilliSatoshi = nextLocalSpec.toRemote

  lazy val availableForSend: MilliSatoshi = nextLocalSpec.toLocal

  override def ourBalance: MilliSatoshi = availableForSend

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState =
    LastCrossSignedState(
      lastCrossSignedState.isHost,
      lastCrossSignedState.refundScriptPubKey,
      lastCrossSignedState.initHostedChannel,
      blockDay = blockDay,
      localBalanceMsat = nextLocalSpec.toLocal,
      remoteBalanceMsat = nextLocalSpec.toRemote,
      nextTotalLocal,
      nextTotalRemote,
      nextLocalSpec.incomingAdds.toList.sortBy(_.id),
      nextLocalSpec.outgoingAdds.toList.sortBy(_.id),
      localSigOfRemote = ByteVector64.Zeroes,
      remoteSigOfLocal = ByteVector64.Zeroes
    )

  def addLocalProposal(update: UpdateMessage): HostedCommits =
    copy(nextLocalUpdates = nextLocalUpdates :+ update)

  def addRemoteProposal(update: UpdateMessage): HostedCommits =
    copy(nextRemoteUpdates = nextRemoteUpdates :+ update)

  type UpdatedHCAndAdd = (HostedCommits, UpdateAddHtlc)
  def sendAdd(
      cmd: CMD_ADD_HTLC,
      blockHeight: Long
  ): Either[LocalReject, UpdatedHCAndAdd] = {
    val completeAdd =
      cmd.incompleteAdd.copy(channelId = channelId, id = nextTotalLocal + 1)
    val commits1 = addLocalProposal(completeAdd)

    if (cmd.payload.amount < minSendable)
      return Left(ChannelNotAbleToSend(cmd.incompleteAdd))
    if (CltvExpiry(blockHeight) >= cmd.cltvExpiry)
      return Left(InPrincipleNotSendable(cmd.incompleteAdd))
    if (LNParams.maxCltvExpiryDelta.toCltvExpiry(blockHeight) < cmd.cltvExpiry)
      return Left(InPrincipleNotSendable(cmd.incompleteAdd))
    if (
      commits1.nextLocalSpec.outgoingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs
    ) return Left(ChannelNotAbleToSend(cmd.incompleteAdd))
    if (
      commits1.allOutgoing.foldLeft(MilliSatoshi(0L))(
        _ + _.amountMsat
      ) > maxSendInFlight
    ) return Left(ChannelNotAbleToSend(cmd.incompleteAdd))
    if (commits1.nextLocalSpec.toLocal < MilliSatoshi(0L))
      return Left(ChannelNotAbleToSend(cmd.incompleteAdd))
    Right(commits1, completeAdd)
  }

  def receiveAdd(add: UpdateAddHtlc): HostedCommits = {
    val commits1: HostedCommits = addRemoteProposal(add)
    // We do not check whether total incoming amount exceeds maxHtlcValueInFlightMsat becase we always accept up to channel capacity
    if (
      commits1.nextLocalSpec.incomingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs
    ) throw ChannelTransitionFail(channelId, add)
    if (commits1.nextLocalSpec.toRemote < MilliSatoshi(0L))
      throw ChannelTransitionFail(channelId, add)
    if (add.id != nextTotalRemote + 1)
      throw ChannelTransitionFail(channelId, add)
    commits1
  }

  // Relaxed constraints for receiveng preimages over HCs: we look at nextLocalSpec, not localSpec
  def makeRemoteFulfill(fulfill: UpdateFulfillHtlc): RemoteFulfill =
    nextLocalSpec.findOutgoingHtlcById(fulfill.id) match {
      case Some(ourAdd) if ourAdd.add.paymentHash != fulfill.paymentHash =>
        throw ChannelTransitionFail(channelId, fulfill)
      case _ if postErrorOutgoingResolvedIds.contains(fulfill.id) =>
        throw ChannelTransitionFail(channelId, fulfill)
      case Some(ourAdd) => RemoteFulfill(ourAdd.add, fulfill.paymentPreimage)
      case None         => throw ChannelTransitionFail(channelId, fulfill)
    }

  def withResize(resize: ResizeChannel): HostedCommits =
    this
      .modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat)
      .setTo(UInt64(resize.newCapacity.toMilliSatoshi.toLong))
      .modify(_.lastCrossSignedState.initHostedChannel.channelCapacityMsat)
      .setTo(resize.newCapacity.toMilliSatoshi)
      .modify(_.localSpec.toRemote)
      .using(
        _ + resize.newCapacity - lastCrossSignedState.initHostedChannel.channelCapacityMsat
      )
      .modify(_.resizeProposal)
      .setTo(None)
}
