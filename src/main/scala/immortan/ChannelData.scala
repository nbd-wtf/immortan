package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.HCErrorCodes._
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import immortan.crypto.{CMDAddImpossible, LightningException}
import fr.acinq.eclair.channel.HostedChannelVersion
import scodec.bits.ByteVector


case class Htlc(incoming: Boolean, add: UpdateAddHtlc) {
  require(incoming || add.internalId.isDefined, "Outgoing add must contain an internal id")
}

trait RemoteFailed {
  val ourAdd: UpdateAddHtlc
  val partId: ByteVector = ourAdd.internalId.get.data
}

case class FailAndAdd(theirFail: UpdateFailHtlc, ourAdd: UpdateAddHtlc) extends RemoteFailed

case class MalformAndAdd(theirMalform: UpdateFailMalformedHtlc, ourAdd: UpdateAddHtlc) extends RemoteFailed

case class CommitmentSpec(feeratePerKw: Long, toLocal: MilliSatoshi, toRemote: MilliSatoshi, htlcs: Set[Htlc] = Set.empty,
                          remoteFailed: Set[FailAndAdd] = Set.empty, remoteMalformed: Set[MalformAndAdd] = Set.empty,
                          localFulfilled: Set[UpdateAddHtlc] = Set.empty) {

  lazy val incomingAdds: Set[UpdateAddHtlc] = htlcs.collect { case Htlc(true, add) => add }

  lazy val outgoingAdds: Set[UpdateAddHtlc] = htlcs.collect { case Htlc(false, add) => add }

  lazy val incomingAddsSum: MilliSatoshi = incomingAdds.foldLeft(0L.msat) { case (accumulator, inAdd) => accumulator + inAdd.amountMsat }

  lazy val outgoingAddsSum: MilliSatoshi = outgoingAdds.foldLeft(0L.msat) { case (accumulator, outAdd) => accumulator + outAdd.amountMsat }

  def findHtlcById(id: Long, isIncoming: Boolean): Option[Htlc] = htlcs.find(htlc => htlc.add.id == id && htlc.incoming == isIncoming)
}

