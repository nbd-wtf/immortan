package immortan

import fr.acinq.eclair._
import immortan.Channel._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.blockchain._
import com.softwaremill.quicklens._

import scala.util.{Success, Try}
import akka.actor.{ActorRef, Props}
import fr.acinq.bitcoin.{ByteVector32, Script, ScriptFlags, Transaction}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.router.Announcements
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.eclair.crypto.ShaChain
import scodec.bits.ByteVector


object ChannelNormal {
  def make(initListeners: Set[ChannelListener], normalData: HasNormalCommitments, cw: WalletExt, bag: ChannelBag): ChannelNormal = new ChannelNormal(bag) {
    def SEND(messages: LightningMessage*): Unit = CommsTower.sendMany(messages, normalData.commitments.remoteInfo.nodeSpecificPair)
    def STORE(normalData1: PersistentChannelData): PersistentChannelData = bag.put(normalData1)
    var chainWallet: WalletExt = cw
    listeners = initListeners
    doProcess(normalData)
  }
}

abstract class ChannelNormal(bag: ChannelBag) extends Channel with Handlers { me =>
  val receiver: ActorRef = LNParams.system actorOf Props(new Receiver)
  var chainWallet: WalletExt

  def doProcess(change: Any): Unit =
    Tuple3(data, change, state) match {
      case (null, init: INPUT_INIT_FUNDER, null) =>
        val channelKeyPath = init.remoteInfo.keyPath(init.localParams)
        val localFundingPubKey = init.remoteInfo.fundingPublicKey(init.localParams.fundingKeyPath).publicKey
        val emptyUpfrontShutdown: TlvStream[OpenChannelTlv] = TlvStream(ChannelTlv UpfrontShutdownScript ByteVector.empty)

        val open = OpenChannel(LNParams.chainHash, init.temporaryChannelId, init.fundingAmount, init.pushAmount, init.localParams.dustLimit, init.localParams.maxHtlcValueInFlightMsat,
          init.localParams.channelReserve, init.localParams.htlcMinimum, init.initialFeeratePerKw, init.localParams.toSelfDelay, init.localParams.maxAcceptedHtlcs, localFundingPubKey,
          init.remoteInfo.revocationPoint(channelKeyPath).publicKey, init.localParams.walletStaticPaymentBasepoint.getOrElse(init.remoteInfo.paymentPoint(channelKeyPath).publicKey),
          init.remoteInfo.delayedPaymentPoint(channelKeyPath).publicKey, init.remoteInfo.htlcPoint(channelKeyPath).publicKey,
          init.remoteInfo.commitmentPoint(channelKeyPath, index = 0L), init.channelFlags, emptyUpfrontShutdown)

        val data1 = DATA_WAIT_FOR_ACCEPT_CHANNEL(init, open)
        BECOME(data1, WAIT_FOR_ACCEPT)
        SEND(open)


      case (wait: DATA_WAIT_FOR_ACCEPT_CHANNEL, accept: AcceptChannel, WAIT_FOR_ACCEPT) =>
        Helpers.validateParamsFunder(wait.lastSent, accept).foreach(exception => throw exception)

        val remoteParams = RemoteParams(accept.dustLimitSatoshis, accept.maxHtlcValueInFlightMsat, accept.channelReserveSatoshis, accept.htlcMinimumMsat, accept.toSelfDelay,
          accept.maxAcceptedHtlcs, accept.fundingPubkey, accept.revocationBasepoint, accept.paymentBasepoint, accept.delayedPaymentBasepoint, accept.htlcBasepoint)

        val multisig = Script pay2wsh Scripts.multiSig2of2(wait.lastSent.fundingPubkey, remoteParams.fundingPubKey)
        chainWallet.wallet.makeFundingTx(Script.write(multisig), wait.initFunder.fundingAmount, wait.initFunder.fundingTxFeeratePerKw).foreach(process)
        BECOME(DATA_WAIT_FOR_FUNDING_INTERNAL(wait.initFunder, remoteParams, accept.firstPerCommitmentPoint, wait.lastSent), WAIT_FOR_ACCEPT)


      case (wait: DATA_WAIT_FOR_FUNDING_INTERNAL, MakeFundingTxResponse(fundingTx, fundingTxOutputIndex, fundingTxFee), WAIT_FOR_ACCEPT) =>
        val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Helpers.Funding.makeFirstCommitTxs(wait.initFunder.remoteInfo, wait.initFunder.channelVersion,
          wait.initFunder.temporaryChannelId, wait.initFunder.localParams, wait.remoteParams, wait.initFunder.fundingAmount, wait.initFunder.pushAmount,
          wait.initFunder.initialFeeratePerKw, fundingTx.hash, fundingTxOutputIndex, wait.remoteFirstPerCommitmentPoint).right.get

        require(fundingTx.txOut(fundingTxOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript)

        val extendedFundingPubKey = wait.initFunder.remoteInfo.fundingPublicKey(wait.initFunder.localParams.fundingKeyPath)
        val localSigOfRemoteTx = wait.initFunder.remoteInfo.sign(remoteCommitTx, extendedFundingPubKey, TxOwner.Remote, wait.initFunder.channelVersion.commitmentFormat)
        val fundingCreated = FundingCreated(wait.initFunder.temporaryChannelId, fundingTxid = fundingTx.hash, fundingTxOutputIndex, localSigOfRemoteTx)

        val data1 = DATA_WAIT_FOR_FUNDING_SIGNED(wait.initFunder.remoteInfo, channelId = toLongId(fundingTx.hash, fundingTxOutputIndex), wait.initFunder.localParams, wait.remoteParams,
          fundingTx, fundingTxFee, localSpec, localCommitTx, RemoteCommit(index = 0L, remoteSpec, remoteCommitTx.tx.txid, wait.remoteFirstPerCommitmentPoint), wait.lastSent.channelFlags,
          wait.initFunder.channelVersion, fundingCreated)

        BECOME(data1, WAIT_FOR_ACCEPT)
        SEND(fundingCreated)


      case (wait: DATA_WAIT_FOR_FUNDING_SIGNED, signed: FundingSigned, WAIT_FOR_ACCEPT) =>
        val fundingPubKey = wait.remoteInfo.fundingPublicKey(wait.localParams.fundingKeyPath)
        val localSigOfLocalTx = wait.remoteInfo.sign(wait.localCommitTx, fundingPubKey, TxOwner.Local, wait.channelVersion.commitmentFormat)
        val signedLocalCommitTx = Transactions.addSigs(wait.localCommitTx, fundingPubKey.publicKey, wait.remoteParams.fundingPubKey, localSigOfLocalTx, signed.signature)

        if (Transactions.checkSpendable(signedLocalCommitTx).isFailure) {
          chainWallet.wallet.rollback(wait.fundingTx)
          throw new RuntimeException
        } else {
          val publishableTxs = PublishableTxs(signedLocalCommitTx, Nil)
          val commits = NormalCommits(wait.channelVersion, wait.remoteInfo, wait.localParams, wait.remoteParams, wait.channelFlags,
            LocalCommit(index = 0L, wait.localSpec, publishableTxs), wait.remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L, remoteNextCommitInfo = Right(randomKey.publicKey), signedLocalCommitTx.input, ShaChain.init,
            updateOpt = None, wait.channelId, startedAt = System.currentTimeMillis)

          chainWallet.watcher ! WatchSpent(receiver, commits.commitInput.outPoint.txid, commits.commitInput.outPoint.index.toInt, commits.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
          chainWallet.watcher ! WatchConfirmed(receiver, commits.commitInput.outPoint.txid, commits.commitInput.txOut.publicKeyScript, LNParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
          StoreBecomeSend(DATA_WAIT_FOR_FUNDING_CONFIRMED(commits, Some(wait.fundingTx), System.currentTimeMillis, Left(wait.lastSent), deferred = None), WAIT_FUNDING_DONE)

          chainWallet.wallet.commit(wait.fundingTx) onComplete {
            case Success(false) => process(BITCOIN_FUNDING_PUBLISH_FAILED)
            case _ => // It's unknown whether tx has been published, do nothing
          }
        }

      // FUNDEE FLOW

      case (null, init: INPUT_INIT_FUNDEE, null) =>
        Helpers.validateParamsFundee(LNParams.normInit.features, init.theirOpen, init.remoteInfo.nodeId).foreach(exception => throw exception)

        val channelKeyPath = init.remoteInfo.keyPath(init.localParams)
        val localFundingPubKey = init.remoteInfo.fundingPublicKey(init.localParams.fundingKeyPath).publicKey
        val emptyUpfrontShutdown: TlvStream[AcceptChannelTlv] = TlvStream(ChannelTlv UpfrontShutdownScript ByteVector.empty)

        val basePoint = init.localParams.walletStaticPaymentBasepoint.getOrElse(init.remoteInfo.paymentPoint(channelKeyPath).publicKey)
        val accept = AcceptChannel(init.temporaryChannelId, init.localParams.dustLimit, init.localParams.maxHtlcValueInFlightMsat, init.localParams.channelReserve, init.localParams.htlcMinimum,
          LNParams.minDepthBlocks, init.localParams.toSelfDelay, init.localParams.maxAcceptedHtlcs, localFundingPubKey, init.remoteInfo.revocationPoint(channelKeyPath).publicKey, basePoint,
          init.remoteInfo.delayedPaymentPoint(channelKeyPath).publicKey, init.remoteInfo.htlcPoint(channelKeyPath).publicKey,
          init.remoteInfo.commitmentPoint(channelKeyPath, index = 0L), emptyUpfrontShutdown)

        val remoteParams = RemoteParams(init.theirOpen.dustLimitSatoshis, init.theirOpen.maxHtlcValueInFlightMsat, init.theirOpen.channelReserveSatoshis,
          init.theirOpen.htlcMinimumMsat, init.theirOpen.toSelfDelay, init.theirOpen.maxAcceptedHtlcs, init.theirOpen.fundingPubkey, init.theirOpen.revocationBasepoint,
          init.theirOpen.paymentBasepoint, init.theirOpen.delayedPaymentBasepoint, init.theirOpen.htlcBasepoint)

        val data1 = DATA_WAIT_FOR_FUNDING_CREATED(init, remoteParams, accept)
        BECOME(data1, WAIT_FOR_ACCEPT)
        SEND(accept)


      case (wait: DATA_WAIT_FOR_FUNDING_CREATED, created: FundingCreated, WAIT_FOR_ACCEPT) =>
        val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Helpers.Funding.makeFirstCommitTxs(wait.initFundee.remoteInfo, wait.initFundee.channelVersion,
          wait.initFundee.temporaryChannelId, wait.initFundee.localParams, wait.remoteParams, wait.initFundee.theirOpen.fundingSatoshis, wait.initFundee.theirOpen.pushMsat,
          wait.initFundee.theirOpen.feeratePerKw, created.fundingTxid, created.fundingOutputIndex, wait.initFundee.theirOpen.firstPerCommitmentPoint).right.get

        val fundingPubKey = wait.initFundee.remoteInfo.fundingPublicKey(wait.initFundee.localParams.fundingKeyPath)
        val localSigOfLocalTx = wait.initFundee.remoteInfo.sign(localCommitTx, fundingPubKey, TxOwner.Local, wait.initFundee.channelVersion.commitmentFormat)
        val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, wait.remoteParams.fundingPubKey, localSigOfLocalTx, created.signature)

        if (Transactions.checkSpendable(signedLocalCommitTx).isFailure) throw new RuntimeException

        val localSigOfRemoteTx = wait.initFundee.remoteInfo.sign(remoteCommitTx, fundingPubKey, TxOwner.Remote, wait.initFundee.channelVersion.commitmentFormat)
        val fundingSigned = FundingSigned(channelId = toLongId(created.fundingTxid, created.fundingOutputIndex), signature = localSigOfRemoteTx)

        val publishableTxs = PublishableTxs(signedLocalCommitTx, Nil)
        val remoteCommit = RemoteCommit(index = 0L, remoteSpec, remoteCommitTx.tx.txid, remotePerCommitmentPoint = wait.initFundee.theirOpen.firstPerCommitmentPoint)
        val commits = NormalCommits(wait.initFundee.channelVersion, wait.initFundee.remoteInfo, wait.initFundee.localParams, wait.remoteParams, wait.initFundee.theirOpen.channelFlags,
          LocalCommit(index = 0L, localSpec, publishableTxs), remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil), localNextHtlcId = 0L, remoteNextHtlcId = 0L,
          remoteNextCommitInfo = Right(randomKey.publicKey), signedLocalCommitTx.input, ShaChain.init, updateOpt = None, fundingSigned.channelId,
          startedAt = System.currentTimeMillis)

        chainWallet.watcher ! WatchSpent(receiver, commits.commitInput.outPoint.txid, commits.commitInput.outPoint.index.toInt, commits.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
        chainWallet.watcher ! WatchConfirmed(receiver, commits.commitInput.outPoint.txid, commits.commitInput.txOut.publicKeyScript, LNParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
        StoreBecomeSend(DATA_WAIT_FOR_FUNDING_CONFIRMED(commits, None, System.currentTimeMillis, Right(fundingSigned), deferred = None), WAIT_FUNDING_DONE, fundingSigned)

      // AWAITING CONFIRMATION

      case (wait: DATA_WAIT_FOR_FUNDING_CONFIRMED, event: WatchEventConfirmed, WAIT_FUNDING_DONE) =>
        // Remote peer may send a tx which is unrelated to our agreed upon channel funding, that is, we won't be able to spend our commit tx, check this right away
        def correct: Unit = Transaction.correctlySpends(wait.commitments.localCommit.publishableTxs.commitTx.tx, Seq(event.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        if (Try(correct).isFailure && wait.lastSent.isRight) BECOME(wait, CLOSING) else {
          val shortChannelId = ShortChannelId(event.blockHeight, event.txIndex, wait.commitments.commitInput.outPoint.index.toInt)
          val nextPerCommitmentPoint = wait.commitments.remoteInfo.commitmentPoint(wait.commitments.channelKeyPath, index = 1L)
          val fundingLocked = FundingLocked(wait.channelId, nextPerCommitmentPoint)

          val data1 = DATA_WAIT_FOR_FUNDING_LOCKED(wait.commitments, shortChannelId, fundingLocked)
          StoreBecomeSend(data1, WAIT_FUNDING_DONE, fundingLocked)
          wait.deferred.foreach(process)
        }


      case (wait: DATA_WAIT_FOR_FUNDING_CONFIRMED, locked: FundingLocked, WAIT_FUNDING_DONE) =>
        // No need to store their message, they will re-send if we get disconnected
        BECOME(wait.copy(deferred = locked.toSome), WAIT_FUNDING_DONE)


      case (wait: DATA_WAIT_FOR_FUNDING_LOCKED, locked: FundingLocked, WAIT_FUNDING_DONE) =>
        val commits1 = wait.commitments.modify(_.remoteNextCommitInfo) setTo Right(locked.nextPerCommitmentPoint)
        StoreBecomeSend(DATA_NORMAL(commits1, wait.shortChannelId), OPEN)

      // MAIN LOOP

      case (some: HasNormalCommitments, ann: NodeAnnouncement, OPEN | SLEEPING)
        if some.commitments.remoteInfo.nodeId == ann.nodeId && Announcements.checkSig(ann) && ann.addresses.nonEmpty =>
        val data1 = some.modify(_.commitments.remoteInfo).setTo(ann.remoteNodeInfo)
        data = STORE(data1)


      case (norm: DATA_NORMAL, update: ChannelUpdate, OPEN | SLEEPING)
        if norm.commitments.updateOpt.forall(update.timestamp > _.timestamp) &&
          Announcements.checkSig(update)(norm.commitments.remoteInfo.nodeId) &&
          update.shortChannelId == norm.shortChannelId =>

          // Refresh remote channel update without triggering of listeners
          val data1 = norm.modify(_.commitments.updateOpt).setTo(update.toSome)
          data = STORE(data1)


      // We may schedule shutdown while channel is offline
      case (norm: DATA_NORMAL, cmd: CMD_CLOSE, OPEN | SLEEPING) =>
        val localScriptPubKey = cmd.scriptPubKey.getOrElse(norm.commitments.localParams.defaultFinalScriptPubKey)
        val hasLocalHasUnsignedOutgoingHtlcs = NormalCommits.localHasUnsignedOutgoingHtlcs(norm.commitments)
        val isValidFinalScriptPubkey = Helpers.Closing.isValidFinalScriptPubkey(localScriptPubKey)
        val shutdown = Shutdown(norm.channelId, localScriptPubKey)

        if (hasLocalHasUnsignedOutgoingHtlcs) CMDException(CannotCloseWithUnsignedChanges(norm.channelId), cmd)
        else if (norm.localShutdown.isDefined) CMDException(ClosingAlreadyInProgress(norm.channelId), cmd)
        else if (!isValidFinalScriptPubkey) CMDException(InvalidFinalScript(norm.channelId), cmd)
        else StoreBecomeSend(norm.copy(localShutdown = shutdown.toSome), state, shutdown)


      case (norm: DATA_NORMAL, cmd: CMD_ADD_HTLC, state) =>
        if (OPEN != state || norm.localShutdown.isDefined || norm.remoteShutdown.isDefined) throw CMDException(ChannelUnavailable(norm.channelId), cmd)
        val (commits1, updateAddHtlcMsg) = NormalCommits.sendAdd(norm.commitments, cmd, LNParams.blockCount.get, LNParams.onChainFeeConf)
        BECOME(norm.copy(commitments = commits1), state)
        SEND(updateAddHtlcMsg)
        doProcess(CMD_SIGN)


      case (some: HasNormalCommitments, cmd: CMD_ADD_HTLC, _) =>
        throw CMDException(ChannelUnavailable(some.channelId), cmd)


      case (norm: DATA_NORMAL, cmd: CMD_FULFILL_HTLC, OPEN) =>
        val (commits1, fulfill) = NormalCommits.sendFulfill(norm.commitments, cmd)
        BECOME(norm.copy(commitments = commits1), OPEN)
        SEND(fulfill)


      case (norm: DATA_NORMAL, cmd: CMD_FAIL_HTLC, OPEN) =>
        val (commits1, fail) = NormalCommits.sendFail(norm.commitments, cmd)
        BECOME(norm.copy(commitments = commits1), OPEN)
        SEND(fail)


      case (norm: DATA_NORMAL, cmd: CMD_FAIL_MALFORMED_HTLC, OPEN) =>
        val (commits1, malformed) = NormalCommits.sendFailMalformed(norm.commitments, cmd)
        BECOME(norm.copy(commitments = commits1), OPEN)
        SEND(malformed)


      case (norm: DATA_NORMAL, CMD_SIGN, OPEN)
        // We have something to sign and remote unused pubKey, don't forget to store revoked HTLC data
        if NormalCommits.localHasChanges(norm.commitments) && norm.commitments.remoteNextCommitInfo.isRight =>

        val (commits1, commitSigMessage, nextRemoteCommit) = NormalCommits.sendCommit(norm.commitments)
        val out = Transactions.trimOfferedHtlcs(norm.commitments.remoteParams.dustLimit, nextRemoteCommit.spec, norm.commitments.channelVersion.commitmentFormat)
        val in = Transactions.trimReceivedHtlcs(norm.commitments.remoteParams.dustLimit, nextRemoteCommit.spec, norm.commitments.channelVersion.commitmentFormat)
        StoreBecomeSend(norm.copy(commitments = commits1), OPEN, commitSigMessage)
        bag.putHtlcInfos(out ++ in, norm.shortChannelId, nextRemoteCommit.index)


      case (norm: DATA_NORMAL, CMD_SIGN, OPEN)
        // We have nothing to sign so check for valid shutdown state, only consider this if we have nothing in-flight
        if norm.remoteShutdown.isDefined && !NormalCommits.localHasUnsignedOutgoingHtlcs(norm.commitments) =>
        val (data1, replies) = maybeStartNegotiations(norm, norm.remoteShutdown.get)
        StoreBecomeSend(data1, OPEN, replies:_*)


      case (norm: DATA_NORMAL, add: UpdateAddHtlc, OPEN) =>
        val commits1 = NormalCommits.receiveAdd(norm.commitments, add, LNParams.onChainFeeConf)
        BECOME(norm.copy(commitments = commits1), OPEN)
        events.addReceived(add)


      case (norm: DATA_NORMAL, fulfill: UpdateFulfillHtlc, OPEN) =>
        val (commits1, _) = NormalCommits.receiveFulfill(norm.commitments, fulfill)
        BECOME(norm.copy(commitments = commits1), OPEN)
        events.fulfillReceived(fulfill)


      case (norm: DATA_NORMAL, fail: UpdateFailHtlc, OPEN) =>
        val (commits1, _) = NormalCommits.receiveFail(norm.commitments, fail)
        BECOME(norm.copy(commitments = commits1), OPEN)


      case (norm: DATA_NORMAL, malformed: UpdateFailMalformedHtlc, OPEN) =>
        val (commits1, _) = NormalCommits.receiveFailMalformed(norm.commitments, malformed)
        BECOME(norm.copy(commitments = commits1), OPEN)


      case (norm: DATA_NORMAL, commitSig: CommitSig, OPEN) =>
        val (commits1, revocation) = NormalCommits.receiveCommit(norm.commitments, commitSig)
        StoreBecomeSend(norm.copy(commitments = commits1), OPEN, revocation)
        doProcess(CMD_SIGN)


      case (norm: DATA_NORMAL, revocation: RevokeAndAck, OPEN) =>
        val commits1 = NormalCommits.receiveRevocation(norm.commitments, revocation)
        StoreBecomeSend(norm.copy(commitments = commits1), OPEN)
        events.stateUpdated(norm.commitments.remoteRejects)


      case (norm: DATA_NORMAL, remoteFee: UpdateFee, OPEN) =>
        val commits1 = NormalCommits.receiveFee(norm.commitments, remoteFee, LNParams.onChainFeeConf)
        BECOME(norm.copy(commitments = commits1), OPEN)


      case (norm: DATA_NORMAL, remote: Shutdown, OPEN) =>
        val (data1, replies) = handleRemoteShutdown(norm, remote)
        StoreBecomeSend(data1, OPEN, replies:_*)

      // NEGOTIATIONS

      case (negs: DATA_NEGOTIATING, remote: ClosingSigned, OPEN) =>
        // Either become CLOSING or keep converging in OPEN
        handleNegotiations(negs, remote)

      // RESTORING FROM STORED DATA

      case (null, normalData: HasNormalCommitments, null) =>
        val commitInput: Transactions.InputInfo = normalData.commitments.commitInput
        chainWallet.watcher ! WatchSpent(receiver, commitInput.outPoint.txid, commitInput.outPoint.index.toInt, commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
        chainWallet.watcher ! WatchConfirmed(receiver, commitInput.outPoint.txid, commitInput.txOut.publicKeyScript, LNParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)

        normalData match {
          case data1: DATA_CLOSING =>
            BECOME(data1, state1 = CLOSING)
            Helpers.Closing.isClosingTypeAlreadyKnown(data1) match {
              case Some(close: Helpers.Closing.MutualClose) => doPublish(close.tx)
              case Some(close: Helpers.Closing.LocalClose) => doPublish(close.localCommitPublished)
              case Some(close: Helpers.Closing.RemoteClose) => doPublish(close.remoteCommitPublished)
              case Some(close: Helpers.Closing.RecoveryClose) => doPublish(close.remoteCommitPublished)
              case Some(close: Helpers.Closing.RevokedClose) => doPublish(close.revokedCommitPublished)

              case None =>
                for (close <- data1.mutualClosePublished) doPublish(close)
                for (close <- data1.localCommitPublished) doPublish(close)
                for (close <- data1.remoteCommitPublished) doPublish(close)
                for (close <- data1.revokedCommitPublished) doPublish(close)
                for (close <- data1.nextRemoteCommitPublished) doPublish(close)
                for (close <- data1.futureRemoteCommitPublished) doPublish(close)

                // if commitment number is zero, we also need to make sure that the funding tx has been published
                if (data1.commitments.localCommit.index == 0 && data1.commitments.remoteCommit.index == 0)
                  chainWallet.watcher ! GetTxWithMeta(commitInput.outPoint.txid)
            }

          case data1: DATA_WAIT_FOR_FUNDING_CONFIRMED =>
            chainWallet.watcher ! GetTxWithMeta(commitInput.outPoint.txid)
            BECOME(data1, SLEEPING)

          case data1 =>
            BECOME(data1, SLEEPING)
        }

      // OFFLINE IN PERSISTENT STATES

      case (_, CMD_SOCKET_OFFLINE, WAIT_FUNDING_DONE | OPEN) => BECOME(data, SLEEPING)

      // REESTABLISHMENT IN PERSISTENT STATES

      case (wait: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT, CMD_SOCKET_ONLINE, SLEEPING) =>
        // There isn't much to do except asking them again to publish their current commitment by sending an error
        val error = Error(wait.channelId, PleasePublishYourCommitment(wait.channelId).getMessage)
        BECOME(wait, CLOSING)
        SEND(error)


      case (data1: HasNormalCommitments, CMD_SOCKET_ONLINE, SLEEPING) =>
        val myCurrentPerCommitmentPoint = data1.commitments.remoteInfo.commitmentPoint(data1.commitments.channelKeyPath, data1.commitments.localCommit.index)
        val yourLastPerCommitmentSecret = data1.commitments.remotePerCommitmentSecrets.lastIndex.flatMap(data1.commitments.remotePerCommitmentSecrets.getHash).getOrElse(ByteVector32.Zeroes)
        val reestablish = ChannelReestablish(data1.channelId, data1.commitments.localCommit.index + 1, data1.commitments.remoteCommit.index, PrivateKey(yourLastPerCommitmentSecret), myCurrentPerCommitmentPoint)
        SEND(reestablish)


      case (data1: DATA_CLOSING, _: ChannelReestablish, CLOSING) =>
        val exception = FundingTxSpent(data1.channelId, data1.commitTxes.head)
        val error = Error(data1.channelId, exception.getMessage)
        SEND(error)


      case (wait: DATA_WAIT_FOR_FUNDING_CONFIRMED, _: ChannelReestablish, SLEEPING) =>
        // We put back the watch (operation is idempotent) because corresponding event may have been already fired while we were in SLEEPING
        chainWallet.watcher ! WatchConfirmed(receiver, wait.commitments.commitInput.outPoint.txid, wait.commitments.commitInput.txOut.publicKeyScript, LNParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
        BECOME(wait, OPEN)


      case (wait: DATA_WAIT_FOR_FUNDING_LOCKED, _: ChannelReestablish, SLEEPING) =>
        SEND(wait.lastSent)
        BECOME(wait, OPEN)


      case (data1: DATA_NORMAL, reestablish: ChannelReestablish, SLEEPING) => handleNormalSync(data1, reestablish)

      case (data1: DATA_NEGOTIATING, _: ChannelReestablish, SLEEPING) => handleNegotiationsSync(data1)

      case _ =>
    }
}
