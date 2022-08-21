package immortan

import com.softwaremill.quicklens._
import scoin.Crypto.PrivateKey
import scoin.{ByteVector32, Transaction}
import scoin.ln._
import immortan.blockchain._
import immortan.blockchain.electrum.ElectrumWallet.GenerateTxResponse
import immortan.blockchain.fee.FeeratePerKw
import immortan.channel.Helpers.Closing
import immortan.channel._
import scoin.ln.crypto.ShaChain
import scoin.ln.payment.OutgoingPaymentPacket
import scoin.ln.transactions.Transactions.TxOwner
import scoin.ln.transactions._
import scoin.ln._
import immortan.Channel._
import immortan.crypto.Tools._
import immortan.sqlite.ChannelTxFeesTable
import immortan.utils.Rx
import scodec.bits.ByteVector

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.annotation.nowarn
import scala.util.Try

object ChannelNormal {
  def make(
      initListeners: Set[ChannelListener],
      normalData: HasNormalCommitments,
      bag: ChannelBag
  ): ChannelNormal = new ChannelNormal(bag) {
    def SEND(messages: LightningMessage*): Unit = CommsTower.sendMany(
      messages,
      normalData.commitments.remoteInfo.nodeSpecificPair
    )
    def STORE(dataToStore: PersistentChannelData): PersistentChannelData =
      bag.put(dataToStore)

    listeners = initListeners
    doProcess(normalData)
  }
}

abstract class ChannelNormal(bag: ChannelBag) extends Channel {
  def watchConfirmedSpent(
      cs: NormalCommits,
      watchConfirmed: Boolean,
      watchSpent: Boolean
  ): Unit = {
    if (watchConfirmed)
      LNParams.chainWallets.watcher.send(
        WatchConfirmed(
          receiver,
          cs.commitInput.outPoint.txid,
          cs.commitInput.txOut.publicKeyScript,
          LNParams.minDepthBlocks,
          BITCOIN_FUNDING_DEPTHOK
        )
      )
    if (watchSpent)
      LNParams.chainWallets.watcher.send(
        WatchSpent(
          receiver,
          cs.commitInput.outPoint.txid,
          cs.commitInput.outPoint.index.toInt,
          cs.commitInput.txOut.publicKeyScript,
          BITCOIN_FUNDING_SPENT
        )
      )
  }

  @nowarn
  def doProcess(change: Any): Unit =
    (data, change, state) match {
      // OPENING PHASE: FUNDER FLOW
      case (null, init: INPUT_INIT_FUNDER, Channel.Initial) =>
        val ChannelKeys(
          _,
          _,
          fundingKey,
          revocationKey,
          _,
          delayedPaymentKey,
          htlcKey
        ) = init.localParams.keys
        val emptyUpfrontShutdown: TlvStream[OpenChannelTlv] = TlvStream(
          ChannelTlv UpfrontShutdownScript ByteVector.empty
        )

        val open = OpenChannel(
          LNParams.chainHash,
          init.temporaryChannelId,
          init.fundingAmount,
          init.pushAmount,
          init.localParams.dustLimit,
          init.localParams.maxHtlcValueInFlightMsat,
          init.localParams.channelReserve,
          init.localParams.htlcMinimum,
          init.initialFeeratePerKw,
          init.localParams.toSelfDelay,
          init.localParams.maxAcceptedHtlcs,
          fundingKey.publicKey,
          revocationBasepoint = revocationKey.publicKey,
          paymentBasepoint = init.localParams.walletStaticPaymentBasepoint,
          delayedPaymentBasepoint = delayedPaymentKey.publicKey,
          htlcBasepoint = htlcKey.publicKey,
          init.localParams.keys.commitmentPoint(index = 0L),
          init.channelFlags,
          emptyUpfrontShutdown
        )

        BECOME(DATA_WAIT_FOR_ACCEPT_CHANNEL(init, open), Channel.WaitForAccept)
        SEND(open)

      case (
            wait: DATA_WAIT_FOR_ACCEPT_CHANNEL,
            accept: AcceptChannel,
            Channel.WaitForAccept
          ) =>
        val newData = DATA_WAIT_FOR_FUNDING_INTERNAL(
          wait.initFunder,
          RemoteParams(
            accept.dustLimitSatoshis,
            accept.maxHtlcValueInFlightMsat,
            accept.channelReserveSatoshis,
            accept.htlcMinimumMsat,
            accept.toSelfDelay,
            accept.maxAcceptedHtlcs,
            accept.fundingPubkey,
            accept.revocationBasepoint,
            accept.paymentBasepoint,
            accept.delayedPaymentBasepoint,
            accept.htlcBasepoint
          ),
          accept.firstPerCommitmentPoint,
          wait.lastSent
        )

        Helpers.validateParamsFunder(newData.lastSent, accept)
        BECOME(newData, Channel.WaitForAccept)

      case (
            wait: DATA_WAIT_FOR_FUNDING_INTERNAL,
            realFunding: GenerateTxResponse,
            Channel.WaitForAccept
          ) =>
        val fundingOutputIndex = realFunding.tx.txOut.indexWhere(
          _.publicKeyScript == realFunding.pubKeyScriptToAmount.keys.head
        )

        val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) =
          Helpers.Funding.makeFirstCommitTxs(
            wait.initFunder.channelFeatures,
            wait.initFunder.localParams,
            wait.remoteParams,
            realFunding.pubKeyScriptToAmount.values.head,
            wait.initFunder.pushAmount,
            wait.initFunder.initialFeeratePerKw,
            realFunding.tx.hash,
            fundingOutputIndex,
            wait.remoteFirstPerCommitmentPoint
          )

        require(fundingOutputIndex >= 0, "Funding input is missing")
        require(
          realFunding.pubKeyScriptToAmount.values.head == localCommitTx.input.txOut.amount,
          "Commit and funding tx amounts differ"
        )
        require(
          realFunding.pubKeyScriptToAmount.keys.head == localCommitTx.input.txOut.publicKeyScript,
          "Commit and funding tx scripts differ"
        )
        require(
          realFunding.pubKeyScriptToAmount.size == 1,
          "Funding spends to multiple destinations, should only be channel one"
        )

        val localSigOfRemoteTx = Transactions.sign(
          remoteCommitTx,
          wait.initFunder.localParams.keys.fundingKey.privateKey,
          TxOwner.Remote,
          wait.initFunder.channelFeatures.commitmentFormat
        )
        val fundingCreated = FundingCreated(
          wait.initFunder.temporaryChannelId,
          realFunding.tx.hash,
          fundingOutputIndex,
          localSigOfRemoteTx
        )
        val remoteCommit = RemoteCommit(
          index = 0L,
          remoteSpec,
          remoteCommitTx.tx.txid,
          wait.remoteFirstPerCommitmentPoint
        )

        BECOME(
          DATA_WAIT_FOR_FUNDING_SIGNED(
            wait.initFunder.remoteInfo,
            channelId = toLongId(realFunding.tx.hash, fundingOutputIndex),
            wait.initFunder.localParams,
            wait.remoteParams,
            realFunding.tx,
            realFunding.fee,
            localSpec,
            localCommitTx,
            remoteCommit,
            wait.lastSent.channelFlags,
            wait.initFunder.channelFeatures,
            fundingCreated
          ),
          Channel.WaitForAccept
        )
        SEND(fundingCreated)

      case (
            wait: DATA_WAIT_FOR_FUNDING_SIGNED,
            signed: FundingSigned,
            Channel.WaitForAccept
          ) =>
        val localSigOfLocalTx = Transactions.sign(
          wait.localCommitTx,
          wait.localParams.keys.fundingKey.privateKey,
          TxOwner.Local,
          wait.channelFeatures.commitmentFormat
        )
        val signedLocalCommitTx = Transactions.addSigs(
          wait.localCommitTx,
          wait.localParams.keys.fundingKey.publicKey,
          wait.remoteParams.fundingPubKey,
          localSigOfLocalTx,
          signed.signature
        )

        // Make sure their supplied signature is correct before committing
        require(Transactions.checkSpendable(signedLocalCommitTx).isSuccess)

