package immortan

import fr.acinq.eclair._
import immortan.Channel._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.channel._
import akka.actor.{ActorRef, Props}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.blockchain.{MakeFundingTxResponse, WatchConfirmed, WatchSpent}
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.bitcoin.Script
import scodec.bits.ByteVector
import scala.util.Success


object NormalChannel {
  def make(initListeners: Set[ChannelListener], normalData: HasNormalCommitments, chainWallet: ChainWallet, bag: ChannelBag): NormalChannel = new NormalChannel(chainWallet) {
    def SEND(messages: LightningMessage *): Unit = CommsTower.sendMany(messages, normalData.commitments.announce.nodeSpecificPair)
    def STORE(normalData1: PersistentChannelData): PersistentChannelData = bag.put(normalData1)
    listeners = initListeners
    doProcess(normalData)
  }
}

abstract class NormalChannel(chainWallet: ChainWallet) extends Channel { me =>
  val receiver: ActorRef = LNParams.system actorOf Props(new Receiver)

  def doProcess(change: Any): Unit = {
    Tuple3(data, change, state) match {
      case (null, initFunder: INPUT_INIT_FUNDER, null) =>
        val channelKeyPath = initFunder.announce.keyPath(initFunder.localParams)
        val localFundingPubKey = initFunder.announce.fundingPublicKey(initFunder.localParams.fundingKeyPath).publicKey
        val emptyUpfrontShutdown: TlvStream[OpenChannelTlv] = TlvStream(ChannelTlv UpfrontShutdownScript ByteVector.empty)

        val open = OpenChannel(LNParams.chainHash, temporaryChannelId = initFunder.temporaryChannelId, fundingSatoshis = initFunder.fundingAmount, pushMsat = initFunder.pushAmount,
          dustLimitSatoshis = initFunder.localParams.dustLimit, maxHtlcValueInFlightMsat = initFunder.localParams.maxHtlcValueInFlightMsat, channelReserveSatoshis = initFunder.localParams.channelReserve,
          htlcMinimumMsat = initFunder.localParams.htlcMinimum, feeratePerKw = initFunder.initialFeeratePerKw, toSelfDelay = initFunder.localParams.toSelfDelay, maxAcceptedHtlcs = initFunder.localParams.maxAcceptedHtlcs,
          fundingPubkey = localFundingPubKey, revocationBasepoint = initFunder.announce.revocationPoint(channelKeyPath).publicKey, paymentBasepoint = initFunder.announce.paymentPoint(channelKeyPath).publicKey,
          delayedPaymentBasepoint = initFunder.announce.delayedPaymentPoint(channelKeyPath).publicKey, htlcBasepoint = initFunder.announce.htlcPoint(channelKeyPath).publicKey,
          firstPerCommitmentPoint = initFunder.announce.commitmentPoint(channelKeyPath, index = 0L), channelFlags = initFunder.channelFlags, tlvStream = emptyUpfrontShutdown)

        BECOME(DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder, open), WAIT_FOR_ACCEPT)
        SEND(open)


      case (wait: DATA_WAIT_FOR_ACCEPT_CHANNEL, accept: AcceptChannel, WAIT_FOR_ACCEPT) =>
        Helpers.validateParamsFunder(wait.lastSent, accept).foreach(exception => throw exception)

        val remoteParams = RemoteParams(dustLimit = accept.dustLimitSatoshis, maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat, channelReserve = accept.channelReserveSatoshis,
          htlcMinimum = accept.htlcMinimumMsat, toSelfDelay = accept.toSelfDelay, maxAcceptedHtlcs = accept.maxAcceptedHtlcs, fundingPubKey = accept.fundingPubkey, revocationBasepoint = accept.revocationBasepoint,
          paymentBasepoint = accept.paymentBasepoint, delayedPaymentBasepoint = accept.delayedPaymentBasepoint, htlcBasepoint = accept.htlcBasepoint, features = wait.initFunder.remoteInit.features)

        val multisig = Script pay2wsh Scripts.multiSig2of2(wait.lastSent.fundingPubkey, remoteParams.fundingPubKey)
        chainWallet.wallet.makeFundingTx(Script.write(multisig), wait.initFunder.fundingAmount, wait.initFunder.fundingTxFeeratePerKw).foreach(process)
        BECOME(DATA_WAIT_FOR_FUNDING_INTERNAL(wait.initFunder, remoteParams, accept.firstPerCommitmentPoint, wait.lastSent), WAIT_FOR_ACCEPT)


      case (wait: DATA_WAIT_FOR_FUNDING_INTERNAL, MakeFundingTxResponse(fundingTx, fundingTxOutputIndex, fundingTxFee), WAIT_FOR_ACCEPT) =>
        val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) = Helpers.Funding.makeFirstCommitTxs(wait.initFunder.announce, wait.initFunder.channelVersion,
          wait.initFunder.temporaryChannelId, wait.initFunder.localParams, wait.remoteParams, wait.initFunder.fundingAmount, wait.initFunder.pushAmount,
          wait.initFunder.initialFeeratePerKw, fundingTx.hash, fundingTxOutputIndex, wait.remoteFirstPerCommitmentPoint).right.get

