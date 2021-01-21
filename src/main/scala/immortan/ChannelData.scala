package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.HCErrorCodes._
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import immortan.crypto.{CMDAddImpossible, LightningException}
import fr.acinq.eclair.channel.HostedChannelVersion
import fr.acinq.eclair.transactions.CommitmentSpec
import scodec.bits.ByteVector


sealed trait ChannelData { val announce: NodeAnnouncementExt }

case class WaitRemoteHostedStateUpdate(announce: NodeAnnouncementExt, hc: HostedCommits) extends ChannelData

case class WaitRemoteHostedReply(announce: NodeAnnouncementExt, refundScriptPubKey: ByteVector, secret: ByteVector) extends ChannelData

case class HostedCommits(announce: NodeAnnouncementExt, lastCrossSignedState: LastCrossSignedState,
                         nextLocalUpdates: List[LightningMessage], nextRemoteUpdates: List[LightningMessage],
                         localSpec: CommitmentSpec, updateOpt: Option[ChannelUpdate], localError: Option[Error], remoteError: Option[Error],
                         resizeProposal: Option[ResizeChannel] = None, startedAt: Long = System.currentTimeMillis) extends ChannelData { me =>

  val nextTotalLocal: Long = lastCrossSignedState.localUpdates + nextLocalUpdates.size
  val nextTotalRemote: Long = lastCrossSignedState.remoteUpdates + nextRemoteUpdates.size
  lazy val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)
  lazy val invokeMsg: InvokeHostedChannel = InvokeHostedChannel(LNParams.chainHash, lastCrossSignedState.refundScriptPubKey, ByteVector.empty)
  lazy val unansweredIncoming: Set[UpdateAddHtlc] = localSpec.incomingAdds intersect nextLocalSpec.incomingAdds // Cross-signed MINUS already resolved by us
  lazy val allOutgoing: Set[UpdateAddHtlc] = localSpec.outgoingAdds union nextLocalSpec.outgoingAdds // Cross-signed PLUS new payments offered by us

  lazy val revealedHashes: Seq[ByteVector32] = for {
    UpdateFulfillHtlc(_, htlcId, _) <- nextLocalUpdates
    htlc <- localSpec.findIncomingHtlcById(htlcId)
  } yield htlc.add.paymentHash

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState =
    LastCrossSignedState(lastCrossSignedState.isHost, lastCrossSignedState.refundScriptPubKey,
      lastCrossSignedState.initHostedChannel, blockDay, nextLocalSpec.toLocal, nextLocalSpec.toRemote, nextTotalLocal, nextTotalRemote,
      nextLocalSpec.incomingAdds.toList, nextLocalSpec.outgoingAdds.toList, localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)

  def getError: Option[Error] = localError.orElse(remoteError)
  def addLocalProposal(update: LightningMessage): HostedCommits = copy(nextLocalUpdates = nextLocalUpdates :+ update)
  def addRemoteProposal(update: LightningMessage): HostedCommits = copy(nextRemoteUpdates = nextRemoteUpdates :+ update)
  def isResizingSupported: Boolean = lastCrossSignedState.initHostedChannel.version.isSet(HostedChannelVersion.USE_RESIZE)
  def currentCapacity: MilliSatoshi = lastCrossSignedState.initHostedChannel.channelCapacityMsat

  def sendAdd(cmd: CMD_ADD_HTLC): (HostedCommits, UpdateAddHtlc) = {
    val internalId: TlvStream[Tlv] = TlvStream(UpdateAddTlv.InternalId(cmd.internalId) :: Nil)
    // Let's add this change and see if the new state violates any of constraints including those imposed by them on us
    val add = UpdateAddHtlc(announce.nodeSpecificHostedChanId, nextTotalLocal + 1, cmd.firstAmount, cmd.paymentHash, cmd.cltvExpiry, cmd.packetAndSecrets.packet, internalId)
    val commits1: HostedCommits = addLocalProposal(add)

    if (commits1.nextLocalSpec.toLocal < 0L.msat) throw CMDAddImpossible(cmd, ERR_NOT_ENOUGH_BALANCE)
    if (cmd.payload.amount < lastCrossSignedState.initHostedChannel.htlcMinimumMsat) throw CMDAddImpossible(cmd, ERR_AMOUNT_TOO_SMALL)
    if (UInt64(commits1.nextLocalSpec.outgoingAddsSum.toLong) > lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat) throw CMDAddImpossible(cmd, ERR_TOO_MUCH_IN_FLIGHT)
    if (commits1.nextLocalSpec.outgoingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw CMDAddImpossible(cmd, ERR_TOO_MANY_HTLC)
    commits1 -> add
  }

  def receiveAdd(add: UpdateAddHtlc): ChannelData = {
    val commits1: HostedCommits = addRemoteProposal(add)
    if (add.id != nextTotalRemote + 1) throw new LightningException
    if (commits1.nextLocalSpec.toRemote < 0L.msat) throw new LightningException
    if (UInt64(commits1.nextLocalSpec.incomingAddsSum.toLong) > lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat) throw new LightningException
    if (commits1.nextLocalSpec.incomingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw new LightningException
    commits1
  }

  def withResize(resize: ResizeChannel): HostedCommits =
    me.modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(resize.newCapacityMsatU64)
      .modify(_.lastCrossSignedState.initHostedChannel.channelCapacityMsat).setTo(resize.newCapacity.toMilliSatoshi)
      .modify(_.localSpec.toRemote).using(_ + resize.newCapacity - lastCrossSignedState.initHostedChannel.channelCapacityMsat)
      .modify(_.resizeProposal).setTo(None)
}