        val publishableTxs = PublishableTxs(signedLocalCommitTx, Nil)
        val commits = NormalCommits(
          wait.channelFlags,
          wait.channelId,
          wait.channelFeatures,
          Right(randomKey.publicKey),
          ShaChain.init,
          updateOpt = None,
          postCloseOutgoingResolvedIds = Set.empty,
          wait.remoteInfo,
          wait.localParams,
          wait.remoteParams,
          LocalCommit(index = 0L, wait.localSpec, publishableTxs),
          wait.remoteCommit,
          LocalChanges(Nil, Nil, Nil),
          RemoteChanges(Nil, Nil, Nil),
          localNextHtlcId = 0L,
          remoteNextHtlcId = 0L,
          signedLocalCommitTx.input
        )

        watchConfirmedSpent(commits, watchConfirmed = true, watchSpent = true)
        // Persist a channel unconditionally, try to re-publish a funding tx on restart unconditionally (don't react to commit=false, we can't trust remote servers on this)
        StoreBecomeSend(
          DATA_WAIT_FOR_FUNDING_CONFIRMED(
            commits,
            Some(wait.fundingTx),
            System.currentTimeMillis,
            Left(wait.lastSent),
            deferred = None
          ),
          Channel.WaitFundingDone
        )

      // OPENING PHASE: FUNDEE FLOW

      case (null, init: INPUT_INIT_FUNDEE, Channel.Initial) =>
        val ChannelKeys(
          _,
          _,
          fundingKey,
          revocationKey,
          _,
          delayedPaymentKey,
          htlcKey
        ) = init.localParams.keys
        val emptyUpfrontShutdown: TlvStream[AcceptChannelTlv] = TlvStream(
          ChannelTlv UpfrontShutdownScript ByteVector.empty
        )

        val accept = AcceptChannel(
          init.theirOpen.temporaryChannelId,
          init.localParams.dustLimit,
          init.localParams.maxHtlcValueInFlightMsat,
          init.localParams.channelReserve,
          init.localParams.htlcMinimum,
          minimumDepth = LNParams.minDepthBlocks,
          init.localParams.toSelfDelay,
          init.localParams.maxAcceptedHtlcs,
          fundingPubkey = fundingKey.publicKey,
          revocationBasepoint = revocationKey.publicKey,
          paymentBasepoint = init.localParams.walletStaticPaymentBasepoint,
          delayedPaymentBasepoint = delayedPaymentKey.publicKey,
          htlcBasepoint = htlcKey.publicKey,
          init.localParams.keys.commitmentPoint(index = 0L),
          emptyUpfrontShutdown
        )

        BECOME(
          DATA_WAIT_FOR_FUNDING_CREATED(
            init,
            RemoteParams(
              init.theirOpen.dustLimitSatoshis,
              init.theirOpen.maxHtlcValueInFlightMsat,
              init.theirOpen.channelReserveSatoshis,
              init.theirOpen.htlcMinimumMsat,
              init.theirOpen.toSelfDelay,
              init.theirOpen.maxAcceptedHtlcs,
              init.theirOpen.fundingPubkey,
              init.theirOpen.revocationBasepoint,
              init.theirOpen.paymentBasepoint,
              init.theirOpen.delayedPaymentBasepoint,
              init.theirOpen.htlcBasepoint
            ),
            accept
          ),
          Channel.WaitForAccept
        )
        SEND(accept)