object CommitmentSpec {
  def fulfill(cs: CommitmentSpec, isIncoming: Boolean, m: UpdateFulfillHtlc): CommitmentSpec = cs.findHtlcById(m.id, isIncoming) match {
    case Some(their) if their.incoming => cs.copy(toLocal = cs.toLocal + their.add.amountMsat, localFulfilled = cs.localFulfilled + their.add, htlcs = cs.htlcs - their)
    case Some(htlc) => cs.copy(toRemote = cs.toRemote + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case None => cs
  }

  def fail(cs: CommitmentSpec, isIncoming: Boolean, m: UpdateFailHtlc): CommitmentSpec = cs.findHtlcById(m.id, isIncoming) match {
    case Some(theirAddHtlc) if theirAddHtlc.incoming => cs.copy(toRemote = cs.toRemote + theirAddHtlc.add.amountMsat, htlcs = cs.htlcs - theirAddHtlc)
    case Some(htlc) => cs.copy(remoteFailed = cs.remoteFailed + FailAndAdd(m, htlc.add), toLocal = cs.toLocal + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case None => cs
  }

  def failMalformed(cs: CommitmentSpec, isIncoming: Boolean, m: UpdateFailMalformedHtlc): CommitmentSpec = cs.findHtlcById(m.id, isIncoming) match {
    case Some(theirAddHtlc) if theirAddHtlc.incoming => cs.copy(toRemote = cs.toRemote + theirAddHtlc.add.amountMsat, htlcs = cs.htlcs - theirAddHtlc)
    case Some(htlc) => cs.copy(remoteMalformed = cs.remoteMalformed + MalformAndAdd(m, htlc.add), toLocal = cs.toLocal + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case None => cs
  }

  def plusOutgoing(m: UpdateAddHtlc, cs: CommitmentSpec): CommitmentSpec = cs.copy(htlcs = cs.htlcs + Htlc(incoming = false, add = m), toLocal = cs.toLocal - m.amountMsat)

  def plusIncoming(m: UpdateAddHtlc, cs: CommitmentSpec): CommitmentSpec = cs.copy(htlcs = cs.htlcs + Htlc(incoming = true, add = m), toRemote = cs.toRemote - m.amountMsat)

  def reduce(local: List[LightningMessage], remote: List[LightningMessage], spec1: CommitmentSpec): CommitmentSpec = {

    val spec2 = spec1.copy(remoteFailed = Set.empty, remoteMalformed = Set.empty, localFulfilled = Set.empty)
    val spec3 = local.foldLeft(spec2) { case (spec, add: UpdateAddHtlc) => plusOutgoing(add, spec) case (spec, _) => spec }
    val spec4 = remote.foldLeft(spec3) { case (spec, add: UpdateAddHtlc) => plusIncoming(add, spec) case (spec, _) => spec }

    val spec5 = local.foldLeft(spec4) {
      case (spec, message: UpdateFulfillHtlc) => fulfill(spec, isIncoming = true, message)
      case (spec, message: UpdateFailMalformedHtlc) => failMalformed(spec, isIncoming = true, message)
      case (spec, message: UpdateFailHtlc) => fail(spec, isIncoming = true, message)
      case (spec, _) => spec
    }

    remote.foldLeft(spec5) {
      case (spec, message: UpdateFulfillHtlc) => fulfill(spec, isIncoming = false, message)
      case (spec, message: UpdateFailMalformedHtlc) => failMalformed(spec, isIncoming = false, message)
      case (spec, message: UpdateFailHtlc) => fail(spec, isIncoming = false, message)
      case (spec, _) => spec
    }
  }
}

sealed trait ChannelData { val announce: NodeAnnouncementExt }

case class WaitRemoteHostedStateUpdate(announce: NodeAnnouncementExt, hc: HostedCommits) extends ChannelData

case class WaitRemoteHostedReply(announce: NodeAnnouncementExt, refundScriptPubKey: ByteVector, secret: ByteVector) extends ChannelData

case class HostedCommits(announce: NodeAnnouncementExt, lastCrossSignedState: LastCrossSignedState,
                         nextLocalUpdates: List[LightningMessage], nextRemoteUpdates: List[LightningMessage],
                         localSpec: CommitmentSpec, updateOpt: Option[ChannelUpdate], localError: Option[Error], remoteError: Option[Error],
                         resizeProposal: Option[ResizeChannel] = None, startedAt: Long = System.currentTimeMillis) extends ChannelData { me =>

  val nextTotalLocal: Long = lastCrossSignedState.localUpdates + nextLocalUpdates.size
  val nextTotalRemote: Long = lastCrossSignedState.remoteUpdates + nextRemoteUpdates.size
  lazy val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(nextLocalUpdates, nextRemoteUpdates, localSpec)
  lazy val invokeMsg: InvokeHostedChannel = InvokeHostedChannel(LNParams.chainHash, lastCrossSignedState.refundScriptPubKey, ByteVector.empty)
  lazy val unansweredIncoming: Set[UpdateAddHtlc] = localSpec.incomingAdds intersect nextLocalSpec.incomingAdds // Cross-signed MINUS already resolved by us
  lazy val allOutgoing: Set[UpdateAddHtlc] = localSpec.outgoingAdds union nextLocalSpec.outgoingAdds // Cross-signed PLUS new payments offered by us

  lazy val revealedHashes: Seq[ByteVector32] = for {
    UpdateFulfillHtlc(_, htlcId, _) <- nextLocalUpdates
    htlc <- localSpec.findHtlcById(htlcId, isIncoming = true)
  } yield htlc.add.paymentHash

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState = {
    val (incomingHtlcs, outgoingHtlcs) = nextLocalSpec.htlcs.toList.partition(_.incoming)
    LastCrossSignedState(lastCrossSignedState.isHost, lastCrossSignedState.refundScriptPubKey,
      lastCrossSignedState.initHostedChannel, blockDay, nextLocalSpec.toLocal, nextLocalSpec.toRemote, nextTotalLocal, nextTotalRemote,
      incomingHtlcs = incomingHtlcs.map(incomingHtlc => incomingHtlc.add), outgoingHtlcs = outgoingHtlcs.map(outgoingHtlc => outgoingHtlc.add),
      localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)
  }

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