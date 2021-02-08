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
import immortan.{ChainWallet, LNParams, NormalChannel}
import fr.acinq.eclair.blockchain.{PublishAsap, WatchConfirmed, WatchSpent}
import fr.acinq.bitcoin.{ByteVector32, OutPoint, Satoshi, SatoshiLong, Transaction}
import fr.acinq.eclair.wire.{ChannelReestablish, Error, FundingLocked, LightningMessage, RevokeAndAck, UpdateAddHtlc}
import fr.acinq.eclair.channel.Helpers.Closing
import scala.collection.immutable.Queue
import akka.actor.ActorRef

/**
 * Created by PM on 20/08/2015.
 */

object Channel {
  val MAX_ACCEPTED_HTLCS = 483

  val MIN_DUSTLIMIT: Satoshi = 546L.sat

  val MAX_NEGOTIATION_ITERATIONS = 20

  val MIN_CLTV_EXPIRY_DELTA: CltvExpiryDelta = CltvExpiryDelta(18)

  val MAX_CLTV_EXPIRY_DELTA: CltvExpiryDelta = CltvExpiryDelta(7 * 144)

  def doPublish(closingTx: Transaction, cw: ChainWallet, replyTo: ActorRef): Unit = {
    cw.watcher ! WatchConfirmed(replyTo, closingTx, LNParams.minDepthBlocks, BITCOIN_TX_CONFIRMED(closingTx))
    cw.watcher ! PublishAsap(closingTx)
  }

  /**
   * This helper method will publish txes only if they haven't yet reached minDepth
   */
  def publishIfNeeded(txes: Iterable[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32], cw: ChainWallet): Unit = {
    val (_, process) = txes.partition(Closing.inputsAlreadySpent(_, irrevocablySpent))
    process.foreach(tx => cw.watcher ! PublishAsap(tx))
  }

  /**
   * This helper method will watch txes only if they haven't yet reached minDepth
   */
  def watchConfirmedIfNeeded(txes: Iterable[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32], cw: ChainWallet, replyTo: ActorRef): Unit = {
    val (_, process) = txes.partition(Closing.inputsAlreadySpent(_, irrevocablySpent))
    process.foreach(tx => cw.watcher ! WatchConfirmed(replyTo, tx, LNParams.minDepthBlocks, BITCOIN_TX_CONFIRMED(tx)))

  }

  /**
   * This helper method will watch txes only if the utxo they spend hasn't already been irrevocably spent
   */
  def watchSpentIfNeeded(parentTx: Transaction, txes: Iterable[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32], cw: ChainWallet, replyTo: ActorRef): Unit = {
    val (_, process) = txes.partition(Closing.inputsAlreadySpent(_, irrevocablySpent))
    process.foreach(tx => cw.watcher ! WatchSpent(replyTo, parentTx, tx.txIn.head.outPoint.index.toInt, BITCOIN_OUTPUT_SPENT))

  }

  def doPublish(localCommitPublished: LocalCommitPublished, cw: ChainWallet, replyTo: ActorRef): Unit = {
    import localCommitPublished._

    val publishQueue = List(commitTx) ++ claimMainDelayedOutputTx ++ htlcSuccessTxs ++ htlcTimeoutTxs ++ claimHtlcDelayedTxs
    publishIfNeeded(publishQueue, irrevocablySpent, cw)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txes' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainDelayedOutputTx ++ claimHtlcDelayedTxs
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, cw, replyTo)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = htlcSuccessTxs ++ htlcTimeoutTxs
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, cw, replyTo)
  }

  def doPublish(remoteCommitPublished: RemoteCommitPublished, cw: ChainWallet, replyTo: ActorRef): Unit = {
    import remoteCommitPublished._

    val publishQueue = claimMainOutputTx ++ claimHtlcSuccessTxs ++ claimHtlcTimeoutTxs
    publishIfNeeded(publishQueue, irrevocablySpent, cw)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txes' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainOutputTx
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, cw, replyTo)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = claimHtlcTimeoutTxs ++ claimHtlcSuccessTxs
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, cw, replyTo)
  }

  def doPublish(revokedCommitPublished: RevokedCommitPublished, cw: ChainWallet, replyTo: ActorRef): Unit = {
    import revokedCommitPublished._

    val publishQueue = claimMainOutputTx ++ mainPenaltyTx ++ htlcPenaltyTxs ++ claimHtlcDelayedPenaltyTxs
    publishIfNeeded(publishQueue, irrevocablySpent, cw)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txes' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainOutputTx
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent, cw, replyTo)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = mainPenaltyTx ++ htlcPenaltyTxs
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent, cw, replyTo)
  }
}

