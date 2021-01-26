package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.channel._
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.transactions.CommitmentSpec
import scodec.bits.ByteVector


case class WaitRemoteHostedStateUpdate(announce: NodeAnnouncementExt, hc: HostedCommits) extends ChannelData

case class WaitRemoteHostedReply(announce: NodeAnnouncementExt, refundScriptPubKey: ByteVector, secret: ByteVector) extends ChannelData

case class HostedCommits(announce: NodeAnnouncementExt, lastCrossSignedState: LastCrossSignedState, nextLocalUpdates: List[LightningMessage], nextRemoteUpdates: List[LightningMessage],
                         localSpec: CommitmentSpec, updateOpt: Option[ChannelUpdate], localError: Option[Error], remoteError: Option[Error], resizeProposal: Option[ResizeChannel] = None,
                         startedAt: Long = System.currentTimeMillis) extends ChannelData with Commitments { me =>

  val nextTotalLocal: Long = lastCrossSignedState.localUpdates + nextLocalUpdates.size
  val nextTotalRemote: Long = lastCrossSignedState.remoteUpdates + nextRemoteUpdates.size
  lazy val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)
  lazy val invokeMsg: InvokeHostedChannel = InvokeHostedChannel(LNParams.chainHash, lastCrossSignedState.refundScriptPubKey, ByteVector.empty)
  lazy val unansweredIncoming: Set[UpdateAddHtlc] = localSpec.incomingAdds intersect nextLocalSpec.incomingAdds // Cross-signed MINUS already resolved by us
  lazy val allOutgoing: Set[UpdateAddHtlc] = localSpec.outgoingAdds union nextLocalSpec.outgoingAdds // Cross-signed PLUS new payments offered by us
  lazy val minSendable: MilliSatoshi = lastCrossSignedState.initHostedChannel.htlcMinimumMsat
  lazy val availableBalanceForReceive: MilliSatoshi = nextLocalSpec.toRemote
  lazy val availableBalanceForSend: MilliSatoshi = nextLocalSpec.toLocal
  lazy val channelId: ByteVector32 = announce.nodeSpecificHostedChanId

  lazy val revealedHashes: Seq[ByteVector32] = {
    val ourFulfills = nextLocalUpdates.collect { case fulfill: UpdateFulfillHtlc => fulfill.id }
    ourFulfills.flatMap(localSpec.findIncomingHtlcById).map(_.add.paymentHash)
  }

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState =
    LastCrossSignedState(lastCrossSignedState.isHost, lastCrossSignedState.refundScriptPubKey,
      lastCrossSignedState.initHostedChannel, blockDay, nextLocalSpec.toLocal, nextLocalSpec.toRemote, nextTotalLocal, nextTotalRemote,
      nextLocalSpec.incomingAdds.toList, nextLocalSpec.outgoingAdds.toList, localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)

  def getError: Option[Error] = localError.orElse(remoteError)
  def addLocalProposal(update: LightningMessage): HostedCommits = copy(nextLocalUpdates = nextLocalUpdates :+ update)
  def addRemoteProposal(update: LightningMessage): HostedCommits = copy(nextRemoteUpdates = nextRemoteUpdates :+ update)
  def isResizingSupported: Boolean = lastCrossSignedState.initHostedChannel.version.isSet(HostedChannelVersion.USE_RESIZE)

  def sendAdd(cmd: CMD_ADD_HTLC): (ChannelData, UpdateAddHtlc) = {
    // Let's add this change and see if the new state violates any of constraints including those imposed by them on us, proceed only if it does not
    val add = UpdateAddHtlc(channelId, nextTotalLocal + 1, cmd.firstAmount, cmd.paymentHash, cmd.cltvExpiry, cmd.packetAndSecrets.packet, cmd.partId)
    val commits1: HostedCommits = addLocalProposal(add)

    val htlcValueInFlight = commits1.nextLocalSpec.outgoingAdds.foldLeft(0L.msat) { case (accumulator, outAdd) => accumulator + outAdd.amountMsat }
    if (UInt64(htlcValueInFlight.toLong) > lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat) throw HtlcAddImpossible(HtlcValueTooHighInFlight(channelId), cmd)
    if (commits1.nextLocalSpec.outgoingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw HtlcAddImpossible(TooManyAcceptedHtlcs(channelId), cmd)
    if (commits1.nextLocalSpec.toLocal < 0L.msat) throw HtlcAddImpossible(InsufficientFunds(channelId), cmd)
    if (cmd.payload.amount < minSendable) throw HtlcAddImpossible(HtlcValueTooSmall(channelId), cmd)
    (commits1, add)
  }

  def receiveAdd(add: UpdateAddHtlc): ChannelData = {
    val commits1: HostedCommits = addRemoteProposal(add)
    if (commits1.nextLocalSpec.toRemote < 0L.msat) throw InsufficientFunds(channelId)
    if (add.id != nextTotalRemote + 1) throw UnexpectedHtlcId(channelId, expected = nextTotalRemote + 1, actual = add.id)
    val htlcValueInFlight = commits1.nextLocalSpec.incomingAdds.foldLeft(0L.msat) { case (accumulator, outAdd) => accumulator + outAdd.amountMsat }
    if (UInt64(htlcValueInFlight.toLong) > lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat) throw HtlcValueTooHighInFlight(channelId)
    if (commits1.nextLocalSpec.incomingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw TooManyAcceptedHtlcs(channelId)
    commits1
  }

  def withResize(resize: ResizeChannel): HostedCommits =
    me.modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(resize.newCapacityMsatU64)
      .modify(_.lastCrossSignedState.initHostedChannel.channelCapacityMsat).setTo(resize.newCapacity.toMilliSatoshi)
      .modify(_.localSpec.toRemote).using(_ + resize.newCapacity - lastCrossSignedState.initHostedChannel.channelCapacityMsat)
      .modify(_.resizeProposal).setTo(None)
}