/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import com.softwaremill.quicklens._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.transactions.DirectedHtlc._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import immortan.{LNParams, RemoteNodeInfo, UpdateAddHtlcExt}
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, OnChainFeeConf}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, DeterministicWallet, SatoshiLong}
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.bitcoin.Crypto.PublicKey


case class LocalChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage] = Nil) {
  lazy val adds: List[UpdateAddHtlc] = all.collect { case add: UpdateAddHtlc => add }
  lazy val all: List[UpdateMessage] = proposed ++ signed ++ acked
}

case class RemoteChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage], signed: List[UpdateMessage] = Nil) {
  lazy val adds: List[UpdateAddHtlc] = all.collect { case add: UpdateAddHtlc => add }
  lazy val all: List[UpdateMessage] = proposed ++ signed ++ acked
}

case class Changes(ourChanges: LocalChanges, theirChanges: RemoteChanges)

case class HtlcTxAndSigs(txinfo: TransactionWithInputInfo, localSig: ByteVector64, remoteSig: ByteVector64)

case class PublishableTxs(commitTx: CommitTx, htlcTxsAndSigs: List[HtlcTxAndSigs] = Nil)

case class LocalCommit(index: Long, spec: CommitmentSpec, publishableTxs: PublishableTxs)

case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: ByteVector32, remotePerCommitmentPoint: PublicKey)

case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, sentAfterLocalCommitIndex: Long, reSignAsap: Boolean = false)


trait Commitments {
  def channelId: ByteVector32
  def remoteInfo: RemoteNodeInfo
  def updateOpt: Option[ChannelUpdate]

  def maxInFlight: MilliSatoshi
  def minSendable: MilliSatoshi
  def availableBalanceForSend: MilliSatoshi
  def availableBalanceForReceive: MilliSatoshi

  def remoteRejects: Seq[RemoteReject] // Our adds rejected and cross-signed on last state update
  def unProcessedIncoming: Set[UpdateAddHtlcExt] // Cross-signed MINUS already processed by us
  def allOutgoing: Set[UpdateAddHtlc] // Cross-signed PLUS new payments offered by us
}

