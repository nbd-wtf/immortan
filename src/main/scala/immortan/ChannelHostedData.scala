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


case class WaitRemoteHostedReply(remoteInfo: RemoteNodeInfo, refundScriptPubKey: ByteVector, secret: ByteVector) extends ChannelData

case class WaitRemoteHostedStateUpdate(remoteInfo: RemoteNodeInfo, hc: HostedCommits) extends ChannelData

case class HostedCommits(remoteInfo: RemoteNodeInfo, lastCrossSignedState: LastCrossSignedState, nextLocalUpdates: List[UpdateMessage], nextRemoteUpdates: List[UpdateMessage],
                         localSpec: CommitmentSpec, updateOpt: Option[ChannelUpdate], localError: Option[Error], remoteError: Option[Error], resizeProposal: Option[ResizeChannel] = None,
                         startedAt: Long = System.currentTimeMillis) extends PersistentChannelData with Commitments { me =>

  val nextTotalLocal: Long = lastCrossSignedState.localUpdates + nextLocalUpdates.size

  val nextTotalRemote: Long = lastCrossSignedState.remoteUpdates + nextRemoteUpdates.size

  val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)

  val channelId: ByteVector32 = Tools.hostedChanId(remoteInfo.nodeSpecificPubKey.value, remoteInfo.nodeId.value)

  val crossSignedIncoming: Set[UpdateAddHtlcExt] = for (add <- localSpec.incomingAdds) yield UpdateAddHtlcExt(add, remoteInfo)

  val allOutgoing: Set[UpdateAddHtlc] = localSpec.outgoingAdds ++ nextLocalSpec.outgoingAdds

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

  def sendFail(cmd: CMD_FAIL_HTLC): (HostedCommits, UpdateFailHtlc) = {
    val isAlreadyProposed = alreadyProposed(nextLocalUpdates, cmd.id)
    val theirAdd = localSpec.findIncomingHtlcById(cmd.id).get.add
    val ourFail = OutgoingPacket.buildHtlcFailure(cmd, theirAdd)

    if (isAlreadyProposed) throw CMDException(AlreadyProposed, cmd)
    (addLocalProposal(ourFail), ourFail)
  }

  def sendMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): (HostedCommits, UpdateFailMalformedHtlc) = {
    val ourFail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
    val isAlreadyProposed = alreadyProposed(nextLocalUpdates, cmd.id)

    require(localSpec.findIncomingHtlcById(cmd.id).isDefined)
    if (isAlreadyProposed) throw CMDException(AlreadyProposed, cmd)
    (addLocalProposal(ourFail), ourFail)
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): (HostedCommits, UpdateFulfillHtlc) = {
    val ourFulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.preimage)
    val isAlreadyProposed = alreadyProposed(nextLocalUpdates, cmd.id)
    val theirAdd = localSpec.findIncomingHtlcById(cmd.id).get.add

    if (theirAdd.paymentHash != cmd.paymentHash) throw new RuntimeException
    if (isAlreadyProposed) throw CMDException(AlreadyProposed, cmd)
    (addLocalProposal(ourFulfill), ourFulfill)
  }

  def sendAdd(cmd: CMD_ADD_HTLC): (HostedCommits, UpdateAddHtlc) = {
    val encryptedType: TlvStream[Tlv] = TlvStream(PaymentTypeTlv.EncryptedType(cmd.encryptedType) :: Nil)
    val add = UpdateAddHtlc(channelId, nextTotalLocal + 1, cmd.firstAmount, cmd.paymentType.paymentHash, cmd.cltvExpiry, cmd.packetAndSecrets.packet, encryptedType)
    val commits1: HostedCommits = addLocalProposal(add)

    if (commits1.nextLocalSpec.outgoingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw CMDException(new RuntimeException, cmd)
    if (commits1.nextLocalSpec.outgoingAdds.foldLeft(0L.msat)(_ + _.amountMsat) > maxInFlight) throw CMDException(new RuntimeException, cmd)
    if (commits1.nextLocalSpec.toLocal < 0L.msat) throw CMDException(new RuntimeException, cmd)
    if (cmd.payload.amount < minSendable) throw CMDException(new RuntimeException, cmd)
    (commits1, add)
  }

  def receiveAdd(add: UpdateAddHtlc): HostedCommits = {
    val commits1: HostedCommits = addRemoteProposal(add)
    if (commits1.nextLocalSpec.incomingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw new RuntimeException
    if (commits1.nextLocalSpec.incomingAdds.foldLeft(0L.msat)(_ + _.amountMsat) > maxInFlight) throw new RuntimeException
    if (commits1.nextLocalSpec.toRemote < 0L.msat) throw new RuntimeException
    if (add.id != nextTotalRemote + 1) throw new RuntimeException
    commits1
  }

  def withResize(resize: ResizeChannel): HostedCommits =
    me.modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(resize.newCapacityMsatU64)
      .modify(_.lastCrossSignedState.initHostedChannel.channelCapacityMsat).setTo(resize.newCapacity.toMilliSatoshi)
      .modify(_.localSpec.toRemote).using(_ + resize.newCapacity - lastCrossSignedState.initHostedChannel.channelCapacityMsat)
      .modify(_.resizeProposal).setTo(None)
}