      case (
            wait: DATA_WAIT_FOR_FUNDING_CREATED,
            created: FundingCreated,
            Channel.WaitForAccept
          ) =>
        val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) =
          Helpers.Funding.makeFirstCommitTxs(
            wait.initFundee.channelFeatures,
            wait.initFundee.localParams,
            wait.remoteParams,
            wait.initFundee.theirOpen.fundingSatoshis,
            wait.initFundee.theirOpen.pushMsat,
            wait.initFundee.theirOpen.feeratePerKw,
            created.fundingTxid,
            created.fundingOutputIndex,
            wait.initFundee.theirOpen.firstPerCommitmentPoint
          )

        val localSigOfLocalTx = Transactions.sign(
          localCommitTx,
          wait.initFundee.localParams.keys.fundingKey.privateKey,
          TxOwner.Local,
          wait.initFundee.channelFeatures.commitmentFormat
        )
        val signedLocalCommitTx = Transactions.addSigs(
          localCommitTx,
          wait.initFundee.localParams.keys.fundingKey.publicKey,
          wait.remoteParams.fundingPubKey,
          localSigOfLocalTx,
          created.signature
        )
        val localSigOfRemoteTx = Transactions.sign(
          remoteCommitTx,
          wait.initFundee.localParams.keys.fundingKey.privateKey,
          TxOwner.Remote,
          wait.initFundee.channelFeatures.commitmentFormat
        )
        val fundingSigned = FundingSigned(
          channelId = toLongId(created.fundingTxid, created.fundingOutputIndex),
          signature = localSigOfRemoteTx
        )

        val publishableTxs = PublishableTxs(signedLocalCommitTx, Nil)
        val commits = NormalCommits(
          wait.initFundee.theirOpen.channelFlags,
          fundingSigned.channelId,
          wait.initFundee.channelFeatures,
          Right(randomKey.publicKey),
          ShaChain.init,
          updateOpt = None,
          postCloseOutgoingResolvedIds = Set.empty[Long],
          wait.initFundee.remoteInfo,
          wait.initFundee.localParams,
          wait.remoteParams,
          LocalCommit(index = 0L, localSpec, publishableTxs),
          RemoteCommit(
            index = 0L,
            remoteSpec,
            remoteCommitTx.tx.txid,
            remotePerCommitmentPoint =
              wait.initFundee.theirOpen.firstPerCommitmentPoint
          ),
          LocalChanges(Nil, Nil, Nil),
          RemoteChanges(Nil, Nil, Nil),
          localNextHtlcId = 0L,
          remoteNextHtlcId = 0L,
          signedLocalCommitTx.input
        )

        require(Transactions.checkSpendable(signedLocalCommitTx).isSuccess)
        Helpers.validateParamsFundee(wait.initFundee.theirOpen, commits)

        watchConfirmedSpent(commits, watchConfirmed = true, watchSpent = true)
        StoreBecomeSend(
          DATA_WAIT_FOR_FUNDING_CONFIRMED(
            commits,
            None,
            System.currentTimeMillis,
            Right(fundingSigned)
          ),
          Channel.WaitFundingDone,
          fundingSigned
        )

      // AWAITING CONFIRMATION
      case (
            wait: DATA_WAIT_FOR_FUNDING_CONFIRMED,
            event: WatchEventConfirmed,
            Channel.Sleeping | Channel.WaitFundingDone
          ) =>
        if (Try(wait checkSpend event.txConfirmedAt.tx).isFailure)
          StoreBecomeSend(
            DATA_CLOSING(wait.commitments, System.currentTimeMillis),
            Channel.Closing
          )
        else {
          val fundingLocked = FundingLocked(
            nextPerCommitmentPoint =
              wait.commitments.localParams.keys.commitmentPoint(1L),
            channelId = wait.channelId
          )
          val shortChannelId = ShortChannelId(
            event.txConfirmedAt.blockHeight,
            event.txIndex,
            wait.commitments.commitInput.outPoint.index.toInt
          )
          StoreBecomeSend(
            DATA_WAIT_FOR_FUNDING_LOCKED(
              wait.commitments,
              shortChannelId,
              fundingLocked
            ),
            state,
            fundingLocked
          )
          wait.deferred.foreach(process)
        }

      case (
            wait: DATA_WAIT_FOR_FUNDING_CONFIRMED,
            locked: FundingLocked,
            Channel.WaitFundingDone
          ) =>
        // No need to store their message, they will re-send if we get disconnected
        BECOME(wait.copy(deferred = Some(locked)), Channel.WaitFundingDone)

      case (
            wait: DATA_WAIT_FOR_FUNDING_LOCKED,
            locked: FundingLocked,
            Channel.WaitFundingDone
          ) =>
        StoreBecomeSend(
          DATA_NORMAL(
            wait.commitments.copy(remoteNextCommitInfo =
              Right(locked.nextPerCommitmentPoint)
            ),
            wait.shortChannelId
          ),
          Channel.Open
        )

      // MAIN LOOP

      case (
            some: HasNormalCommitments,
            WatchEventSpent(BITCOIN_FUNDING_SPENT, tx),
            Channel.Open | Channel.Sleeping | Channel.Closing
          ) =>
        // Catch all possible closings in one go, account for us receiving various closing txs multiple times
        some match {
          case closing: DATA_CLOSING
              if closing.mutualClosePublished.exists(_.txid == tx.txid) =>
          case closing: DATA_CLOSING
              if closing.localCommitPublished.exists(
                _.commitTx.txid == tx.txid
              ) =>
          case closing: DATA_CLOSING
              if closing.remoteCommitPublished.exists(
                _.commitTx.txid == tx.txid
              ) =>
          case closing: DATA_CLOSING
              if closing.nextRemoteCommitPublished.exists(
                _.commitTx.txid == tx.txid
              ) =>
          case closing: DATA_CLOSING
              if closing.futureRemoteCommitPublished.exists(
                _.commitTx.txid == tx.txid
              ) =>
          case closing: DATA_CLOSING
              if closing.mutualCloseProposed.exists(_.txid == tx.txid) =>
            handleMutualClose(tx, closing)
          case negs: DATA_NEGOTIATING
              if negs.bestUnpublishedClosingTxOpt.exists(_.txid == tx.txid) =>
            handleMutualClose(tx, negs)
          case negs: DATA_NEGOTIATING
              if negs.closingTxProposed.flatten.exists(
                _.unsignedTx.txid == tx.txid
              ) =>
            handleMutualClose(tx, negs)
          case waitPublishFuture: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT =>
            handleRemoteSpentFuture(tx, waitPublishFuture.commitments)
          case _
              if some.commitments.remoteNextCommitInfo.left.exists(
                _.nextRemoteCommit.txid == tx.txid
              ) =>
            handleRemoteSpentNext(tx, some)
          case _ if tx.txid == some.commitments.remoteCommit.txid =>
            handleRemoteSpentCurrent(tx, some)
          case _ => handleRemoteSpentOther(tx, some)
        }

      case (
            norm: DATA_NORMAL,
            CurrentBlockCount(tip),
            Channel.Open | Channel.Sleeping
          ) =>
        val sentExpiredRouted = norm.commitments.allOutgoing.exists(add =>
          tip > add.cltvExpiry.underlying && add.fullTag.tag == PaymentTagTlv.TRAMPLOINE_ROUTED
        )
        val threshold = Transactions.receivedHtlcTrimThreshold(
          norm.commitments.remoteParams.dustLimit,
          norm.commitments.remoteCommit.spec,
          norm.commitments.channelFeatures.commitmentFormat
        )
        val largeReceivedRevealed = norm.commitments.revealedFulfills.filter(
          _.theirAdd.amountMsat > threshold * LNParams.minForceClosableIncomingHtlcAmountToFeeRatio
        )
        val expiredReceivedRevealed =
          largeReceivedRevealed.exists(localFulfill =>
            tip > localFulfill.theirAdd.cltvExpiry.underlying - LNParams.ncFulfillSafetyBlocks
          )
        if (sentExpiredRouted || expiredReceivedRevealed)
          throw ExpiredHtlcInNormalChannel(
            norm.channelId,
            sentExpiredRouted,
            expiredReceivedRevealed
          )

      case (some: HasNormalCommitments, remoteInfo: RemoteNodeInfo, _)
          if some.commitments.remoteInfo.nodeId == remoteInfo.nodeId =>
        StoreBecomeSend(
          some withNewCommits some.commitments
            .copy(remoteInfo = remoteInfo.safeAlias),
          state
        )

      case (
            norm: DATA_NORMAL,
            update: ChannelUpdate,
            Channel.Open | Channel.Sleeping
          )
          if update.shortChannelId == norm.shortChannelId && norm.commitments.updateOpt
            .forall(_.core != update.core) =>
        StoreBecomeSend(
          norm withNewCommits norm.commitments.copy(updateOpt = Some(update)),
          state
        )

      // It is assumed that LNParams.feeRates.info is updated at this point
      case (norm: DATA_NORMAL, CMD_CHECK_FEERATE, Channel.Open)
          if norm.commitments.localParams.isFunder =>
        nextFeerate(norm, LNParams.shouldSendUpdateFeerateDiff)
          .map(norm.commitments.sendFee)
          .toList match {
          case (newCommits, balanceAfterFeeUpdate, ourRatesUpdateMsg) :: Nil
              if balanceAfterFeeUpdate.toLong >= 0L =>
            // Technically we still require feerate update at this point since local commit feerate is not refreshed yet
            // but we may start sending new HTLCs right away because remote peer will see them AFTER update signature
            StoreBecomeSend(
              norm.copy(commitments = newCommits, feeUpdateRequired = false),
              Channel.Open,
              ourRatesUpdateMsg
            )
            doProcess(CMD_SIGN)

          case (_, balanceAfterFeeUpdate, _) :: Nil
              if balanceAfterFeeUpdate.toLong < 0L =>
            // We need a feerate update but can't afford it right now so wait and reject outgoing adds
            // situation is expected to normalize by either sending it later or getting better feerates
            StoreBecomeSend(norm.copy(feeUpdateRequired = true), Channel.Open)

          case Nil if norm.feeUpdateRequired =>
            // We don't need a feerate update, but persisted state indicates it was required
            // we have probably gotten another feerates which are more in line with current ones
            StoreBecomeSend(
              norm.copy(feeUpdateRequired = false),
              Channel.Open
            )

          case Nil =>
          // Do nothing
        }

      case (negs: DATA_NEGOTIATING, _: CMD_CLOSE, _)
          if negs.bestUnpublishedClosingTxOpt.nonEmpty =>
        handleMutualClose(negs.bestUnpublishedClosingTxOpt.get, negs)
      case (
            some: HasNormalCommitments,
            cmd: CMD_CLOSE,
            Channel.Open | Channel.Sleeping | Channel.WaitFundingDone |
            Channel.Closing
          ) if cmd.force =>
        spendLocalCurrent(some, cmd)

      // We may schedule shutdown while channel is offline
      case (
            norm: DATA_NORMAL,
            cmd: CMD_CLOSE,
            Channel.Open | Channel.Sleeping
          ) =>
        val localScriptPubKey = cmd.scriptPubKey.getOrElse(
          norm.commitments.localParams.defaultFinalScriptPubKey
        )
        val isValidFinalScriptPubkey =
          Closing.isValidFinalScriptPubkey(localScriptPubKey)
        // It's important that local Shutdown MUST be persisted if sent to remote peer
        // it will be resent on restart and won't be resent on entering negotiations
        val shutdown = Shutdown(norm.channelId, localScriptPubKey)

        if (!isValidFinalScriptPubkey)
          throw CMDException(CMD_CLOSE.INVALID_CLOSING_PUBKEY, cmd)
        else if (norm.localShutdown.isDefined)
          throw CMDException(CMD_CLOSE.ALREADY_IN_PROGRESS, cmd)
        else if (norm.commitments.localHasUnsignedOutgoingHtlcs)
          throw CMDException(CMD_CLOSE.CHANNEL_BUSY, cmd)
        else
          StoreBecomeSend(
            norm.copy(localShutdown = Some(shutdown)),
            state,
            shutdown
          )

      case (
            norm: DATA_NORMAL,
            cmd: CMD_ADD_HTLC,
            Channel.Open | Channel.Sleeping
          ) =>
        norm.commitments.sendAdd(
          cmd,
          blockHeight = LNParams.blockCount.get
        ) match {
          case _
              if norm.localShutdown.nonEmpty || norm.remoteShutdown.nonEmpty =>
            events addRejectedLocally ChannelNotAbleToSend(cmd.incompleteAdd)

          case _ if Channel.Sleeping == state =>
            // Tell outgoing FSM to not exclude this channel yet
            events addRejectedLocally ChannelOffline(cmd.incompleteAdd)

          case _
              if norm.commitments.localParams.isFunder && norm.feeUpdateRequired =>
            // It's dangerous to send payments when we need a feerate update but can not afford it
            events addRejectedLocally ChannelNotAbleToSend(cmd.incompleteAdd)

          case _
              if !norm.commitments.localParams.isFunder && cmd.fullTag.tag == PaymentTagTlv.TRAMPLOINE_ROUTED && nextFeerate(
                norm,
                LNParams.shouldRejectPaymentFeerateDiff
              ).isDefined =>
            // It's dangerous to send routed payments as a fundee when we need a feerate update but peer has not sent us one yet
            events addRejectedLocally ChannelNotAbleToSend(cmd.incompleteAdd)

          case Left(reason) =>
            events addRejectedLocally reason

          case Right(newCommits ~ updateAddHtlcMsg) =>
            BECOME(norm.copy(commitments = newCommits), Channel.Open)
            SEND(msg = updateAddHtlcMsg)
            process(CMD_SIGN)
        }

      case (_, cmd: CMD_ADD_HTLC, _) =>
        // Instruct upstream to skip this channel in such a state
        val reason = ChannelNotAbleToSend(cmd.incompleteAdd)
        events addRejectedLocally reason

      case (norm: DATA_NORMAL, cmd: CMD_FULFILL_HTLC, Channel.Open)
          // CMD_SIGN will be sent from ChannelMaster strictly after outgoing FSM sends this command
          if norm.commitments.latestReducedRemoteSpec
            .findOutgoingHtlcById(cmd.theirAdd.id)
            .isDefined =>
        val (newCommits, ourFulfillMsg) = norm.commitments.sendFulfill(cmd)
        BECOME(norm.copy(commitments = newCommits), Channel.Open)
        SEND(ourFulfillMsg)

      case (norm: DATA_NORMAL, cmd: CMD_FAIL_HTLC, Channel.Open)
          // CMD_SIGN will be sent from ChannelMaster strictly after outgoing FSM sends this command
          if norm.commitments.latestReducedRemoteSpec
            .findOutgoingHtlcById(cmd.theirAdd.id)
            .isDefined =>
        val msg =
          OutgoingPaymentPacket.buildHtlcFailure(cmd, theirAdd = cmd.theirAdd)
        BECOME(
          norm.copy(commitments = norm.commitments.addLocalProposal(msg)),
          Channel.Open
        )
        SEND(msg)

      case (norm: DATA_NORMAL, cmd: CMD_FAIL_MALFORMED_HTLC, Channel.Open)
          // CMD_SIGN will be sent from ChannelMaster strictly after outgoing FSM sends this command
          if norm.commitments.latestReducedRemoteSpec
            .findOutgoingHtlcById(cmd.theirAdd.id)
            .isDefined =>
        val msg = UpdateFailMalformedHtlc(
          norm.channelId,
          cmd.theirAdd.id,
          cmd.onionHash,
          cmd.failureCode
        )
        BECOME(
          norm.copy(commitments = norm.commitments.addLocalProposal(msg)),
          Channel.Open
        )
        SEND(msg)

      case (norm: DATA_NORMAL, CMD_SIGN, Channel.Open)
          if norm.commitments.localHasChanges && norm.commitments.remoteNextCommitInfo.isRight =>
        // We have something to sign and remote unused pubKey, don't forget to store revoked HTLC data

        val (newCommits, commitSigMessage, nextRemoteCommit) =
          norm.commitments.sendCommit
        val out = Transactions.trimOfferedHtlcs(
          norm.commitments.remoteParams.dustLimit,
          nextRemoteCommit.spec,
          norm.commitments.channelFeatures.commitmentFormat
        )
        val in = Transactions.trimReceivedHtlcs(
          norm.commitments.remoteParams.dustLimit,
          nextRemoteCommit.spec,
          norm.commitments.channelFeatures.commitmentFormat
        )
        // This includes hashing and db calls on potentially dozens of in-flight HTLCs, do this in separate thread but throw if it fails to know early
        Rx.ioQueue.foreach(
          _ =>
            bag.putHtlcInfos(
              out ++ in,
              norm.shortChannelId,
              nextRemoteCommit.index
            ),
          throw _
        )
        StoreBecomeSend(
          norm.copy(commitments = newCommits),
          Channel.Open,
          commitSigMessage
        )

      case (norm: DATA_NORMAL, CMD_SIGN, Channel.Open)
          if norm.remoteShutdown.isDefined && !norm.commitments.localHasUnsignedOutgoingHtlcs =>
        // We have nothing to sign left AND no unsigned outgoing HTLCs AND remote peer wishes to close a channel
        maybeStartNegotiations(norm, norm.remoteShutdown.get)

      case (norm: DATA_NORMAL, theirAdd: UpdateAddHtlc, Channel.Open) =>
        val theirAddExt =
          UpdateAddHtlcExt(theirAdd, norm.commitments.remoteInfo)
        BECOME(
          norm.copy(commitments = norm.commitments.receiveAdd(add = theirAdd)),
          Channel.Open
        )
        events addReceived theirAddExt

      case (norm: DATA_NORMAL, msg: UpdateFulfillHtlc, Channel.Open) =>
        val (newCommits, ourAdd) = norm.commitments.receiveFulfill(msg)
        val fulfill = RemoteFulfill(ourAdd, msg.paymentPreimage)
        BECOME(norm.copy(commitments = newCommits), Channel.Open)
        events fulfillReceived fulfill

      case (norm: DATA_NORMAL, msg: UpdateFailHtlc, Channel.Open) =>
        BECOME(
          norm.copy(commitments = norm.commitments.receiveFail(msg)),
          Channel.Open
        )

      case (norm: DATA_NORMAL, msg: UpdateFailMalformedHtlc, Channel.Open) =>
        BECOME(
          norm.copy(commitments = norm.commitments.receiveFailMalformed(msg)),
          Channel.Open
        )

      case (norm: DATA_NORMAL, commitSig: CommitSig, Channel.Open) =>
        val (newCommits, revocation) = norm.commitments.receiveCommit(commitSig)
        // If feerate update is required AND becomes possible then we schedule another check shortly
        if (norm.feeUpdateRequired)
          Rx.ioQueue
            .delay(1.second)
            .foreach(_ => process(CMD_CHECK_FEERATE), none)
        StoreBecomeSend(
          norm.copy(commitments = newCommits),
          Channel.Open,
          revocation
        )
        process(CMD_SIGN)

      case (norm: DATA_NORMAL, revocation: RevokeAndAck, Channel.Open) =>
        val remoteRejects: Seq[RemoteReject] =
          norm.commitments.remoteChanges.signed.collect {
            case fail: UpdateFailHtlc =>
              RemoteUpdateFail(
                fail,
                norm.commitments.remoteCommit.spec
                  .findIncomingHtlcById(fail.id)
                  .get
                  .add
              )
            case malform: UpdateFailMalformedHtlc =>
              RemoteUpdateMalform(
                malform,
                norm.commitments.remoteCommit.spec
                  .findIncomingHtlcById(malform.id)
                  .get
                  .add
              )
          }

        StoreBecomeSend(
          norm.copy(commitments =
            norm.commitments.receiveRevocation(revocation)
          ),
          Channel.Open
        )
        for (reject <- remoteRejects) events addRejectedRemotely reject
        events.notifyResolvers

      case (norm: DATA_NORMAL, remoteFee: UpdateFee, Channel.Open) =>
        BECOME(
          norm.copy(commitments = norm.commitments.receiveFee(remoteFee)),
          Channel.Open
        )

      case (norm: DATA_NORMAL, remote: Shutdown, Channel.Open) =>
        val isTheirFinalScriptPubkeyValid =
          Closing.isValidFinalScriptPubkey(remote.scriptPubKey)
        if (!isTheirFinalScriptPubkeyValid)
          throw ChannelTransitionFail(norm.commitments.channelId, remote)
        if (norm.commitments.remoteHasUnsignedOutgoingHtlcs)
          throw ChannelTransitionFail(norm.commitments.channelId, remote)
        if (norm.commitments.remoteHasUnsignedOutgoingUpdateFee)
          throw ChannelTransitionFail(norm.commitments.channelId, remote)
        if (norm.commitments.localHasUnsignedOutgoingHtlcs)
          BECOME(norm.copy(remoteShutdown = Some(remote)), Channel.Open)
        else maybeStartNegotiations(norm, remote)

      // NEGOTIATIONS

      case (negs: DATA_NEGOTIATING, remote: ClosingSigned, Channel.Open) =>
        val signedClosingTx = Closing.checkClosingSignature(
          negs.commitments,
          negs.localShutdown.scriptPubKey,
          negs.remoteShutdown.scriptPubKey,
          remote
        )
        val firstClosingFee = Closing.firstClosingFee(
          negs.commitments,
          negs.localShutdown.scriptPubKey,
          negs.remoteShutdown.scriptPubKey,
          LNParams.feeRates.info.onChainFeeConf
        )
        if (
          negs.closingTxProposed.last.lastOption
            .map(_.localClosingSigned.feeSatoshis)
            .contains(
              remote.feeSatoshis
            ) || negs.closingTxProposed.flatten.size >= LNParams.maxNegotiationIterations
        ) {
          val newNegotiating =
            negs.copy(bestUnpublishedClosingTxOpt = Some(signedClosingTx))
          handleMutualClose(signedClosingTx, newNegotiating)
        } else {
          val lastLocalClosingFee = negs.closingTxProposed.last.lastOption
            .map(_.localClosingSigned.feeSatoshis)
          val nextClosingFee = Closing.nextClosingFee(
            localClosingFee = lastLocalClosingFee.getOrElse(firstClosingFee),
            remoteClosingFee = remote.feeSatoshis
          )
          val (closingTx, closingSignedMsg) = Closing.makeClosingTx(
            negs.commitments,
            negs.localShutdown.scriptPubKey,
            negs.remoteShutdown.scriptPubKey,
            nextClosingFee
          )

          val proposed = ClosingTxProposed(closingTx.tx, closingSignedMsg)

          @nowarn
          val closingTxProposed = negs.closingTxProposed match {
            case prev :+ current => prev :+ (current :+ proposed) map identity
          }
          val newNegotiating = negs.copy(
            bestUnpublishedClosingTxOpt = Some(signedClosingTx),
            closingTxProposed = closingTxProposed
          )

          if (lastLocalClosingFee contains nextClosingFee) {
            // Next computed fee is the same than the one we previously sent
            // this may happen due to rounding, anyway we can close now
            handleMutualClose(signedClosingTx, newNegotiating)
          } else if (nextClosingFee == remote.feeSatoshis) {
            // Our next computed fee matches their, we have converged
            handleMutualClose(signedClosingTx, newNegotiating)
            SEND(closingSignedMsg)
          } else {
            // Keep negotiating, our closing fees are different
            StoreBecomeSend(newNegotiating, Channel.Open, closingSignedMsg)
          }
        }

      // OFFLINE IN PERSISTENT STATES
      case (
            currentData: HasNormalCommitments,
            CMD_SOCKET_OFFLINE,
            Channel.WaitFundingDone | Channel.Open
          ) =>
        // Do not persist if we had no proposed updates to protect against flapping channels
        val (newData, localProposedAdds, wasUpdated) =
          maybeRevertUnsignedOutgoing(currentData)
        if (wasUpdated) StoreBecomeSend(newData, Channel.Sleeping)
        else BECOME(currentData, Channel.Sleeping)
        for (add <- localProposedAdds)
          events addRejectedLocally ChannelOffline(add)

      // REESTABLISHMENT IN PERSISTENT STATES
      case (
            wait: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT,
            CMD_SOCKET_ONLINE,
            Channel.Sleeping
          ) =>
        // There isn't much to do except asking them once again to publish their current commitment on chain
        CommsTower.workers
          .get(wait.commitments.remoteInfo.nodeSpecificPair)
          .foreach(_ requestRemoteForceClose wait.channelId)
        BECOME(wait, Channel.Closing)

      case (
            currentData: HasNormalCommitments,
            CMD_SOCKET_ONLINE,
            Channel.Sleeping
          ) =>
        val myCurrentPoint = currentData.commitments.localParams.keys
          .commitmentPoint(currentData.commitments.localCommit.index)
        val yourLastSecret =
          currentData.commitments.remotePerCommitmentSecrets.lastIndex
            .flatMap(currentData.commitments.remotePerCommitmentSecrets.getHash)
            .getOrElse(ByteVector32.Zeroes)
        val reestablish = ChannelReestablish(
          currentData.channelId,
          currentData.commitments.localCommit.index + 1,
          currentData.commitments.remoteCommit.index,
          PrivateKey(yourLastSecret),
          myCurrentPoint
        )
        SEND(reestablish)

      case (
            wait: DATA_WAIT_FOR_FUNDING_CONFIRMED,
            _: ChannelReestablish,
            Channel.Sleeping
          ) =>
        // We put back the watch (operation is idempotent) because corresponding event may have been already fired while we were in Channel.Sleeping state
        LNParams.chainWallets.watcher.send(
          WatchConfirmed(
            receiver,
            wait.commitments.commitInput.outPoint.txid,
            wait.commitments.commitInput.txOut.publicKeyScript,
            LNParams.minDepthBlocks,
            BITCOIN_FUNDING_DEPTHOK
          )
        )
        // Getting remote ChannelReestablish means our chain wallet is online (since we start connecting channels only after it becomes online), it makes sense to retry a funding broadcast here
        wait.fundingTx.foreach(LNParams.chainWallets.lnWallet.broadcast)
        BECOME(wait, Channel.WaitFundingDone)

      case (
            wait: DATA_WAIT_FOR_FUNDING_LOCKED,
            _: ChannelReestablish,
            Channel.Sleeping
          ) =>
        // At this point funding tx already has a desired number of confirmations
        BECOME(wait, Channel.WaitFundingDone)
        SEND(wait.lastSent)

      case (
            norm: DATA_NORMAL,
            reestablish: ChannelReestablish,
            Channel.Sleeping
          ) =>
        var sendQueue = Queue.empty[LightningMessage]

        reestablish match {
          case rs
              if !Helpers.checkLocalCommit(
                norm,
                rs.nextRemoteRevocationNumber
              ) =>
            // If NextRemoteRevocationNumber is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
            // we need to make sure that the last PerCommitmentSecret that they claim to have received from us is correct for that NextRemoteRevocationNumber - 1
            if (
              norm.commitments.localParams.keys.commitmentSecret(
                rs.nextRemoteRevocationNumber - 1
              ) == rs.yourLastPerCommitmentSecret
            ) {
              CommsTower.workers
                .get(norm.commitments.remoteInfo.nodeSpecificPair)
                .foreach(_ requestRemoteForceClose norm.channelId)
              StoreBecomeSend(
                DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(
                  norm.commitments,
                  rs
                ),
                Channel.Closing
              )
            } else
              throw ChannelTransitionFail(
                norm.commitments.channelId,
                reestablish
              )

          case rs
              if !Helpers.checkRemoteCommit(
                norm,
                rs.nextLocalCommitmentNumber
              ) =>
            // If NextRemoteRevocationNumber is more than one more our remote commitment index, it means that either we are using an outdated commitment, or they are lying
            // there is no way to make sure if they are saying the truth or not, the best thing to do is ask them to publish their commitment right now
            // note that if they don't comply, we could publish our own commitment (it is not stale, otherwise we would be in the case above)
            CommsTower.workers
              .get(norm.commitments.remoteInfo.nodeSpecificPair)
              .foreach(_ requestRemoteForceClose norm.channelId)
            StoreBecomeSend(
              DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(
                norm.commitments,
                rs
              ),
              Channel.Closing
            )

          case rs =>
            if (
              rs.nextLocalCommitmentNumber == 1 && norm.commitments.localCommit.index == 0
            ) {
              val nextPerCommitmentPoint =
                norm.commitments.localParams.keys.commitmentPoint(index = 1L)
              sendQueue :+= FundingLocked(
                norm.commitments.channelId,
                nextPerCommitmentPoint
              )
            }

            def resendRevocation(): Unit =
              if (
                norm.commitments.localCommit.index == rs.nextRemoteRevocationNumber + 1
              ) {
                val localPerCommitmentSecret = norm.commitments.localParams.keys
                  .commitmentSecret(norm.commitments.localCommit.index - 1)
                val localNextPerCommitmentPoint =
                  norm.commitments.localParams.keys
                    .commitmentPoint(norm.commitments.localCommit.index + 1)
                sendQueue :+= RevokeAndAck(
                  norm.channelId,
                  localPerCommitmentSecret,
                  localNextPerCommitmentPoint
                )
              } else if (
                norm.commitments.localCommit.index != rs.nextRemoteRevocationNumber
              ) {
                // Sync has failed, no sense to continue normally, force-close it
                throw ChannelTransitionFail(norm.channelId, reestablish)
              }

            norm.commitments.remoteNextCommitInfo match {
              case _
                  if norm.commitments.remoteNextCommitInfo.isRight && norm.commitments.remoteCommit.index + 1 == rs.nextLocalCommitmentNumber =>
                resendRevocation
              case Left(waitingForRevocation)
                  if waitingForRevocation.nextRemoteCommit.index + 1 == rs.nextLocalCommitmentNumber =>
                resendRevocation

              case Left(waitingForRevocation)
                  if waitingForRevocation.nextRemoteCommit.index == rs.nextLocalCommitmentNumber =>
                if (
                  norm.commitments.localCommit.index <= waitingForRevocation.sentAfterLocalCommitIndex
                ) resendRevocation
                (norm.commitments.localChanges.signed :+ waitingForRevocation.sent)
                  .foreach(change => sendQueue :+= change)
                if (
                  norm.commitments.localCommit.index > waitingForRevocation.sentAfterLocalCommitIndex
                ) resendRevocation

              case _ =>
                // Sync has failed, no sense to continue normally, force-close it
                throw ChannelTransitionFail(norm.channelId, reestablish)
            }

            BECOME(norm, Channel.Open)
            SEND(sendQueue ++ norm.localShutdown: _*)
            events.notifyResolvers
        }

      case (
            currentData: DATA_NEGOTIATING,
            _: ChannelReestablish,
            Channel.Sleeping
          ) if currentData.commitments.localParams.isFunder =>
        // We could use the last ClosingSigned we sent, but network fees may have changed while we were offline so it is better to restart from scratch
        val (closingTx, closingSigned) = Closing.makeFirstClosingTx(
          currentData.commitments,
          currentData.localShutdown.scriptPubKey,
          currentData.remoteShutdown.scriptPubKey,
          LNParams.feeRates.info.onChainFeeConf
        )
        StoreBecomeSend(
          currentData
            .modify(_.closingTxProposed)
            .using(_ :+ List(ClosingTxProposed(closingTx.tx, closingSigned))),
          Channel.Open,
          currentData.localShutdown,
          closingSigned
        )

      case (
            currentData: DATA_NEGOTIATING,
            _: ChannelReestablish,
            Channel.Sleeping
          ) =>
        StoreBecomeSend(
          currentData.copy(
            closingTxProposed =
              if (currentData.closingTxProposed.last.isEmpty)
                currentData.closingTxProposed
              else currentData.closingTxProposed :+ Nil
          ),
          Channel.Open,
          currentData.localShutdown
        )

      // Closing phase
      case (
            currentData: DATA_CLOSING,
            _: ChannelReestablish,
            Channel.Closing
          ) =>
        val error = Fail(currentData.channelId, s"funding tx has been spent")
        SEND(error)

      case (
            closing: DATA_CLOSING,
            WatchEventSpent(BITCOIN_OUTPUT_SPENT, tx),
            Channel.Closing
          ) =>
        // An output in local/remote/revoked commit was spent, add it to irrevocably spent once confirmed
        LNParams.chainWallets.watcher.send(
          WatchConfirmed(
            receiver,
            tx,
            event = BITCOIN_TX_CONFIRMED(tx),
            minDepth = 1L
          )
        )
        // Peer might have just used a preimage on chain to claim our timeout HTLCs UTXO: consider a payment sent then
        val remoteFulfills = Closing
          .extractPreimages(closing.commitments.localCommit, tx)
          .map(RemoteFulfill.tupled)
        val settledOutgoingHtlcIds = remoteFulfills.map(_.ourAdd.id)

        val rev = closing.revokedCommitPublished.map { revokedCommit =>
          // This might be further spend of success/timeout UTXO from an old revoked state which peer has published previously
          val (txOpt, rev) = Closing.claimRevokedHtlcTxOutputs(
            closing.commitments,
            revokedCommit,
            tx,
            LNParams.feeRates.info.onChainFeeConf.feeEstimator
          )
          for (claimTx <- txOpt)
            LNParams.chainWallets.watcher.send(
              WatchSpent(
                receiver,
                tx,
                claimTx.txIn
                  .filter(_.outPoint.txid == tx.txid)
                  .head
                  .outPoint
                  .index
                  .toInt,
                BITCOIN_OUTPUT_SPENT
              )
            )

          for (claimTx <- txOpt)
            LNParams.chainWallets.watcher.send(PublishAsap(claimTx))

          rev
        }

        // Proceed as if we have normally received preimages off chain, mark related outgoing HTLCs as settled
        StoreBecomeSend(
          closing
            .modify(_.commitments.postCloseOutgoingResolvedIds)
            .using(_ ++ settledOutgoingHtlcIds)
            .copy(revokedCommitPublished = rev),
          Channel.Closing
        )
        remoteFulfills foreach events.fulfillReceived
        // There will be no state update
        events.notifyResolvers

      case (
            closing: DATA_CLOSING,
            event: WatchEventConfirmed,
            Channel.Closing
          ) =>
        val lcp1Opt =
          for (lcp <- closing.localCommitPublished)
            yield Closing.updateLocalCommitPublished(lcp, event.txConfirmedAt)
        val rcp1Opt =
          for (rcp <- closing.remoteCommitPublished)
            yield Closing.updateRemoteCommitPublished(rcp, event.txConfirmedAt)
        val fcp1Opt =
          for (fcp <- closing.futureRemoteCommitPublished)
            yield Closing.updateRemoteCommitPublished(fcp, event.txConfirmedAt)
        val rcp1NextOpt =
          for (rcp <- closing.nextRemoteCommitPublished)
            yield Closing.updateRemoteCommitPublished(rcp, event.txConfirmedAt)
        val revCp1Opt =
          for (revokedCp <- closing.revokedCommitPublished)
            yield Closing.updateRevokedCommitPublished(
              revokedCp,
              event.txConfirmedAt
            )

        val newClosing: DATA_CLOSING =
          DATA_CLOSING(
            closing.commitments,
            closing.waitingSince,
            closing.mutualCloseProposed,
            closing.mutualClosePublished,
            lcp1Opt,
            rcp1Opt,
            rcp1NextOpt,
            fcp1Opt,
            revCp1Opt
          )

        val isClosed: Boolean =
          newClosing.mutualClosePublished.contains(
            event.txConfirmedAt.tx
          ) || lcp1Opt.exists(Closing.isLocalCommitDone) ||
            rcp1Opt.exists(Closing.isRemoteCommitDone) || rcp1NextOpt.exists(
              Closing.isRemoteCommitDone
            ) ||
            revCp1Opt.exists(Closing.isRevokedCommitDone) || fcp1Opt.exists(
              Closing.isRemoteCommitDone
            )

        val overRiddenHtlcs =
          Closing.overriddenOutgoingHtlcs(newClosing, event.txConfirmedAt.tx)
        val (timedOutHtlcs, isDustOutgoingHtlcs) =
          Closing.isClosingTypeAlreadyKnown(newClosing) map {
            case local: Closing.LocalClose =>
              Closing.timedoutHtlcs(
                newClosing.commitments,
                local.localCommit,
                local.localCommitPublished,
                event.txConfirmedAt.tx
              )
            case remote: Closing.RemoteClose =>
              Closing.timedoutHtlcs(
                newClosing.commitments,
                remote.remoteCommit,
                remote.remoteCommitPublished,
                event.txConfirmedAt.tx
              )
            case _: Closing.MutualClose | _: Closing.RecoveryClose |
                _: Closing.RevokedClose =>
              (Set.empty, false)
          } getOrElse (Set.empty, false)

        val settledOutgoingHtlcIds =
          (overRiddenHtlcs ++ timedOutHtlcs).map(_.id)
        StoreBecomeSend(
          newClosing
            .modify(_.commitments.postCloseOutgoingResolvedIds)
            .using(_ ++ settledOutgoingHtlcIds),
          Channel.Closing
        )
        for (add <- overRiddenHtlcs ++ timedOutHtlcs)
          events addRejectedLocally InPrincipleNotSendable(localAdd = add)

        Helpers.chainFeePaid(event.txConfirmedAt.tx, newClosing).foreach {
          chainFee =>
            // This happens when we get a confimed (mutual or commit)-as-funder/timeout/success tx
            bag.addChannelTxFee(
              chainFee,
              event.txConfirmedAt.tx.txid.toHex,
              ChannelTxFeesTable.TAG_CHAIN_FEE
            )
        }

        if (isDustOutgoingHtlcs) bag.db txWrap {
          // This happens when we get a confirmed local/remote commit with dusty outgoing HTLCs which would never reach a chain now, record their values as loss
          for (add <- timedOutHtlcs)
            bag.addChannelTxFee(
              add.amountMsat.truncateToSatoshi,
              add.paymentHash.toHex + add.id,
              ChannelTxFeesTable.TAG_DUSTY_HTLC
            )
        }

        if (isClosed) bag.db txWrap {
          // We only remove a channel from db here, it will disapper on next restart
          newClosing.commitments.updateOpt
            .map(_.shortChannelId)
            .foreach(bag.rmHtlcInfos)
          bag.delete(newClosing.channelId)
        }

      case (closing: DATA_CLOSING, cmd: CMD_FULFILL_HTLC, Channel.Closing)
          // We get a preimage when channel is already closed, so we need to try to redeem payments on chain
          if closing.commitments.latestReducedRemoteSpec
            .findOutgoingHtlcById(cmd.theirAdd.id)
            .isDefined =>
        val (newCommits, ourFulfillMsg) = closing.commitments.sendFulfill(cmd)
        val conf = LNParams.feeRates.info.onChainFeeConf

        val rcp1NextOpt = for {
          rcp <- closing.nextRemoteCommitPublished
          nextRemoteCommit <- newCommits.remoteNextCommitInfo.left.toOption
            .map(_.nextRemoteCommit)
        } yield Closing.claimRemoteCommitTxOutputs(
          newCommits,
          nextRemoteCommit,
          rcp.commitTx,
          conf.feeEstimator
        )

        val lcp1Opt =
          for (lcp <- closing.localCommitPublished)
            yield Closing.claimCurrentLocalCommitTxOutputs(
              newCommits,
              lcp.commitTx,
              conf
            )
        val rcp1Opt =
          for (rcp <- closing.remoteCommitPublished)
            yield Closing.claimRemoteCommitTxOutputs(
              newCommits,
              newCommits.remoteCommit,
              rcp.commitTx,
              conf.feeEstimator
            )
        StoreBecomeSend(
          closing.copy(
            commitments = newCommits,
            localCommitPublished = lcp1Opt,
            remoteCommitPublished = rcp1Opt,
            nextRemoteCommitPublished = rcp1NextOpt
          ),
          Channel.Closing,
          ourFulfillMsg
        )
        rcp1NextOpt.foreach(doPublish)
        rcp1Opt.foreach(doPublish)
        lcp1Opt.foreach(doPublish)

      // RESTORING FROM STORED DATA
      case (null, currentData: DATA_CLOSING, Channel.Initial) =>
        currentData.mutualClosePublished.foreach(doPublish)
        currentData.localCommitPublished.foreach(doPublish)
        currentData.remoteCommitPublished.foreach(doPublish)
        currentData.revokedCommitPublished.foreach(doPublish)
        currentData.nextRemoteCommitPublished.foreach(doPublish)
        currentData.futureRemoteCommitPublished.foreach(doPublish)
        BECOME(currentData, Channel.Closing)

      case (
            null,
            currentData: DATA_WAIT_FOR_FUNDING_CONFIRMED,
            Channel.Initial
          ) =>
        watchConfirmedSpent(
          currentData.commitments,
          watchConfirmed = true,
          watchSpent = true
        )
        BECOME(currentData, Channel.Sleeping)

      case (null, currentData: HasNormalCommitments, Channel.Initial) =>
        watchConfirmedSpent(
          currentData.commitments,
          watchConfirmed = false,
          watchSpent = true
        )
        BECOME(currentData, Channel.Sleeping)

      case (_, remote: Fail, _) =>
        // Convert remote error to local exception, it will be dealt with upstream
        throw RemoteErrorException(ErrorExt extractDescription remote)

      case _ =>
    }

  def nextFeerate(norm: DATA_NORMAL, threshold: Double): Option[FeeratePerKw] =
    newFeerate(
      LNParams.feeRates.info,
      norm.commitments.localCommit.spec,
      threshold
    )

  private def maybeRevertUnsignedOutgoing(currentData: HasNormalCommitments) = {
    val hadProposed =
      currentData.commitments.remoteChanges.proposed.nonEmpty || currentData.commitments.localChanges.proposed.nonEmpty
    val remoteProposed =
      currentData.commitments.remoteChanges.proposed.collect {
        case updateAddHtlc: UpdateAddHtlc => updateAddHtlc
      }
    val localProposed = currentData.commitments.localChanges.proposed.collect {
      case updateAddHtlc: UpdateAddHtlc => updateAddHtlc
    }

    val commitsNoChanges = currentData.commitments
      .modifyAll(_.remoteChanges.proposed, _.localChanges.proposed)
      .setTo(Nil)
    val commitsNoRemoteUpdates =
      commitsNoChanges.modify(_.remoteNextHtlcId).using(_ - remoteProposed.size)
    val commits = commitsNoRemoteUpdates
      .modify(_.localNextHtlcId)
      .using(_ - localProposed.size)
    (currentData withNewCommits commits, localProposed, hadProposed)
  }

  private def handleChannelForceClosing(
      prev: HasNormalCommitments
  )(turnIntoClosing: HasNormalCommitments => DATA_CLOSING): Unit = {
    val (newClosing: DATA_CLOSING, localProposedAdds, _) =
      turnIntoClosing.andThen(maybeRevertUnsignedOutgoing)(prev)
    // Here we don't care whether there were proposed updates since data type changes to Channel.Closing anyway
    StoreBecomeSend(newClosing, Channel.Closing)

    // Unsigned outgoing HTLCs should be failed right away on any force-closing
    for (add <- localProposedAdds)
      events addRejectedLocally ChannelNotAbleToSend(add)
    // In case if force-closing happens when we have a cross-signed mutual tx
    newClosing.mutualClosePublished.foreach(doPublish)
  }

  private def maybeStartNegotiations(
      currentData: DATA_NORMAL,
      remote: Shutdown
  ): Unit = {
    val local = currentData.localShutdown getOrElse Shutdown(
      currentData.channelId,
      currentData.commitments.localParams.defaultFinalScriptPubKey
    )

    if (currentData.commitments.hasPendingHtlcsOrFeeUpdate) {
      StoreBecomeSend(
        currentData
          .copy(localShutdown = Some(local), remoteShutdown = Some(remote)),
        Channel.Open,
        local
      )
    } else if (currentData.commitments.localParams.isFunder) {
      val (closingTx, closingSigned) = Closing.makeFirstClosingTx(
        currentData.commitments,
        local.scriptPubKey,
        remote.scriptPubKey,
        LNParams.feeRates.info.onChainFeeConf
      )
      val newData = DATA_NEGOTIATING(
        currentData.commitments,
        local,
        remote,
        List(ClosingTxProposed(closingTx.tx, closingSigned) :: Nil),
        bestUnpublishedClosingTxOpt = None
      )
      if (currentData.localShutdown.isDefined)
        StoreBecomeSend(newData, Channel.Open, closingSigned)
      else StoreBecomeSend(newData, Channel.Open, local, closingSigned)
    } else {
      val newData = DATA_NEGOTIATING(currentData.commitments, local, remote)
      if (currentData.localShutdown.isDefined)
        StoreBecomeSend(newData, Channel.Open)
      else StoreBecomeSend(newData, Channel.Open, local)
    }
  }

  private def handleMutualClose(
      closingTx: Transaction,
      currentData: DATA_NEGOTIATING
  ): Unit = {
    val newData =
      currentData.toClosed.copy(mutualClosePublished = closingTx :: Nil)
    BECOME(STORE(newData), Channel.Closing)
    doPublish(closingTx)
  }

  private def handleMutualClose(
      closingTx: Transaction,
      currentData: DATA_CLOSING
  ): Unit = {
    val newData =
      currentData.copy(mutualClosePublished =
        currentData.mutualClosePublished :+ closingTx
      )
    BECOME(STORE(newData), Channel.Closing)
    doPublish(closingTx)
  }

  private def spendLocalCurrent(
      currentData: HasNormalCommitments,
      cmd: Command
  ): Unit = currentData match {
    case _: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT =>
      throw CMDException(CMD_CLOSE.AWAITING_REMOTE_FORCE_CLOSE, cmd)
    case closing: DATA_CLOSING
        if closing.futureRemoteCommitPublished.isDefined =>
      throw CMDException(CMD_CLOSE.AWAITING_REMOTE_FORCE_CLOSE, cmd)
    case closing: DATA_CLOSING if closing.localCommitPublished.isDefined =>
      throw CMDException(CMD_CLOSE.ALREADY_IN_PROGRESS, cmd)

    case _ =>
      val commitTx =
        currentData.commitments.localCommit.publishableTxs.commitTx.tx
      val lcp = Closing.claimCurrentLocalCommitTxOutputs(
        currentData.commitments,
        commitTx,
        LNParams.feeRates.info.onChainFeeConf
      )

      handleChannelForceClosing(currentData) {
        case some: DATA_CLOSING => some.copy(localCommitPublished = Some(lcp))
        case some: DATA_NEGOTIATING =>
          some.toClosed.copy(localCommitPublished = Some(lcp))
        case _ =>
          DATA_CLOSING(
            currentData.commitments,
            System.currentTimeMillis,
            localCommitPublished = Some(lcp)
          )
      }

      doPublish(lcp)
  }

  private def handleRemoteSpentCurrent(
      commitTx: Transaction,
      currentData: HasNormalCommitments
  ): Unit = {
    val rcp = Closing.claimRemoteCommitTxOutputs(
      currentData.commitments,
      currentData.commitments.remoteCommit,
      commitTx,
      LNParams.feeRates.info.onChainFeeConf.feeEstimator
    )

    handleChannelForceClosing(currentData) {
      case some: DATA_CLOSING => some.copy(remoteCommitPublished = Some(rcp))
      case some: DATA_NEGOTIATING =>
        some.toClosed.copy(remoteCommitPublished = Some(rcp))
      case some: DATA_WAIT_FOR_FUNDING_CONFIRMED =>
        DATA_CLOSING(
          some.commitments,
          System.currentTimeMillis,
          remoteCommitPublished = Some(rcp)
        )
      case _ =>
        DATA_CLOSING(
          currentData.commitments,
          System.currentTimeMillis,
          remoteCommitPublished = Some(rcp)
        )
    }

    doPublish(rcp)
  }

  private def handleRemoteSpentNext(
      commitTx: Transaction,
      currentData: HasNormalCommitments
  ): Unit = {
    val nextRemoteCommit: RemoteCommit =
      currentData.commitments.remoteNextCommitInfo.swap.toOption.get.nextRemoteCommit
    val rcp = Closing.claimRemoteCommitTxOutputs(
      currentData.commitments,
      nextRemoteCommit,
      commitTx,
      LNParams.feeRates.info.onChainFeeConf.feeEstimator
    )

    handleChannelForceClosing(currentData) {
      case some: DATA_CLOSING =>
        some.copy(nextRemoteCommitPublished = Some(rcp))
      case some: DATA_NEGOTIATING =>
        some.toClosed.copy(nextRemoteCommitPublished = Some(rcp))
      case _ =>
        DATA_CLOSING(
          currentData.commitments,
          System.currentTimeMillis,
          nextRemoteCommitPublished = Some(rcp)
        )
    }

    doPublish(rcp)
  }

  private def handleRemoteSpentFuture(
      commitTx: Transaction,
      commits: NormalCommits
  ): Unit = {
    val remoteCommitPublished = RemoteCommitPublished(
      commitTx,
      claimMainOutputTx = None,
      claimHtlcSuccessTxs = Nil,
      claimHtlcTimeoutTxs = Nil
    )
    val closing = DATA_CLOSING(
      commits,
      System.currentTimeMillis,
      futureRemoteCommitPublished = Some(remoteCommitPublished)
    )
    StoreBecomeSend(closing, Channel.Closing)
  }

  private def handleRemoteSpentOther(
      tx: Transaction,
      currentData: HasNormalCommitments
  ): Unit = {
    Closing.claimRevokedRemoteCommitTxOutputs(
      currentData.commitments,
      tx,
      bag,
      LNParams.feeRates.info.onChainFeeConf.feeEstimator
    ) match {
      // This is most likely an old revoked state, but it might not be in some kind of exceptional circumstance (private keys leakage, old backup etc)
      case Some(revCp) =>
        handleChannelForceClosing(currentData) {
          case some: DATA_CLOSING =>
            some.copy(revokedCommitPublished =
              some.revokedCommitPublished :+ revCp
            )
          case _ =>
            DATA_CLOSING(
              currentData.commitments,
              System.currentTimeMillis,
              revokedCommitPublished = revCp :: Nil
            )
        }

        doPublish(revCp)

      case None =>
        // It is dagerous to publish our commit here (for example when we restore state from an old backup)
        // thanks to remote static key we get the rest of channel balance back anyway so it's not too bad
        handleRemoteSpentFuture(tx, currentData.commitments)
    }
  }

  // Publish handlers
  private def doPublish(closingTx: Transaction): Unit = {
    LNParams.chainWallets.watcher.send(
      WatchConfirmed(
        receiver,
        closingTx,
        BITCOIN_TX_CONFIRMED(closingTx),
        minDepth = 1L
      )
    )
    LNParams.chainWallets.watcher.send(PublishAsap(closingTx))
  }

  private def publishIfNeeded(
      txes: Iterable[Transaction],
      fcc: ForceCloseCommitPublished
  ): Unit =
    txes
      .filterNot(fcc.isIrrevocablySpent)
      .map(PublishAsap)
      .foreach(event => LNParams.chainWallets.watcher.send(event))

  // Watch utxos only we can spend to get basically resolved
  private def watchConfirmedIfNeeded(
      txes: Iterable[Transaction],
      fcc: ForceCloseCommitPublished
  ): Unit =
    txes.filterNot(fcc.isIrrevocablySpent).map(BITCOIN_TX_CONFIRMED).foreach {
      replyEvent =>
        LNParams.chainWallets.watcher.send(
          WatchConfirmed(
            receiver,
            replyEvent.tx,
            replyEvent,
            minDepth = 1L
          )
        )
    }

  // Watch utxos that both we and peer can spend to get triggered (spent, but not confirmed yet)
  private def watchSpentIfNeeded(
      parentTx: Transaction,
      txes: Iterable[Transaction],
      fcc: ForceCloseCommitPublished
  ): Unit =
    txes
      .filterNot(fcc.isIrrevocablySpent)
      .map(_.txIn.head.outPoint.index.toInt)
      .foreach { outPointIndex =>
        LNParams.chainWallets.watcher.send(
          WatchSpent(
            receiver,
            parentTx,
            outPointIndex,
            BITCOIN_OUTPUT_SPENT
          )
        )
      }

  private def doPublish(lcp: LocalCommitPublished): Unit = {
    publishIfNeeded(
      List(
        lcp.commitTx
      ) ++ lcp.claimMainDelayedOutputTx ++ lcp.htlcSuccessTxs ++ lcp.htlcTimeoutTxs ++ lcp.claimHtlcDelayedTxs,
      lcp
    )
    watchConfirmedIfNeeded(
      List(
        lcp.commitTx
      ) ++ lcp.claimMainDelayedOutputTx ++ lcp.claimHtlcDelayedTxs,
      lcp
    )
    watchSpentIfNeeded(
      lcp.commitTx,
      lcp.htlcSuccessTxs ++ lcp.htlcTimeoutTxs,
      lcp
    )
  }

  private def doPublish(rcp: RemoteCommitPublished): Unit = {
    publishIfNeeded(
      rcp.claimMainOutputTx ++ rcp.claimHtlcSuccessTxs ++ rcp.claimHtlcTimeoutTxs,
      rcp
    )
    watchSpentIfNeeded(
      rcp.commitTx,
      rcp.claimHtlcTimeoutTxs ++ rcp.claimHtlcSuccessTxs,
      rcp
    )
    watchConfirmedIfNeeded(List(rcp.commitTx) ++ rcp.claimMainOutputTx, rcp)
  }

  private def doPublish(rcp: RevokedCommitPublished): Unit = {
    publishIfNeeded(
      rcp.claimMainOutputTx ++ rcp.mainPenaltyTx ++ rcp.htlcPenaltyTxs ++ rcp.claimHtlcDelayedPenaltyTxs,
      rcp
    )
    watchSpentIfNeeded(
      rcp.commitTx,
      rcp.mainPenaltyTx ++ rcp.htlcPenaltyTxs,
      rcp
    )
    watchConfirmedIfNeeded(List(rcp.commitTx) ++ rcp.claimMainOutputTx, rcp)
  }
}