case class NormalCommits(channelVersion: ChannelVersion, remoteInfo: RemoteNodeInfo, localParams: LocalParams, remoteParams: RemoteParams,
                         channelFlags: Byte, localCommit: LocalCommit, remoteCommit: RemoteCommit, localChanges: LocalChanges, remoteChanges: RemoteChanges,
                         localNextHtlcId: Long, remoteNextHtlcId: Long, remoteNextCommitInfo: Either[WaitingForRevocation, PublicKey], commitInput: InputInfo,
                         remotePerCommitmentSecrets: ShaChain, updateOpt: Option[ChannelUpdate], channelId: ByteVector32,
                         startedAt: Long = System.currentTimeMillis) extends Commitments {

  val latestRemoteCommit: RemoteCommit = remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(remoteCommit)

  val channelKeyPath: DeterministicWallet.KeyPath = remoteInfo.keyPath(localParams)

  val maxInFlight: MilliSatoshi = remoteParams.maxHtlcValueInFlightMsat.toMilliSatoshi

  val minSendable: MilliSatoshi = remoteParams.htlcMinimum.max(localParams.htlcMinimum)

  val unProcessedIncoming: Set[UpdateAddHtlcExt] = {
    val reduced = CommitmentSpec.reduce(latestRemoteCommit.spec, remoteChanges.acked, localChanges.proposed)
    for (add <- localCommit.spec.incomingAdds intersect reduced.outgoingAdds) yield UpdateAddHtlcExt(add, remoteInfo)
  }

  val allOutgoing: Set[UpdateAddHtlc] = localCommit.spec.outgoingAdds ++ remoteCommit.spec.incomingAdds ++ localChanges.adds

  val remoteRejects: Seq[RemoteReject] = remoteChanges.signed.collect {
    case fail: UpdateFailHtlc => RemoteUpdateFail(fail, remoteCommit.spec.findIncomingHtlcById(fail.id).get.add)
    case malform: UpdateFailMalformedHtlc => RemoteUpdateMalform(malform, remoteCommit.spec.findIncomingHtlcById(malform.id).get.add)
  }

  val availableBalanceForSend: MilliSatoshi = {
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val reduced = CommitmentSpec.reduce(latestRemoteCommit.spec, remoteChanges.acked, localChanges.proposed)
    val balanceNoFees = (reduced.toRemote - remoteParams.channelReserve).max(0.msat)
    if (localParams.isFunder) {
      // The funder always pays the on-chain fees, so we must subtract that from the amount we can send.
      val commitFees = commitTxFeeMsat(remoteParams.dustLimit, reduced, channelVersion.commitmentFormat)
      // the funder needs to keep a "funder fee buffer" (see explanation above)
      val funderFeeBuffer = commitTxFeeMsat(remoteParams.dustLimit, reduced.copy(feeratePerKw = reduced.feeratePerKw * 2),
        channelVersion.commitmentFormat) + htlcOutputFee(reduced.feeratePerKw * 2, channelVersion.commitmentFormat)
      val amountToReserve = commitFees.max(funderFeeBuffer)
      if (balanceNoFees - amountToReserve < offeredHtlcTrimThreshold(remoteParams.dustLimit, reduced, channelVersion.commitmentFormat)) {
        // htlc will be trimmed
        (balanceNoFees - amountToReserve).max(0.msat)
      } else {
        // htlc will have an output in the commitment tx, so there will be additional fees.
        val commitFees1 = commitFees + htlcOutputFee(reduced.feeratePerKw, channelVersion.commitmentFormat)
        // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
        val funderFeeBuffer1 = funderFeeBuffer + htlcOutputFee(reduced.feeratePerKw * 2, channelVersion.commitmentFormat)
        val amountToReserve1 = commitFees1.max(funderFeeBuffer1)
        (balanceNoFees - amountToReserve1).max(0.msat)
      }
    } else {
      // The fundee doesn't pay on-chain fees.
      balanceNoFees
    }
  }

  val availableBalanceForReceive: MilliSatoshi = {
    val reduced = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
    val balanceNoFees = (reduced.toRemote - localParams.channelReserve).max(0.msat)
    if (localParams.isFunder) {
      // The fundee doesn't pay on-chain fees so we don't take those into account when receiving.
      balanceNoFees
    } else {
      // The funder always pays the on-chain fees, so we must subtract that from the amount we can receive.
      val commitFees = commitTxFeeMsat(localParams.dustLimit, reduced, channelVersion.commitmentFormat)
      // we expected the funder to keep a "funder fee buffer" (see explanation above)
      val funderFeeBuffer = commitTxFeeMsat(localParams.dustLimit, reduced.copy(feeratePerKw = reduced.feeratePerKw * 2),
        channelVersion.commitmentFormat) + htlcOutputFee(reduced.feeratePerKw * 2, channelVersion.commitmentFormat)
      val amountToReserve = commitFees.max(funderFeeBuffer)
      if (balanceNoFees - amountToReserve < receivedHtlcTrimThreshold(localParams.dustLimit, reduced, channelVersion.commitmentFormat)) {
        // htlc will be trimmed
        (balanceNoFees - amountToReserve).max(0.msat)
      } else {
        // htlc will have an output in the commitment tx, so there will be additional fees.
        val commitFees1 = commitFees + htlcOutputFee(reduced.feeratePerKw, channelVersion.commitmentFormat)
        // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
        val funderFeeBuffer1 = funderFeeBuffer + htlcOutputFee(reduced.feeratePerKw * 2, channelVersion.commitmentFormat)
        val amountToReserve1 = commitFees1.max(funderFeeBuffer1)
        (balanceNoFees - amountToReserve1).max(0.msat)
      }
    }
  }

  def isMoreRecent(other: NormalCommits): Boolean = {
    val ourNextCommitSent = remoteCommit.index == other.remoteCommit.index && remoteNextCommitInfo.isLeft && other.remoteNextCommitInfo.isRight
    localCommit.index > other.localCommit.index || remoteCommit.index > other.remoteCommit.index || ourNextCommitSent
  }

  def hasNoPendingHtlcsOrFeeUpdate: Boolean = {
    val feeUpdate = (localChanges.signed ++ localChanges.acked ++ remoteChanges.signed ++ remoteChanges.acked).collectFirst { case _: UpdateFee => true }
    remoteNextCommitInfo.isRight && localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty && feeUpdate.isEmpty
  }
}