trait NormalChannelHandler { me: NormalChannel =>
  def handleNormalSync(d: DATA_NORMAL, channelReestablish: ChannelReestablish): Unit = {
    var sendQueue = Queue.empty[LightningMessage]
    channelReestablish match {
      case ChannelReestablish(_, _, nextRemoteRevocationNumber, yourLastPerCommitmentSecret, _) if !Helpers.checkLocalCommit(d, nextRemoteRevocationNumber) =>
        // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
        // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
        if (d.commitments.announce.commitmentSecret(d.commitments.channelKeyPath, nextRemoteRevocationNumber - 1) == yourLastPerCommitmentSecret) {
          // their data checks out, we indeed seem to be using an old revoked commitment, and must absolutely *NOT* publish it, because that would be a cheating attempt and they
          // would punish us by taking all the funds in the channel
          val exc = PleasePublishYourCommitment(d.channelId)
          val error = Error(d.channelId, exc.getMessage)
          STORE_BECOME_SEND(DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(d.commitments, channelReestablish), immortan.Channel.CLOSING, error)
        } else {
          // they lied! the last per_commitment_secret they claimed to have received from us is invalid
          throw InvalidRevokedCommitProof(d.channelId, d.commitments.localCommit.index, nextRemoteRevocationNumber, yourLastPerCommitmentSecret)
        }
      case ChannelReestablish(_, nextLocalCommitmentNumber, _, _, _) if !Helpers.checkRemoteCommit(d, nextLocalCommitmentNumber) =>
        // if next_local_commit_number is more than one more our remote commitment index, it means that either we are using an outdated commitment, or they are lying
        // there is no way to make sure that they are saying the truth, the best thing to do is ask them to publish their commitment right now
        // maybe they will publish their commitment, in that case we need to remember their commitment point in order to be able to claim our outputs
        // not that if they don't comply, we could publish our own commitment (it is not stale, otherwise we would be in the case above)
        val exc = PleasePublishYourCommitment(d.channelId)
        val error = Error(d.channelId, exc.getMessage)
        STORE_BECOME_SEND(DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(d.commitments, channelReestablish), immortan.Channel.CLOSING, error)
      case _ =>
        // normal case, our data is up-to-date
        if (channelReestablish.nextLocalCommitmentNumber == 1 && d.commitments.localCommit.index == 0) {
          // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit funding_locked, otherwise it MUST NOT
          val nextPerCommitmentPoint = d.commitments.announce.commitmentPoint(d.commitments.channelKeyPath, index = 1L)
          val fundingLocked = FundingLocked(d.commitments.channelId, nextPerCommitmentPoint)
          sendQueue = sendQueue :+ fundingLocked
        }

        val (commitments1, sendQueue1) = handleSync(channelReestablish, d)
        sendQueue = sendQueue ++ sendQueue1

        // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
        d.localShutdown.foreach(localShutdown => sendQueue = sendQueue :+ localShutdown)
        BECOME(d.copy(commitments = commitments1), immortan.Channel.OPEN)
        SEND(sendQueue:_*)
    }
  }