        require(fundingTx.txOut(fundingTxOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript)

        val extendedFundingPubKey = wait.initFunder.announce.fundingPublicKey(wait.initFunder.localParams.fundingKeyPath)
        val localSigOfRemoteTx = wait.initFunder.announce.sign(remoteCommitTx, extendedFundingPubKey, TxOwner.Remote, wait.initFunder.channelVersion.commitmentFormat)
        val fundingCreated = FundingCreated(wait.initFunder.temporaryChannelId, fundingTxid = fundingTx.hash, fundingTxOutputIndex, localSigOfRemoteTx)

        SEND(fundingCreated)
        BECOME(DATA_WAIT_FOR_FUNDING_SIGNED(wait.initFunder.announce, channelId = toLongId(fundingTx.hash, fundingTxOutputIndex), wait.initFunder.localParams, wait.remoteParams, fundingTx,
          fundingTxFee, wait.initFunder.initialRelayFees, localSpec, localCommitTx, RemoteCommit(index = 0L, remoteSpec, remoteCommitTx.tx.txid, wait.remoteFirstPerCommitmentPoint),
          wait.lastSent.channelFlags, wait.initFunder.channelVersion, fundingCreated), WAIT_FOR_ACCEPT)


      case (wait: DATA_WAIT_FOR_FUNDING_SIGNED, signed: FundingSigned, WAIT_FOR_ACCEPT) =>
        val fundingPubKey = wait.announce.fundingPublicKey(wait.localParams.fundingKeyPath)
        val localSigOfLocalTx = wait.announce.sign(wait.localCommitTx, fundingPubKey, TxOwner.Local, wait.channelVersion.commitmentFormat)
        val signedLocalCommitTx = Transactions.addSigs(wait.localCommitTx, fundingPubKey.publicKey, wait.remoteParams.fundingPubKey, localSigOfLocalTx, signed.signature)

        if (Transactions.checkSpendable(signedLocalCommitTx).isFailure) {
          chainWallet.wallet.rollback(wait.fundingTx)
          throw new RuntimeException
        } else {
          val publishableTxs = PublishableTxs(signedLocalCommitTx, Nil)
          val commits = NormalCommits(wait.channelVersion, wait.announce, wait.localParams, wait.remoteParams, wait.channelFlags,
            LocalCommit(index = 0L, wait.localSpec, publishableTxs), wait.remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L, remoteNextCommitInfo = Right(randomKey.publicKey), signedLocalCommitTx.input, ShaChain.init,
            updateOpt = None, wait.channelId, startedAt = System.currentTimeMillis)

          chainWallet.watcher ! WatchSpent(receiver, commits.commitInput.outPoint.txid, commits.commitInput.outPoint.index.toInt, commits.commitInput.txOut.publicKeyScript, BITCOIN_FUNDING_SPENT)
          chainWallet.watcher ! WatchConfirmed(receiver, commits.commitInput.outPoint.txid, commits.commitInput.txOut.publicKeyScript, LNParams.minDepthBlocks, BITCOIN_FUNDING_DEPTHOK)
          val data1 = DATA_WAIT_FOR_FUNDING_CONFIRMED(commits, Some(wait.fundingTx), wait.initialRelayFees, System.currentTimeMillis, Left(wait.lastSent), deferred = None)
          BECOME(STORE(data1), WAIT_FUNDING_DONE)

          chainWallet.wallet.commit(wait.fundingTx) onComplete {
            case Success(false) => process(BITCOIN_FUNDING_PUBLISH_FAILED)
            case _ => // It's unknown whether tx has been published, do nothing
          }
        }


      case (null, inputFundee: INPUT_INIT_FUNDEE, null) =>
        val data1 = DATA_WAIT_FOR_OPEN_CHANNEL(inputFundee)
        BECOME(data1, WAIT_FOR_ACCEPT)
    }

    // Change has been processed without failures
    events onProcessSuccess Tuple3(me, data, change)
  }
}