object NormalCommits {
  private def addLocalProposal(commitments: NormalCommits, proposal: UpdateMessage): NormalCommits = commitments.modify(_.localChanges.proposed).using(_ :+ proposal)

  private def addRemoteProposal(commitments: NormalCommits, proposal: UpdateMessage): NormalCommits = commitments.modify(_.remoteChanges.proposed).using(_ :+ proposal)

  def hasNoPendingHtlcs(commitments: NormalCommits): Boolean = commitments.localCommit.spec.htlcs.isEmpty && commitments.remoteCommit.spec.htlcs.isEmpty && commitments.remoteNextCommitInfo.isRight

  def hasPendingOrProposedHtlcs(commitments: NormalCommits): Boolean = !hasNoPendingHtlcs(commitments) || commitments.localChanges.adds.nonEmpty || commitments.remoteChanges.adds.nonEmpty

  def timedOutOutgoingHtlcs(commitments: NormalCommits, blockheight: Long): Set[UpdateAddHtlc] =
    commitments.latestRemoteCommit.spec.incomingAdds.filter(add => blockheight >= add.cltvExpiry.toLong) ++
      commitments.remoteCommit.spec.incomingAdds.filter(add => blockheight >= add.cltvExpiry.toLong) ++
      commitments.localCommit.spec.outgoingAdds.filter(add => blockheight >= add.cltvExpiry.toLong)

  def almostTimedOutIncomingHtlcs(commitments: NormalCommits, blockheight: Long, fulfillSafety: CltvExpiryDelta): Set[UpdateAddHtlc] =
    commitments.localCommit.spec.incomingAdds.filter(add => CltvExpiry(blockheight) >= add.cltvExpiry - fulfillSafety)

