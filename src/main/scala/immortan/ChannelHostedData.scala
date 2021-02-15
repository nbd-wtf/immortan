package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.channel._
import com.softwaremill.quicklens._
import fr.acinq.eclair.transactions._
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.payment.OutgoingPacket
import scodec.bits.ByteVector
import immortan.crypto.Tools


case class WaitRemoteHostedReply(announce: NodeAnnouncementExt, refundScriptPubKey: ByteVector, secret: ByteVector) extends ChannelData

case class WaitRemoteHostedStateUpdate(announce: NodeAnnouncementExt, hc: HostedCommits) extends ChannelData

case class HostedCommits(announce: NodeAnnouncementExt, lastCrossSignedState: LastCrossSignedState, nextLocalUpdates: List[UpdateMessage],
                         nextRemoteUpdates: List[UpdateMessage], localSpec: CommitmentSpec, updateOpt: Option[ChannelUpdate],
                         localError: Option[Error], remoteError: Option[Error], resizeProposal: Option[ResizeChannel] = None,
                         startedAt: Long = System.currentTimeMillis) extends PersistentChannelData with Commitments { me =>

  val nextTotalLocal: Long = lastCrossSignedState.localUpdates + nextLocalUpdates.size

  val nextTotalRemote: Long = lastCrossSignedState.remoteUpdates + nextRemoteUpdates.size

  val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)

  val unProcessedIncoming: Set[NodeAnnounceExtAndTheirAdd] = {
    val unprocessed = localSpec.incomingAdds intersect nextLocalSpec.incomingAdds
    for (add <- unprocessed) yield NodeAnnounceExtAndTheirAdd(announce, add)
  }

  val allOutgoing: Set[UpdateAddHtlc] = localSpec.outgoingAdds ++ nextLocalSpec.outgoingAdds

  val channelId: ByteVector32 = Tools.hostedChanId(announce.nodeSpecificPubKey.value, announce.na.nodeId.value)

  val remoteRejects: Seq[RemoteReject] = nextRemoteUpdates.collect {
    case fail: UpdateFailHtlc => RemoteUpdateFail(fail, localSpec.findOutgoingHtlcById(fail.id).get.add)
    case malform: UpdateFailMalformedHtlc => RemoteUpdateMalform(malform, localSpec.findOutgoingHtlcById(malform.id).get.add)
  }

  val maxInFlight: MilliSatoshi = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat.toMilliSatoshi

  val minSendable: MilliSatoshi = lastCrossSignedState.initHostedChannel.htlcMinimumMsat

  val availableBalanceForReceive: MilliSatoshi = nextLocalSpec.toRemote

  val availableBalanceForSend: MilliSatoshi = nextLocalSpec.toLocal

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState =
    LastCrossSignedState(lastCrossSignedState.isHost, lastCrossSignedState.refundScriptPubKey, lastCrossSignedState.initHostedChannel,
      blockDay, nextLocalSpec.toLocal, nextLocalSpec.toRemote, nextTotalLocal, nextTotalRemote, nextLocalSpec.incomingAdds.toList,
      nextLocalSpec.outgoingAdds.toList, localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)

  def getError: Option[Error] = localError.orElse(remoteError)
  def addLocalProposal(update: UpdateMessage): HostedCommits = copy(nextLocalUpdates = nextLocalUpdates :+ update)
  def addRemoteProposal(update: UpdateMessage): HostedCommits = copy(nextRemoteUpdates = nextRemoteUpdates :+ update)
  def isResizingSupported: Boolean = lastCrossSignedState.initHostedChannel.version == HostedChannelVersion.RESIZABLE

  def sendFail(cmd: CMD_FAIL_HTLC): (HostedCommits, UpdateFailHtlc) = unProcessedIncoming.find(theirAddExt => cmd.id == theirAddExt.theirAdd.id) match {
    case Some(data) => OutgoingPacket.buildHtlcFailure(cmd, data.theirAdd) match { case Right(fail) => (addLocalProposal(fail), fail) case Left(error) => throw error }
    case None => throw UnknownHtlcId(channelId, cmd.id)
  }

  def sendMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): (HostedCommits, UpdateFailMalformedHtlc) = {
    val isNotProcessedYet = unProcessedIncoming.exists(theirAddExt => cmd.id == theirAddExt.theirAdd.id)
    val ourFailMalform = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
    if (cmd.failureCode.&(FailureMessageCodecs.BADONION) == 0) throw InvalidFailureCode(channelId)
    else if (isNotProcessedYet) (addLocalProposal(ourFailMalform), ourFailMalform)
    else throw UnknownHtlcId(channelId, cmd.id)
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): (HostedCommits, UpdateFulfillHtlc) = {
    val msg = UpdateFulfillHtlc(channelId, cmd.id, paymentPreimage = cmd.preimage)
    unProcessedIncoming.find(theirAddExt => cmd.id == theirAddExt.theirAdd.id) match {
      case Some(data) if data.theirAdd.paymentHash != cmd.paymentHash => throw InvalidHtlcPreimage(channelId, cmd.id)
      case None => throw UnknownHtlcId(channelId, cmd.id)
      case _ => (addLocalProposal(msg), msg)
    }
  }

  def sendAdd(cmd: CMD_ADD_HTLC): (ChannelData, UpdateAddHtlc) = {
    // Let's add this change and see if the new state violates any of constraints including those imposed by them on us, proceed only if it does not
    val add = UpdateAddHtlc(channelId, nextTotalLocal + 1, cmd.firstAmount, cmd.paymentHash, cmd.cltvExpiry, cmd.packetAndSecrets.packet)
    val commits1: HostedCommits = addLocalProposal(add)

    if (commits1.nextLocalSpec.outgoingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw CMDException(TooManyAcceptedHtlcs(channelId), cmd)
    if (commits1.nextLocalSpec.outgoingAdds.foldLeft(0L.msat)(_ + _.amountMsat) > maxInFlight) throw CMDException(HtlcValueTooHighInFlight(channelId), cmd)
    if (commits1.nextLocalSpec.toLocal < 0L.msat) throw CMDException(InsufficientFunds(channelId), cmd)
    if (cmd.payload.amount < minSendable) throw CMDException(HtlcValueTooSmall(channelId), cmd)
    (commits1, add)
  }

  def receiveAdd(add: UpdateAddHtlc): ChannelData = {
    val commits1: HostedCommits = addRemoteProposal(add)
    if (commits1.nextLocalSpec.incomingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw TooManyAcceptedHtlcs(channelId)
    if (commits1.nextLocalSpec.incomingAdds.foldLeft(0L.msat)(_ + _.amountMsat) > maxInFlight) throw HtlcValueTooHighInFlight(channelId)
    if (add.id != nextTotalRemote + 1) throw UnexpectedHtlcId(channelId, expected = nextTotalRemote + 1, actual = add.id)
    if (commits1.nextLocalSpec.toRemote < 0L.msat) throw InsufficientFunds(channelId)
    commits1
  }

  def withResize(resize: ResizeChannel): HostedCommits =
    me.modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(resize.newCapacityMsatU64)
      .modify(_.lastCrossSignedState.initHostedChannel.channelCapacityMsat).setTo(resize.newCapacity.toMilliSatoshi)
      .modify(_.localSpec.toRemote).using(_ + resize.newCapacity - lastCrossSignedState.initHostedChannel.channelCapacityMsat)
      .modify(_.resizeProposal).setTo(None)
}