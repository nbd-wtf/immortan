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
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, OnChainFeeConf}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, DeterministicWallet, SatoshiLong}
import fr.acinq.eclair.payment.OutgoingPacket
import immortan.NodeAnnouncementExt


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
case class PublishableTxs(commitTx: CommitTx, htlcTxsAndSigs: List[HtlcTxAndSigs])
case class LocalCommit(index: Long, spec: CommitmentSpec, publishableTxs: PublishableTxs)
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: ByteVector32, remotePerCommitmentPoint: PublicKey)
case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, sentAfterLocalCommitIndex: Long, reSignAsap: Boolean = false)


trait Commitments {
  val announce: NodeAnnouncementExt
  val updateOpt: Option[ChannelUpdate]
  val localSpec: CommitmentSpec
  val channelId: ByteVector32

  val minSendable: MilliSatoshi
  val availableBalanceForSend: MilliSatoshi
  val availableBalanceForReceive: MilliSatoshi

  val revealedHashes: Seq[ByteVector32] // Payment hashes of revealed but unresolved preimages
  val unansweredIncoming: Set[UpdateAddHtlc] // Cross-signed MINUS already resolved by us
  val allOutgoing: Set[UpdateAddHtlc] // Cross-signed PLUS new payments offered by us
}

case class NormalCommits(channelVersion: ChannelVersion, announce: NodeAnnouncementExt, localParams: LocalParams, remoteParams: RemoteParams,
                         channelFlags: Byte, localCommit: LocalCommit, remoteCommit: RemoteCommit, localChanges: LocalChanges, remoteChanges: RemoteChanges,
                         localNextHtlcId: Long, remoteNextHtlcId: Long, remoteNextCommitInfo: Either[WaitingForRevocation, PublicKey], commitInput: InputInfo,
                         remotePerCommitmentSecrets: ShaChain, updateOpt: Option[ChannelUpdate], channelId: ByteVector32,
                         startedAt: Long = System.currentTimeMillis) extends Commitments {

  val localSpec: CommitmentSpec = localCommit.spec

  val minSendable: MilliSatoshi = remoteParams.htlcMinimum.max(localParams.htlcMinimum)

  lazy val revealedHashes: Seq[ByteVector32] = {
    val ourFulfills = localChanges.all.collect { case fulfill: UpdateFulfillHtlc => fulfill.id }
    ourFulfills.flatMap(localCommit.spec.findIncomingHtlcById).map(_.add.paymentHash)
  }

  lazy val unansweredIncoming: Set[UpdateAddHtlc] = {
    val remote = remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(remoteCommit)
    CommitmentSpec.reduce(remote.spec, remoteChanges.acked, localChanges.proposed).outgoingAdds
  }

  lazy val allOutgoing: Set[UpdateAddHtlc] = localCommit.spec.outgoingAdds ++ remoteCommit.spec.incomingAdds ++ localChanges.adds

  // NB: when computing availableBalanceForSend and availableBalanceForReceive, the funder keeps an extra buffer on top
  // of its usual channel reserve to avoid getting channels stuck in case the on-chain feerate increases (see
  // https://github.com/lightningnetwork/lightning-rfc/issues/728 for details).
  //
  // This extra buffer (which we call "funder fee buffer") is calculated as follows:
  //  1) Simulate a x2 feerate increase and compute the corresponding commit tx fee (note that it may trim some HTLCs)
  //  2) Add the cost of adding a new untrimmed HTLC at that increased feerate. This ensures that we'll be able to
  //     actually use the channel to add new HTLCs if the feerate doubles.
  //
  // If for example the current feerate is 1000 sat/kw, the dust limit 546 sat, and we have 3 pending outgoing HTLCs for
  // respectively 1250 sat, 2000 sat and 2500 sat.
  // commit tx fee = commitWeight * feerate + 3 * htlcOutputWeight * feerate = 724 * 1000 + 3 * 172 * 1000 = 1240 sat
  // To calculate the funder fee buffer, we first double the feerate and calculate the corresponding commit tx fee.
  // By doubling the feerate, the first HTLC becomes trimmed so the result is: 724 * 2000 + 2 * 172 * 2000 = 2136 sat
  // We then add the additional fee for a potential new untrimmed HTLC: 172 * 2000 = 344 sat
  // The funder fee buffer is 2136 + 344 = 2480 sat
  //
  // If there are many pending HTLCs that are only slightly above the trim threshold, the funder fee buffer may be
  // smaller than the current commit tx fee because those HTLCs will be trimmed and the commit tx weight will decrease.
  // For example if we have 10 outgoing HTLCs of 1250 sat:
  //  - commit tx fee = 724 * 1000 + 10 * 172 * 1000 = 2444 sat
  //  - commit tx fee at twice the feerate = 724 * 2000 = 1448 sat (all HTLCs have been trimmed)
  //  - cost of an additional untrimmed HTLC = 172 * 2000 = 344 sat
  //  - funder fee buffer = 1448 + 344 = 1792 sat
  // In that case the current commit tx fee is higher than the funder fee buffer and will dominate the balance restrictions.

  lazy val channelKeyPath: DeterministicWallet.KeyPath = announce.keyPath(localParams)

  lazy val availableBalanceForSend: MilliSatoshi = {
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val remoteCommit1 = remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(remoteCommit)
    val reduced = CommitmentSpec.reduce(remoteCommit1.spec, remoteChanges.acked, localChanges.proposed)
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

  lazy val availableBalanceForReceive: MilliSatoshi = {
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
}

object NormalCommits {
  private def addLocalProposal(commitments: NormalCommits, proposal: UpdateMessage): NormalCommits = commitments.modify(_.localChanges.proposed).using(_ :+ proposal)

  private def addRemoteProposal(commitments: NormalCommits, proposal: UpdateMessage): NormalCommits = commitments.modify(_.remoteChanges.proposed).using(_ :+ proposal)

  def hasNoPendingHtlcs(commitments: NormalCommits): Boolean = commitments.localCommit.spec.htlcs.isEmpty && commitments.remoteCommit.spec.htlcs.isEmpty && commitments.remoteNextCommitInfo.isRight

  def hasPendingOrProposedHtlcs(commitments: NormalCommits): Boolean = !hasNoPendingHtlcs(commitments) || commitments.localChanges.adds.nonEmpty || commitments.remoteChanges.adds.nonEmpty

  def timedOutOutgoingHtlcs(commitments: NormalCommits, blockheight: Long): Set[UpdateAddHtlc] = {
    def expired(add: UpdateAddHtlc) = blockheight >= add.cltvExpiry.toLong

    commitments.localCommit.spec.outgoingAdds.filter(expired) ++
      commitments.remoteCommit.spec.incomingAdds.filter(expired) ++
      commitments.remoteNextCommitInfo.left.toSeq.flatMap(_.nextRemoteCommit.spec.incomingAdds.filter(expired).toSet)
  }

  /**
   * HTLCs that are close to timing out upstream are potentially dangerous. If we received the preimage for those HTLCs,
   * we need to get a remote signed updated commitment that removes those HTLCs.
   * Otherwise when we get close to the upstream timeout, we risk an on-chain race condition between their HTLC timeout
   * and our HTLC success in case of a force-close.
   */
  def almostTimedOutIncomingHtlcs(commitments: NormalCommits, blockheight: Long, fulfillSafety: CltvExpiryDelta): Set[UpdateAddHtlc] =
    commitments.localCommit.spec.incomingAdds.filter(add => blockheight >= (add.cltvExpiry - fulfillSafety).toLong)

  /**
   * Return the outgoing HTLC with the given id if it is:
   *  - signed by us in their commitment transaction (remote)
   *  - signed by them in our commitment transaction (local)
   *
   * NB: if we're in the middle of fulfilling or failing that HTLC, it will not be returned by this function.
   */
  def getOutgoingHtlcCrossSigned(commitments: NormalCommits, htlcId: Long): Option[UpdateAddHtlc] = for {
    localSigned <- commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(commitments.remoteCommit).spec.findIncomingHtlcById(htlcId)
    remoteSigned <- commitments.localCommit.spec.findOutgoingHtlcById(htlcId)
  } yield {
    require(localSigned.add == remoteSigned.add)
    localSigned.add
  }

  /**
   * Return the incoming HTLC with the given id if it is:
   *  - signed by us in their commitment transaction (remote)
   *  - signed by them in our commitment transaction (local)
   *
   * NB: if we're in the middle of fulfilling or failing that HTLC, it will not be returned by this function.
   */
  def getIncomingHtlcCrossSigned(commitments: NormalCommits, htlcId: Long): Option[UpdateAddHtlc] = for {
    localSigned <- commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(commitments.remoteCommit).spec.findOutgoingHtlcById(htlcId)
    remoteSigned <- commitments.localCommit.spec.findIncomingHtlcById(htlcId)
  } yield {
    require(localSigned.add == remoteSigned.add)
    localSigned.add
  }

  def alreadyProposed(changes: List[UpdateMessage], id: Long): Boolean = changes.exists {
    case u: UpdateFailMalformedHtlc => id == u.id
    case u: UpdateFulfillHtlc => id == u.id
    case u: UpdateFailHtlc => id == u.id
    case _ => false
  }

  /**
   *
   * @param commitments current commitments
   * @param cmd         add HTLC command
   * @return either Left(failure, error message) where failure is a failure message (see BOLT #4 and the Failure Message class) or Right(new commitments, updateAddHtlc)
   */
  def sendAdd(commitments: NormalCommits, cmd: CMD_ADD_HTLC, blockHeight: Long, feeConf: OnChainFeeConf): (NormalCommits, UpdateAddHtlc) = {
    // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
    val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
    if (cmd.cltvExpiry >= maxExpiry) {
      throw HtlcAddImpossible(ExpiryTooBig(commitments.channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight), cmd)
    }

    if (cmd.firstAmount < commitments.minSendable) {
      throw HtlcAddImpossible(HtlcValueTooSmall(commitments.channelId), cmd)
    }

    // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
    // we need to verify that we're not disagreeing on feerates anymore before offering new HTLCs
    val localFeeratePerKw = feeConf.feeEstimator.getFeeratePerKw(target = feeConf.feeTargets.commitmentBlockTarget)
    if (Helpers.isFeeDiffTooHigh(localFeeratePerKw, commitments.localCommit.spec.feeratePerKw, feeConf.maxFeerateMismatchFor(commitments.announce.na.nodeId))) {
      throw HtlcAddImpossible(FeerateTooDifferent(commitments.channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = commitments.localCommit.spec.feeratePerKw), cmd)
    }

    // let's compute the current commitment *as seen by them* with this change taken into account
    val add = UpdateAddHtlc(commitments.channelId, commitments.localNextHtlcId, cmd.firstAmount, cmd.paymentHash, cmd.cltvExpiry, cmd.packetAndSecrets.packet, cmd.partId)
    // we increment the local htlc index and add an entry to the origins map
    val commitments1 = addLocalProposal(commitments, add).copy(localNextHtlcId = commitments.localNextHtlcId + 1)
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val remoteCommit1 = commitments1.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(commitments1.remoteCommit)
    val reduced = CommitmentSpec.reduce(remoteCommit1.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)
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
      throw HtlcAddImpossible(InsufficientFunds(commitments.channelId), cmd)
    } else if (missingForReceiver < 0.msat) {
      if (commitments.localParams.isFunder) {
        // receiver is fundee; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
      } else {
        throw HtlcAddImpossible(RemoteCannotAffordFeesForNewHtlc(commitments.channelId, amount = cmd.firstAmount, missing = -missingForReceiver.truncateToSatoshi), cmd)
      }
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since outgoingHtlcs is a Set).
    val htlcValueInFlight = outgoingHtlcs.foldLeft(0L.msat) { case (accumulator, outAdd) => accumulator + outAdd.amountMsat }
    if (commitments1.remoteParams.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      throw HtlcAddImpossible(HtlcValueTooHighInFlight(commitments.channelId), cmd)
    }

    if (outgoingHtlcs.size > commitments1.remoteParams.maxAcceptedHtlcs) {
      throw HtlcAddImpossible(TooManyAcceptedHtlcs(commitments.channelId), cmd)
    }

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
    if (Helpers.isFeeDiffTooHigh(localFeeratePerKw, commitments.localCommit.spec.feeratePerKw, feeConf.maxFeerateMismatchFor(commitments.announce.na.nodeId))) {
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
    if (commitments1.localParams.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      throw HtlcValueTooHighInFlight(commitments.channelId)
    }

    if (incomingHtlcs.size > commitments1.localParams.maxAcceptedHtlcs) {
      throw TooManyAcceptedHtlcs(commitments.channelId)
    }

    commitments1
  }

  def sendFulfill(commitments: NormalCommits, cmd: CMD_FULFILL_HTLC): (NormalCommits, UpdateFulfillHtlc) =
    NormalCommits.getIncomingHtlcCrossSigned(commitments, cmd.add.id) match {
      case Some(htlc) if alreadyProposed(commitments.localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(commitments.channelId, cmd.add.id)
      case Some(htlc) if htlc.paymentHash == cmd.add.paymentHash =>
        val fulfill = UpdateFulfillHtlc(commitments.channelId, cmd.add.id, cmd.preimage)
        val commitments1 = addLocalProposal(commitments, fulfill)
        (commitments1, fulfill)
      case Some(_) => throw InvalidHtlcPreimage(commitments.channelId, cmd.add.id)
      case None => throw UnknownHtlcId(commitments.channelId, cmd.add.id)
    }

  def receiveFulfill(commitments: NormalCommits, fulfill: UpdateFulfillHtlc): (NormalCommits, UpdateAddHtlc) =
    NormalCommits.getOutgoingHtlcCrossSigned(commitments, fulfill.id) match {
      case Some(htlc) if htlc.paymentHash == fulfill.paymentHash => (addRemoteProposal(commitments, fulfill), htlc)
      case Some(_) => throw InvalidHtlcPreimage(commitments.channelId, fulfill.id)
      case None => throw UnknownHtlcId(commitments.channelId, fulfill.id)
    }

  def sendFail(commitments: NormalCommits, cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): (NormalCommits, UpdateFailHtlc) =
    NormalCommits.getIncomingHtlcCrossSigned(commitments, cmd.add.id) match {
      case Some(htlc) if alreadyProposed(commitments.localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(commitments.channelId, cmd.add.id)
      case Some(htlc) =>
        // we need the shared secret to build the error packet
        OutgoingPacket.buildHtlcFailure(nodeSecret, cmd, htlc) match {
          case Left(canNotExtractSharedSecret) => throw canNotExtractSharedSecret
          case Right(fail) => (addLocalProposal(commitments, fail), fail)
        }
      case None => throw UnknownHtlcId(commitments.channelId, cmd.add.id)
    }

  def sendFailMalformed(commitments: NormalCommits, cmd: CMD_FAIL_MALFORMED_HTLC): (NormalCommits, UpdateFailMalformedHtlc) = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
      throw InvalidFailureCode(commitments.channelId)
    } else {
      NormalCommits.getIncomingHtlcCrossSigned(commitments, cmd.add.id) match {
        case Some(htlc) if alreadyProposed(commitments.localChanges.proposed, htlc.id) =>
          // we have already sent a fail/fulfill for this htlc
          throw UnknownHtlcId(commitments.channelId, cmd.add.id)
        case Some(_) =>
          val fail = UpdateFailMalformedHtlc(commitments.channelId, cmd.add.id, cmd.onionHash, cmd.failureCode)
          val commitments1 = addLocalProposal(commitments, fail)
          (commitments1, fail)
        case None => throw UnknownHtlcId(commitments.channelId, cmd.add.id)
      }
    }
  }

  def receiveFail(commitments: NormalCommits, fail: UpdateFailHtlc): (NormalCommits, UpdateAddHtlc) =
    NormalCommits.getOutgoingHtlcCrossSigned(commitments, fail.id) match {
      case Some(htlc) => (addRemoteProposal(commitments, fail), htlc)
      case None => throw UnknownHtlcId(commitments.channelId, fail.id)
    }

  def receiveFailMalformed(commitments: NormalCommits, fail: UpdateFailMalformedHtlc): (NormalCommits, UpdateAddHtlc) = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & FailureMessageCodecs.BADONION) == 0) {
      throw InvalidFailureCode(commitments.channelId)
    } else {
      NormalCommits.getOutgoingHtlcCrossSigned(commitments, fail.id) match {
        case Some(htlc) => (addRemoteProposal(commitments, fail), htlc)
        case None => throw UnknownHtlcId(commitments.channelId, fail.id)
      }
    }
  }

  def receiveFee(commitments: NormalCommits, fee: UpdateFee, feeConf: OnChainFeeConf): NormalCommits = {
    if (commitments.localParams.isFunder) {
      throw FundeeCannotSendUpdateFee(commitments.channelId)
    } else if (fee.feeratePerKw < FeeratePerKw.MinimumFeeratePerKw) {
      throw FeerateTooSmall(commitments.channelId, remoteFeeratePerKw = fee.feeratePerKw)
    } else {
      val localFeeratePerKw = feeConf.feeEstimator.getFeeratePerKw(target = feeConf.feeTargets.commitmentBlockTarget)
      if (Helpers.isFeeDiffTooHigh(localFeeratePerKw, fee.feeratePerKw, feeConf.maxFeerateMismatchFor(commitments.announce.na.nodeId)) && hasPendingOrProposedHtlcs(commitments)) {
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

  def localHasUnsignedOutgoingHtlcs(commitments: NormalCommits): Boolean = commitments.localChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined

  def remoteHasUnsignedOutgoingHtlcs(commitments: NormalCommits): Boolean = commitments.remoteChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined

  def localHasChanges(commitments: NormalCommits): Boolean = commitments.remoteChanges.acked.nonEmpty || commitments.localChanges.proposed.nonEmpty

  def remoteHasChanges(commitments: NormalCommits): Boolean = commitments.localChanges.acked.nonEmpty || commitments.remoteChanges.proposed.nonEmpty

  def revocationPreimage(seed: ByteVector32, index: Long): ByteVector32 = ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index)

  def revocationHash(seed: ByteVector32, index: Long): ByteVector32 = Crypto.sha256(revocationPreimage(seed, index))

  def sendCommit(commitments: NormalCommits): (NormalCommits, CommitSig) = {
    import commitments._
    commitments.remoteNextCommitInfo match {
      case Right(_) if !localHasChanges(commitments) =>
        throw CannotSignWithoutChanges(commitments.channelId)
      case Right(remoteNextPerCommitmentPoint) =>
        // remote commitment will includes all local changes + remote acked changes
        val spec = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
        val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeRemoteTxs(commitments, channelVersion, remoteCommit.index + 1, localParams, remoteParams, commitInput, remoteNextPerCommitmentPoint, spec)
        val sig = commitments.announce.sign(remoteCommitTx, commitments.announce.fundingPublicKey(commitments.localParams.fundingKeyPath), TxOwner.Remote, channelVersion.commitmentFormat)

        val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
        val htlcSigs = sortedHtlcTxs.map(commitments.announce.sign(_, commitments.announce.htlcPoint(channelKeyPath), remoteNextPerCommitmentPoint, TxOwner.Remote, channelVersion.commitmentFormat))

        val commitSig = CommitSig(
          channelId = commitments.channelId,
          signature = sig,
          htlcSignatures = htlcSigs.toList)
        val commitments1 = commitments.copy(
          remoteNextCommitInfo = Left(WaitingForRevocation(RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint), commitSig, commitments.localCommit.index)),
          localChanges = localChanges.copy(proposed = Nil, signed = localChanges.proposed),
          remoteChanges = remoteChanges.copy(acked = Nil, signed = remoteChanges.acked))
        (commitments1, commitSig)
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
    val localPerCommitmentPoint = commitments.announce.commitmentPoint(channelKeyPath, commitments.localCommit.index + 1)
    val (localCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeLocalTxs(commitments, channelVersion, localCommit.index + 1, localParams, remoteParams, commitInput, localPerCommitmentPoint, spec)
    val sig = commitments.announce.sign(localCommitTx, commitments.announce.fundingPublicKey(commitments.localParams.fundingKeyPath), TxOwner.Local, channelVersion.commitmentFormat)

    // no need to compute htlc sigs if commit sig doesn't check out
    val signedCommitTx = Transactions.addSigs(localCommitTx, commitments.announce.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, sig, commit.signature)
    if (Transactions.checkSpendable(signedCommitTx).isFailure) {
      throw InvalidCommitmentSignature(commitments.channelId, signedCommitTx.tx)
    }

    val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
    if (commit.htlcSignatures.size != sortedHtlcTxs.size) {
      throw HtlcSigCountMismatch(commitments.channelId, sortedHtlcTxs.size, commit.htlcSignatures.size)
    }
    val htlcSigs = sortedHtlcTxs.map(commitments.announce.sign(_, commitments.announce.htlcPoint(channelKeyPath), localPerCommitmentPoint, TxOwner.Local, channelVersion.commitmentFormat))
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
    val localPerCommitmentSecret = commitments.announce.commitmentSecret(channelKeyPath, commitments.localCommit.index)
    val localNextPerCommitmentPoint = commitments.announce.commitmentPoint(channelKeyPath, commitments.localCommit.index + 2)
    val revocation = RevokeAndAck(
      channelId = commitments.channelId,
      perCommitmentSecret = localPerCommitmentSecret,
      nextPerCommitmentPoint = localNextPerCommitmentPoint
    )

    // update our commitment data
    val localCommit1 = LocalCommit(
      index = localCommit.index + 1,
      spec,
      publishableTxs = PublishableTxs(signedCommitTx, htlcTxsAndSigs))
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

  def makeLocalTxs(cs: NormalCommits,
                   channelVersion: ChannelVersion,
                   commitTxNumber: Long,
                   localParams: LocalParams,
                   remoteParams: RemoteParams,
                   commitmentInput: InputInfo,
                   localPerCommitmentPoint: PublicKey,
                   spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localFundingPubkey = cs.announce.fundingPublicKey(localParams.fundingKeyPath).publicKey
    val localDelayedPaymentPubkey = Generators.derivePubKey(cs.announce.delayedPaymentPoint(cs.channelKeyPath).publicKey, localPerCommitmentPoint)
    val localHtlcPubkey = Generators.derivePubKey(cs.announce.htlcPoint(cs.channelKeyPath).publicKey, localPerCommitmentPoint)
    val remotePaymentPubkey = if (channelVersion.hasStaticRemotekey) remoteParams.paymentBasepoint else Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(cs.announce.paymentPoint(cs.channelKeyPath).publicKey)
    val outputs = makeCommitTxOutputs(localParams.isFunder, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, localFundingPubkey, remoteParams.fundingPubKey, spec, channelVersion.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, localPaymentBasepoint, remoteParams.paymentBasepoint, localParams.isFunder, outputs)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, spec.feeratePerKw, outputs, channelVersion.commitmentFormat)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(cs: NormalCommits,
                    channelVersion: ChannelVersion,
                    commitTxNumber: Long,
                    localParams: LocalParams,
                    remoteParams: RemoteParams,
                    commitmentInput: InputInfo,
                    remotePerCommitmentPoint: PublicKey,
                    spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localFundingPubkey = cs.announce.fundingPublicKey(localParams.fundingKeyPath).publicKey
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(cs.announce.paymentPoint(cs.channelKeyPath).publicKey)
    val localPaymentPubkey = if (channelVersion.hasStaticRemotekey) localPaymentBasepoint else Generators.derivePubKey(localPaymentBasepoint, remotePerCommitmentPoint)
    val localHtlcPubkey = Generators.derivePubKey(cs.announce.htlcPoint(cs.channelKeyPath).publicKey, remotePerCommitmentPoint)
    val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(cs.announce.revocationPoint(cs.channelKeyPath).publicKey, remotePerCommitmentPoint)
    val outputs = makeCommitTxOutputs(!localParams.isFunder, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, remoteParams.fundingPubKey, localFundingPubkey, spec, channelVersion.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localPaymentBasepoint, !localParams.isFunder, outputs)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, spec.feeratePerKw, outputs, channelVersion.commitmentFormat)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }
}