  def sendAdd(commitments: NormalCommits, cmd: CMD_ADD_HTLC, blockHeight: Long, feeConf: OnChainFeeConf): (NormalCommits, UpdateAddHtlc) = {
    // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
    val maxExpiry = LNParams.maxCltvExpiryDelta.toCltvExpiry(blockHeight)
    if (cmd.cltvExpiry >= maxExpiry) {
      throw CMDException(ExpiryTooBig(commitments.channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight), cmd)
    }

    if (cmd.firstAmount < commitments.minSendable) {
      throw CMDException(HtlcValueTooSmall(commitments.channelId), cmd)
    }

    // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
    // we need to verify that we're not disagreeing on feerates anymore before offering new HTLCs
    val localFeeratePerKw = feeConf.feeEstimator.getFeeratePerKw(target = feeConf.feeTargets.commitmentBlockTarget)
    if (Helpers.isFeeDiffTooHigh(localFeeratePerKw, commitments.localCommit.spec.feeratePerKw, feeConf maxFeerateMismatchFor commitments.remoteInfo.nodeId)) {
      throw CMDException(FeerateTooDifferent(commitments.channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = commitments.localCommit.spec.feeratePerKw), cmd)
    }

    // let's compute the current commitment *as seen by them* with this change taken into account
    val encryptedType: TlvStream[Tlv] = TlvStream(PaymentTypeTlv.EncryptedType(cmd.encryptedType) :: Nil)
    val add = UpdateAddHtlc(commitments.channelId, commitments.localNextHtlcId, cmd.firstAmount, cmd.paymentType.paymentHash, cmd.cltvExpiry, cmd.packetAndSecrets.packet, encryptedType)
    val commitments1 = addLocalProposal(commitments, add).copy(localNextHtlcId = commitments.localNextHtlcId + 1)
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val reduced = CommitmentSpec.reduce(commitments1.latestRemoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)
    // the HTLC we are about to create is outgoing, but from their point of view it is incoming
    val outgoingHtlcs = reduced.htlcs.collect(incoming)

    // note that the funder pays the fee, so if sender != funder, both sides will have to afford this payment
    val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced, commitments.channelVersion.commitmentFormat)
    // the funder needs to keep an extra buffer to be able to handle a x2 feerate increase and an additional htlc to avoid
    // getting the channel stuck (see https://github.com/lightningnetwork/lightning-rfc/issues/728).
    val funderFeeBuffer = commitTxFeeMsat(commitments1.remoteParams.dustLimit, reduced.copy(feeratePerKw = reduced.feeratePerKw * 2),
      commitments.channelVersion.commitmentFormat) + htlcOutputFee(reduced.feeratePerKw * 2, commitments.channelVersion.commitmentFormat)
    // NB: increasing the feerate can actually remove htlcs from the commit tx (if they fall below the trim threshold)
    // which may result in a lower commit tx fee; this is why we take the max of the two.
    val missingForSender = reduced.toRemote - commitments1.remoteParams.channelReserve - (if (commitments1.localParams.isFunder) fees.max(funderFeeBuffer.truncateToSatoshi) else 0.sat)
    val missingForReceiver = reduced.toLocal - commitments1.localParams.channelReserve - (if (commitments1.localParams.isFunder) 0.sat else fees)
    if (missingForSender < 0.msat) {
      throw CMDException(InsufficientFunds(commitments.channelId), cmd)
    } else if (missingForReceiver < 0.msat) {
      if (commitments.localParams.isFunder) {
        // receiver is fundee; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
      } else {
        throw CMDException(RemoteCannotAffordFeesForNewHtlc(commitments.channelId, amount = cmd.firstAmount, missing = -missingForReceiver.truncateToSatoshi), cmd)
      }
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since outgoingHtlcs is a Set).
    val htlcValueInFlight = outgoingHtlcs.foldLeft(0L.msat) { case (accumulator, outAdd) => accumulator + outAdd.amountMsat }
    if (commitments1.maxInFlight < htlcValueInFlight) throw CMDException(HtlcValueTooHighInFlight(commitments.channelId), cmd)
    if (outgoingHtlcs.size > commitments1.remoteParams.maxAcceptedHtlcs) throw CMDException(TooManyAcceptedHtlcs(commitments.channelId), cmd)
    (commitments1, add)
  }

  def receiveAdd(commitments: NormalCommits, add: UpdateAddHtlc, feeConf: OnChainFeeConf): NormalCommits = {
    if (add.id != commitments.remoteNextHtlcId) {
      throw UnexpectedHtlcId(commitments.channelId, expected = commitments.remoteNextHtlcId, actual = add.id)
    }

    // we used to not enforce a strictly positive minimum, hence the max(1 msat)
    val htlcMinimum = commitments.localParams.htlcMinimum.max(1.msat)
    if (add.amountMsat < htlcMinimum) {
      throw HtlcValueTooSmall(commitments.channelId)
    }

    // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
    // we need to verify that we're not disagreeing on feerates anymore before accepting new HTLCs
    val localFeeratePerKw = feeConf.feeEstimator.getFeeratePerKw(target = feeConf.feeTargets.commitmentBlockTarget)
    if (Helpers.isFeeDiffTooHigh(localFeeratePerKw, commitments.localCommit.spec.feeratePerKw, feeConf maxFeerateMismatchFor commitments.remoteInfo.nodeId)) {
      throw FeerateTooDifferent(commitments.channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = commitments.localCommit.spec.feeratePerKw)
    }

    // let's compute the current commitment *as seen by us* including this change
    val commitments1 = addRemoteProposal(commitments, add).copy(remoteNextHtlcId = commitments.remoteNextHtlcId + 1)
    val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)
    val incomingHtlcs = reduced.htlcs.collect(incoming)

    // note that the funder pays the fee, so if sender != funder, both sides will have to afford this payment
    val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced, commitments.channelVersion.commitmentFormat)
    // NB: we don't enforce the funderFeeReserve (see sendAdd) because it would confuse a remote funder that doesn't have this mitigation in place
    // We could enforce it once we're confident a large portion of the network implements it.
    val missingForSender = reduced.toRemote - commitments1.localParams.channelReserve - (if (commitments1.localParams.isFunder) 0.sat else fees)
    val missingForReceiver = reduced.toLocal - commitments1.remoteParams.channelReserve - (if (commitments1.localParams.isFunder) fees else 0.sat)
    if (missingForSender < 0.sat) {
      throw InsufficientFunds(commitments.channelId)
    } else if (missingForReceiver < 0.sat) {
      if (commitments.localParams.isFunder) {
        throw CannotAffordFees(commitments.channelId, missing = -missingForReceiver.truncateToSatoshi, reserve = commitments1.remoteParams.channelReserve, fees = fees)
      } else {
        // receiver is fundee; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
      }
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
    val htlcValueInFlight = incomingHtlcs.foldLeft(0L.msat) { case (accumulator, inAdd) => accumulator + inAdd.amountMsat }
    if (commitments1.localParams.maxHtlcValueInFlightMsat < htlcValueInFlight) throw HtlcValueTooHighInFlight(commitments.channelId)
    if (incomingHtlcs.size > commitments1.localParams.maxAcceptedHtlcs) throw TooManyAcceptedHtlcs(commitments.channelId)
    commitments1
  }

  def sendFulfill(commitments: NormalCommits, cmd: CMD_FULFILL_HTLC): (NormalCommits, UpdateFulfillHtlc) = {
    val fulfill = UpdateFulfillHtlc(commitments.channelId, cmd.id, cmd.preimage)
    commitments.latestRemoteCommit.spec.findOutgoingHtlcById(cmd.id) match {
      case Some(directed) if directed.add.paymentHash != cmd.paymentHash =>
        throw InvalidHtlcPreimage(commitments.channelId, cmd.id)
      case None => throw UnknownHtlcId(commitments.channelId, cmd.id)
      case _ => (addLocalProposal(commitments, fulfill), fulfill)
    }
  }

  def receiveFulfill(commitments: NormalCommits, fulfill: UpdateFulfillHtlc): (NormalCommits, UpdateAddHtlc) = {
    commitments.localCommit.spec.findOutgoingHtlcById(fulfill.id) match {
      case Some(directed) if directed.add.paymentHash != fulfill.paymentHash =>
        throw InvalidHtlcPreimage(commitments.channelId, fulfill.id)
      case Some(directed) => (addRemoteProposal(commitments, fulfill), directed.add)
      case None => throw UnknownHtlcId(commitments.channelId, fulfill.id)
    }
  }

  def sendFail(commitments: NormalCommits, cmd: CMD_FAIL_HTLC): (NormalCommits, UpdateFailHtlc) = {
    commitments.latestRemoteCommit.spec.findOutgoingHtlcById(cmd.id) match {
      case None => throw UnknownHtlcId(commitments.channelId, cmd.id)
      case Some(directed) =>
        OutgoingPacket.buildHtlcFailure(cmd, directed.add) match {
          case Right(fail) => (addLocalProposal(commitments, fail), fail)
          case Left(error) => throw error
        }
    }
  }

  def sendFailMalformed(commitments: NormalCommits, cmd: CMD_FAIL_MALFORMED_HTLC): (NormalCommits, UpdateFailMalformedHtlc) = {
    val fail = UpdateFailMalformedHtlc(commitments.channelId, cmd.id, cmd.onionHash, cmd.failureCode)
    val isIncorrect = (cmd.failureCode & FailureMessageCodecs.BADONION) == 0
    commitments.latestRemoteCommit.spec.findOutgoingHtlcById(cmd.id) match {
      case _ if isIncorrect => throw InvalidFailureCode(commitments.channelId)
      case None => throw UnknownHtlcId(commitments.channelId, cmd.id)
      case _ => (addLocalProposal(commitments, fail), fail)
    }
  }

  def receiveFail(commitments: NormalCommits, fail: UpdateFailHtlc): (NormalCommits, UpdateAddHtlc) = {
    commitments.localCommit.spec.findOutgoingHtlcById(fail.id) match {
      case Some(directed) => (addRemoteProposal(commitments, fail), directed.add)
      case None => throw UnknownHtlcId(commitments.channelId, fail.id)
    }
  }

  def receiveFailMalformed(commitments: NormalCommits, fail: UpdateFailMalformedHtlc): (NormalCommits, UpdateAddHtlc) = {
    val isIncorrect = (fail.failureCode & FailureMessageCodecs.BADONION) == 0

    commitments.localCommit.spec.findOutgoingHtlcById(fail.id) match {
      case _ if isIncorrect => throw InvalidFailureCode(commitments.channelId)
      case Some(directed) => (addRemoteProposal(commitments, fail), directed.add)
      case None => throw UnknownHtlcId(commitments.channelId, fail.id)
    }
  }

  def sendFee(commitments: NormalCommits, cmd: CMD_UPDATE_FEE): Option[(NormalCommits, UpdateFee)] = {
    // let's compute the current commitment *as seen by them* with this change taken into account
    val fee = UpdateFee(commitments.channelId, cmd.feeratePerKw)
    // update_fee replace each other, so we can remove previous ones
    val commitments1 = commitments.modify(_.localChanges.proposed).using(_.filter { case _: UpdateFee => false case _ => true } :+ fee)
    val reduced = CommitmentSpec.reduce(commitments1.remoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)

    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
    // we look from remote's point of view, so if local is funder remote doesn't pay the fees
    val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced, commitments.channelVersion.commitmentFormat)
    val missing = reduced.toRemote.truncateToSatoshi - commitments1.remoteParams.channelReserve - fees
    if (missing < 0.sat) None else Some(commitments1, fee)
  }

  def receiveFee(commitments: NormalCommits, fee: UpdateFee, feeConf: OnChainFeeConf): NormalCommits = {
    if (commitments.localParams.isFunder) {
      throw FundeeCannotSendUpdateFee(commitments.channelId)
    } else if (fee.feeratePerKw < FeeratePerKw.MinimumFeeratePerKw) {
      throw FeerateTooSmall(commitments.channelId, remoteFeeratePerKw = fee.feeratePerKw)
    } else {
      val localFeeratePerKw = feeConf.feeEstimator.getFeeratePerKw(target = feeConf.feeTargets.commitmentBlockTarget)
      if (Helpers.isFeeDiffTooHigh(localFeeratePerKw, fee.feeratePerKw, feeConf maxFeerateMismatchFor commitments.remoteInfo.nodeId) && hasPendingOrProposedHtlcs(commitments)) {
        throw FeerateTooDifferent(commitments.channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = fee.feeratePerKw)
      } else {
        // NB: we check that the funder can afford this new fee even if spec allows to do it at next signature
        // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
        // and it would be tricky to check if the conditions are met at signing
        // (it also means that we need to check the fee of the initial commitment tx somewhere)

        // let's compute the current commitment *as seen by us* including this change
        // update_fee replace each other, so we can remove previous ones
        val commitments1 = commitments.copy(remoteChanges = commitments.remoteChanges.copy(proposed = commitments.remoteChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
        val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)

        // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
        val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced, commitments.channelVersion.commitmentFormat)
        val missing = reduced.toRemote.truncateToSatoshi - commitments1.localParams.channelReserve - fees
        if (missing < 0.sat) {
          throw CannotAffordFees(commitments.channelId, missing = -missing, reserve = commitments1.localParams.channelReserve, fees = fees)
        } else {
          commitments1
        }
      }
    }
  }

  def localHasUnsignedOutgoingHtlcs(commitments: NormalCommits): Boolean = commitments.localChanges.proposed.collectFirst { case _: UpdateAddHtlc => true }.isDefined

  def remoteHasUnsignedOutgoingHtlcs(commitments: NormalCommits): Boolean = commitments.remoteChanges.proposed.collectFirst { case _: UpdateAddHtlc => true }.isDefined

  def remoteHasUnsignedOutgoingUpdateFee(commitments: NormalCommits): Boolean = commitments.remoteChanges.proposed.collectFirst { case _: UpdateFee => true }.isDefined

  def localHasChanges(commitments: NormalCommits): Boolean = commitments.remoteChanges.acked.nonEmpty || commitments.localChanges.proposed.nonEmpty

  def sendCommit(commitments: NormalCommits): (NormalCommits, CommitSig, RemoteCommit) = {
    import commitments._
    commitments.remoteNextCommitInfo match {
      case Right(_) if !localHasChanges(commitments) =>
        throw CannotSignWithoutChanges(commitments.channelId)
      case Right(remoteNextPerCommitmentPoint) =>
        // remote commitment will includes all local changes + remote acked changes
        val spec = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
        val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeRemoteTxs(commitments.remoteInfo, channelVersion, remoteCommit.index + 1, localParams, remoteParams, commitInput, remoteNextPerCommitmentPoint, spec)
        val sig = commitments.remoteInfo.sign(remoteCommitTx, commitments.remoteInfo.fundingPublicKey(commitments.localParams.fundingKeyPath), TxOwner.Remote, channelVersion.commitmentFormat)

        val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
        val htlcSigs = sortedHtlcTxs.map(commitments.remoteInfo.sign(_, commitments.remoteInfo.htlcPoint(channelKeyPath), remoteNextPerCommitmentPoint, TxOwner.Remote, channelVersion.commitmentFormat))

        val commitSig = CommitSig(channelId = commitments.channelId, signature = sig, htlcSignatures = htlcSigs.toList)
        val waiting = WaitingForRevocation(RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint), commitSig, commitments.localCommit.index)
        val commitments1 = commitments.copy(remoteNextCommitInfo = Left(waiting), localChanges = localChanges.copy(proposed = Nil, signed = localChanges.proposed),
          remoteChanges = remoteChanges.copy(acked = Nil, signed = remoteChanges.acked))
        (commitments1, commitSig, waiting.nextRemoteCommit)
      case Left(_) =>
        throw CannotSignBeforeRevocation(commitments.channelId)
    }
  }

  def receiveCommit(commitments: NormalCommits, commit: CommitSig): (NormalCommits, RevokeAndAck) = {
    import commitments._
    // they sent us a signature for *their* view of *our* next commit tx
    // so in terms of rev.hashes and indexes we have:
    // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
    // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
    // is about to become our current revocation hash
    // ourCommit.index + 2 -> which is about to become our next revocation hash
    // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
    // and will increment our index

    val spec = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
    val localPerCommitmentPoint = commitments.remoteInfo.commitmentPoint(channelKeyPath, commitments.localCommit.index + 1)
    val (localCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeLocalTxs(commitments.remoteInfo, channelVersion, localCommit.index + 1, localParams, remoteParams, commitInput, localPerCommitmentPoint, spec)
    val sig = commitments.remoteInfo.sign(localCommitTx, commitments.remoteInfo.fundingPublicKey(commitments.localParams.fundingKeyPath), TxOwner.Local, channelVersion.commitmentFormat)

    // no need to compute htlc sigs if commit sig doesn't check out
    val signedCommitTx = Transactions.addSigs(localCommitTx, commitments.remoteInfo.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, sig, commit.signature)
    if (Transactions.checkSpendable(signedCommitTx).isFailure) {
      throw InvalidCommitmentSignature(commitments.channelId, signedCommitTx.tx)
    }

    val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
    if (commit.htlcSignatures.size != sortedHtlcTxs.size) {
      throw HtlcSigCountMismatch(commitments.channelId, sortedHtlcTxs.size, commit.htlcSignatures.size)
    }
    val htlcSigs = sortedHtlcTxs.map(commitments.remoteInfo.sign(_, commitments.remoteInfo.htlcPoint(channelKeyPath), localPerCommitmentPoint, TxOwner.Local, channelVersion.commitmentFormat))
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
    // combine the sigs to make signed txes
    val htlcTxsAndSigs = (sortedHtlcTxs, htlcSigs, commit.htlcSignatures).zipped.toList.collect {
      case (htlcTx: HtlcTimeoutTx, localSig, remoteSig) =>
        if (Transactions.checkSpendable(Transactions.addSigs(htlcTx, localSig, remoteSig, channelVersion.commitmentFormat)).isFailure) {
          throw InvalidHtlcSignature(commitments.channelId, htlcTx.tx)
        }
        HtlcTxAndSigs(htlcTx, localSig, remoteSig)
      case (htlcTx: HtlcSuccessTx, localSig, remoteSig) =>
        // we can't check that htlc-success tx are spendable because we need the payment preimage; thus we only check the remote sig
        // we verify the signature from their point of view, where it is a remote tx
        if (!Transactions.checkSig(htlcTx, remoteSig, remoteHtlcPubkey, TxOwner.Remote, channelVersion.commitmentFormat)) {
          throw InvalidHtlcSignature(commitments.channelId, htlcTx.tx)
        }
        HtlcTxAndSigs(htlcTx, localSig, remoteSig)
    }

    // we will send our revocation preimage + our next revocation hash
    val localPerCommitmentSecret = commitments.remoteInfo.commitmentSecret(channelKeyPath, commitments.localCommit.index)
    val localNextPerCommitmentPoint = commitments.remoteInfo.commitmentPoint(channelKeyPath, commitments.localCommit.index + 2)
    val revocation = RevokeAndAck(channelId = commitments.channelId, perCommitmentSecret = localPerCommitmentSecret, nextPerCommitmentPoint = localNextPerCommitmentPoint)

    // update our commitment data
    val localCommit1 = LocalCommit(index = localCommit.index + 1, spec, publishableTxs = PublishableTxs(signedCommitTx, htlcTxsAndSigs))
    val ourChanges1 = localChanges.copy(acked = Nil)
    val theirChanges1 = remoteChanges.copy(proposed = Nil, acked = remoteChanges.acked ++ remoteChanges.proposed)
    val commitments1 = commitments.copy(localCommit = localCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1)
    (commitments1, revocation)
  }

  def receiveRevocation(commitments: NormalCommits, revocation: RevokeAndAck): NormalCommits = {
    import commitments._
    // we receive a revocation because we just sent them a sig for their next commit tx
    remoteNextCommitInfo match {
      case Left(_) if revocation.perCommitmentSecret.publicKey != remoteCommit.remotePerCommitmentPoint =>
        throw InvalidRevocation(commitments.channelId)
      case Left(d1: WaitingForRevocation) =>
        commitments.copy(
          localChanges = localChanges.copy(signed = Nil, acked = localChanges.acked ++ localChanges.signed),
          remoteChanges = remoteChanges.copy(signed = Nil),
          remoteCommit = d1.nextRemoteCommit,
          remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret.value, 0xFFFFFFFFFFFFL - commitments.remoteCommit.index))
      case Right(_) =>
        throw UnexpectedRevocation(commitments.channelId)
    }
  }

  type HtlcTimeoutTxSeq = Seq[HtlcTimeoutTx]

  type HtlcSuccessTxSeq = Seq[HtlcSuccessTx]

  def makeLocalTxs(remoteInfo: RemoteNodeInfo, channelVersion: ChannelVersion, commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams,
                   commitmentInput: InputInfo, localPerCommitmentPoint: PublicKey, spec: CommitmentSpec): (CommitTx, HtlcTimeoutTxSeq, HtlcSuccessTxSeq) = {
    val channelKeyPath = remoteInfo.keyPath(localParams)
    val localFundingPubkey = remoteInfo.fundingPublicKey(localParams.fundingKeyPath).publicKey
    val localDelayedPaymentPubkey = Generators.derivePubKey(remoteInfo.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
    val localHtlcPubkey = Generators.derivePubKey(remoteInfo.htlcPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
    val remotePaymentPubkey = if (channelVersion.hasStaticRemotekey) remoteParams.paymentBasepoint else Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(remoteInfo.paymentPoint(channelKeyPath).publicKey)
    val outputs = makeCommitTxOutputs(localParams.isFunder, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey,
      remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, localFundingPubkey, remoteParams.fundingPubKey, spec, channelVersion.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, localPaymentBasepoint, remoteParams.paymentBasepoint, localParams.isFunder, outputs)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay,
      localDelayedPaymentPubkey, spec.feeratePerKw, outputs, channelVersion.commitmentFormat)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(remoteInfo: RemoteNodeInfo, channelVersion: ChannelVersion, commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams,
                    commitmentInput: InputInfo, remotePerCommitmentPoint: PublicKey, spec: CommitmentSpec): (CommitTx, HtlcTimeoutTxSeq, HtlcSuccessTxSeq) = {
    val channelKeyPath = remoteInfo.keyPath(localParams)
    val localFundingPubkey = remoteInfo.fundingPublicKey(localParams.fundingKeyPath).publicKey
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(remoteInfo.paymentPoint(channelKeyPath).publicKey)
    val localPaymentPubkey = if (channelVersion.hasStaticRemotekey) localPaymentBasepoint else Generators.derivePubKey(localPaymentBasepoint, remotePerCommitmentPoint)
    val localHtlcPubkey = Generators.derivePubKey(remoteInfo.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
    val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(remoteInfo.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
    val outputs = makeCommitTxOutputs(!localParams.isFunder, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey,
      localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, remoteParams.fundingPubKey, localFundingPubkey, spec, channelVersion.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localPaymentBasepoint, !localParams.isFunder, outputs)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay,
      remoteDelayedPaymentPubkey, spec.feeratePerKw, outputs, channelVersion.commitmentFormat)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }
}