  def handleNegotiationsSync(d: DATA_NEGOTIATING): Unit = if (d.commitments.localParams.isFunder) {
    // we could use the last closing_signed we sent, but network fees may have changed while we were offline so it is better to restart from scratch
    val (closingTx, closingSigned) = Closing.makeFirstClosingTx(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, LNParams.onChainFeeConf.feeEstimator, LNParams.onChainFeeConf.feeTargets)
    STORE_BECOME_SEND(d.copy(closingTxProposed = d.closingTxProposed :+ List(ClosingTxProposed(closingTx.tx, closingSigned))), immortan.Channel.NEGOTIATIONS, d.localShutdown, closingSigned)
  } else {
    // we start a new round of negotiation
    val closingTxProposed1 = if (d.closingTxProposed.last.isEmpty) d.closingTxProposed else d.closingTxProposed :+ Nil
    STORE_BECOME_SEND(d.copy(closingTxProposed = closingTxProposed1), immortan.Channel.NEGOTIATIONS, d.localShutdown)
  }

  def handleSync(channelReestablish: ChannelReestablish, d: HasNormalCommitments): (NormalCommits, Queue[LightningMessage]) = {
    var sendQueue = Queue.empty[LightningMessage]
    // first we clean up unacknowledged updates
    val commitments1 = d.commitments.copy(
      localChanges = d.commitments.localChanges.copy(proposed = Nil),
      remoteChanges = d.commitments.remoteChanges.copy(proposed = Nil),
      localNextHtlcId = d.commitments.localNextHtlcId - d.commitments.localChanges.proposed.collect { case u: UpdateAddHtlc => u }.size,
      remoteNextHtlcId = d.commitments.remoteNextHtlcId - d.commitments.remoteChanges.proposed.collect { case u: UpdateAddHtlc => u }.size)

    def resendRevocation: Unit = {
      // let's see the state of remote sigs
      if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber) {
        // nothing to do
      } else if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber + 1) {
        // our last revocation got lost, let's resend it
        val localPerCommitmentSecret = commitments1.announce.commitmentSecret(commitments1.channelKeyPath, d.commitments.localCommit.index - 1)
        val localNextPerCommitmentPoint = commitments1.announce.commitmentPoint(commitments1.channelKeyPath, d.commitments.localCommit.index + 1)
        val revocation = RevokeAndAck(channelId = commitments1.channelId, perCommitmentSecret = localPerCommitmentSecret, nextPerCommitmentPoint = localNextPerCommitmentPoint)
        sendQueue = sendQueue :+ revocation
      } else throw RevocationSyncError(d.channelId)
    }

    // re-sending sig/rev (in the right order)
    commitments1.remoteNextCommitInfo match {
      case Left(waitingForRevocation) if waitingForRevocation.nextRemoteCommit.index + 1 == channelReestablish.nextLocalCommitmentNumber =>
        // we had sent a new sig and were waiting for their revocation
        // they had received the new sig but their revocation was lost during the disconnection
        // they will send us the revocation, nothing to do here
        resendRevocation
      case Left(waitingForRevocation) if waitingForRevocation.nextRemoteCommit.index == channelReestablish.nextLocalCommitmentNumber =>
        // we had sent a new sig and were waiting for their revocation
        // they didn't receive the new sig because of the disconnection
        // we just resend the same updates and the same sig

        val revWasSentLast = commitments1.localCommit.index > waitingForRevocation.sentAfterLocalCommitIndex
        if (!revWasSentLast) resendRevocation

        commitments1.localChanges.signed.foreach(revocation => sendQueue = sendQueue :+ revocation)
        sendQueue = sendQueue :+ waitingForRevocation.sent

        if (revWasSentLast) resendRevocation
      case Right(_) if commitments1.remoteCommit.index + 1 == channelReestablish.nextLocalCommitmentNumber =>
        // there wasn't any sig in-flight when the disconnection occurred
        resendRevocation
      case _ => throw CommitmentSyncError(d.channelId)
    }

    (commitments1, sendQueue)
  }
}