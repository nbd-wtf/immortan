package immortan.channel

import scala.collection.LazyZip3._
import com.softwaremill.quicklens._
import scoin.Crypto.PublicKey
import scoin._
import scoin.ln._

import immortan._
import immortan.utils.Rx
import immortan.channel._
import immortan.channel.DirectedHtlc._
import immortan.channel.Transactions._
import immortan.channel.Helpers.HashToPreimage

case class LocalChanges(
    proposed: List[UpdateMessage],
    signed: List[UpdateMessage],
    acked: List[UpdateMessage] = Nil
) {
  lazy val adds: List[UpdateAddHtlc] = all.collect { case add: UpdateAddHtlc =>
    add
  }
  lazy val all: List[UpdateMessage] = proposed ++ signed ++ acked
}

case class RemoteChanges(
    proposed: List[UpdateMessage],
    acked: List[UpdateMessage],
    signed: List[UpdateMessage] = Nil
) {
  lazy val adds: List[UpdateAddHtlc] = all.collect { case add: UpdateAddHtlc =>
    add
  }
  lazy val all: List[UpdateMessage] = proposed ++ signed ++ acked
}

case class HtlcTxAndSigs(
    txinfo: TransactionWithInputInfo,
    localSig: ByteVector64,
    remoteSig: ByteVector64
)

case class PublishableTxs(
    commitTx: CommitTx,
    htlcTxsAndSigs: List[HtlcTxAndSigs] = Nil
)

case class LocalCommit(
    index: Long,
    spec: CommitmentSpec,
    publishableTxs: PublishableTxs
)

case class RemoteCommit(
    index: Long,
    spec: CommitmentSpec,
    txid: ByteVector32,
    remotePerCommitmentPoint: PublicKey
)

case class WaitingForRevocation(
    nextRemoteCommit: RemoteCommit,
    sent: CommitSig,
    sentAfterLocalCommitIndex: Long
)

trait Commitments {
  def channelId: ByteVector32
  def remoteInfo: RemoteNodeInfo
  def updateOpt: Option[ChannelUpdate]
  def extParams: List[ExtParams]
  def startedAt: Long

  def minSendable: MilliSatoshi
  def maxSendInFlight: MilliSatoshi

  def availableForSend: MilliSatoshi
  def availableForReceive: MilliSatoshi

  def allOutgoing: Set[
    UpdateAddHtlc
  ] // Cross-signed PLUS not yet signed payments offered by us
  def crossSignedIncoming
      : Set[UpdateAddHtlcExt] // Cross-signed incoming payments offered by them
  def revealedFulfills: Set[
    LocalFulfill
  ] // Incoming payments for which we have releaved a preimge

  def getPendingFulfills(
      preimages: HashToPreimage = Map.empty
  ): Set[LocalFulfill] = for {
    // Find still present cross-signed incoming payments for which we have revealed a preimage
    UpdateAddHtlcExt(theirAdd, _) <- crossSignedIncoming
    ourPreimage <- preimages.get(theirAdd.paymentHash)
  } yield LocalFulfill(theirAdd, ourPreimage)
}

case class NormalCommits(
    channelFlags: Byte,
    channelId: ByteVector32,
    channelFeatures: ChannelFeatures,
    remoteNextCommitInfo: Either[WaitingForRevocation, PublicKey],
    remotePerCommitmentSecrets: ShaChain,
    updateOpt: Option[ChannelUpdate],
    postCloseOutgoingResolvedIds: Set[Long],
    remoteInfo: RemoteNodeInfo,
    localParams: LocalParams,
    remoteParams: RemoteParams,
    localCommit: LocalCommit,
    remoteCommit: RemoteCommit,
    localChanges: LocalChanges,
    remoteChanges: RemoteChanges,
    localNextHtlcId: Long,
    remoteNextHtlcId: Long,
    commitInput: InputInfo,
    extParams: List[ExtParams] = Nil,
    startedAt: Long = System.currentTimeMillis
) extends Commitments {

  lazy val minSendable: MilliSatoshi =
    remoteParams.htlcMinimum.max(localParams.htlcMinimum)

  lazy val maxSendInFlight: MilliSatoshi =
    MilliSatoshi(remoteParams.maxHtlcValueInFlightMsat.toLong)

  lazy val latestReducedRemoteSpec: CommitmentSpec = {
    val latestRemoteCommit = remoteNextCommitInfo.left.toOption
      .map(_.nextRemoteCommit)
      .getOrElse(remoteCommit)
    CommitmentSpec.reduce(
      latestRemoteCommit.spec,
      remoteChanges.acked,
      localChanges.proposed
    )
  }

  lazy val allOutgoing: Set[UpdateAddHtlc] = {
    val allOutgoingAdds = localCommit.spec.outgoingAdds ++ localChanges.adds
    allOutgoingAdds.filterNot(add =>
      postCloseOutgoingResolvedIds contains add.id
    )
  }

  lazy val crossSignedIncoming: Set[UpdateAddHtlcExt] =
    for (theirAdd <- remoteCommit.spec.outgoingAdds)
      yield UpdateAddHtlcExt(theirAdd, remoteInfo)

  lazy val revealedFulfills: Set[LocalFulfill] = getPendingFulfills(
    Helpers extractRevealedPreimages localChanges.all
  )

  lazy val availableForSend: MilliSatoshi = if (localParams.isFunder) {
    val commitFees = commitTxFeeMsat(
      remoteParams.dustLimit,
      latestReducedRemoteSpec,
      channelFeatures.commitmentFormat
    )
    val oneMoreHtlc = htlcOutputFee(
      latestReducedRemoteSpec.commitTxFeerate,
      channelFeatures.commitmentFormat
    )
    latestReducedRemoteSpec.toRemote - remoteParams.channelReserve - commitFees - oneMoreHtlc
  } else latestReducedRemoteSpec.toRemote - remoteParams.channelReserve

  lazy val availableForReceive: MilliSatoshi = {
    val reduced = CommitmentSpec.reduce(
      localCommit.spec,
      localChanges.acked,
      remoteChanges.proposed
    )

    if (!localParams.isFunder) {
      val commitFees = commitTxFeeMsat(
        localParams.dustLimit,
        reduced,
        channelFeatures.commitmentFormat
      )
      val manyMoreHtlc = htlcOutputFee(
        reduced.commitTxFeerate,
        channelFeatures.commitmentFormat
      ) * localParams.maxAcceptedHtlcs
      reduced.toRemote - localParams.channelReserve - commitFees - manyMoreHtlc
    } else reduced.toRemote - localParams.channelReserve
  }

  def hasPendingHtlcsOrFeeUpdate: Boolean = {
    val changes =
      localChanges.signed ++ localChanges.acked ++ remoteChanges.signed ++ remoteChanges.acked
    val pendingHtlcs =
      localCommit.spec.htlcs.nonEmpty || remoteCommit.spec.htlcs.nonEmpty || remoteNextCommitInfo.isLeft
    pendingHtlcs || changes.exists {
      case _: UpdateFee => true
      case _            => false
    }
  }

  def addLocalProposal(proposal: UpdateMessage): NormalCommits =
    this.modify(_.localChanges.proposed).using(_ :+ proposal)

  def addRemoteProposal(proposal: UpdateMessage): NormalCommits =
    this.modify(_.remoteChanges.proposed).using(_ :+ proposal)

  def localHasUnsignedOutgoingHtlcs: Boolean = localChanges.proposed.exists {
    case _: UpdateAddHtlc => true
    case _                => false
  }

  def remoteHasUnsignedOutgoingHtlcs: Boolean = remoteChanges.proposed.exists {
    case _: UpdateAddHtlc => true
    case _                => false
  }

  def remoteHasUnsignedOutgoingUpdateFee: Boolean =
    remoteChanges.proposed.exists {
      case _: UpdateFee => true
      case _            => false
    }

  def localHasChanges: Boolean =
    remoteChanges.acked.nonEmpty || localChanges.proposed.nonEmpty

  type UpdatedNCAndAdd = (NormalCommits, UpdateAddHtlc)

  def sendAdd(
      cmd: CMD_ADD_HTLC,
      blockHeight: Long
  ): Either[LocalReject, UpdatedNCAndAdd] = {
    if (LNParams.maxCltvExpiryDelta.toCltvExpiry(blockHeight) < cmd.cltvExpiry)
      return Left(InPrincipleNotSendable(cmd.incompleteAdd))
    if (CltvExpiry(blockHeight) >= cmd.cltvExpiry)
      return Left(InPrincipleNotSendable(cmd.incompleteAdd))
    if (cmd.firstAmount < minSendable)
      return Left(ChannelNotAbleToSend(cmd.incompleteAdd))

    val completeAdd =
      cmd.incompleteAdd.copy(channelId = channelId, id = localNextHtlcId)
    val commitments =
      addLocalProposal(completeAdd).copy(localNextHtlcId = localNextHtlcId + 1)
    val totalOutgoingHtlcs =
      commitments.latestReducedRemoteSpec.htlcs.collect(incoming).size

    val feeBuffer = htlcOutputFee(
      commitments.latestReducedRemoteSpec.commitTxFeerate,
      channelFeatures.commitmentFormat
    )
    val feerate = commitments.latestReducedRemoteSpec.copy(commitTxFeerate =
      commitments.latestReducedRemoteSpec.commitTxFeerate
    )
    val funderFeeBuffer = commitTxFeeMsat(
      commitments.remoteParams.dustLimit,
      feerate,
      channelFeatures.commitmentFormat
    ) + feeBuffer

    val receiverWithReserve =
      commitments.latestReducedRemoteSpec.toLocal - commitments.localParams.channelReserve
    val senderWithReserve =
      commitments.latestReducedRemoteSpec.toRemote - commitments.remoteParams.channelReserve
    val fees = commitTxFeeMsat(
      commitments.remoteParams.dustLimit,
      commitments.latestReducedRemoteSpec,
      channelFeatures.commitmentFormat
    ).truncateToSatoshi

    val missingForReceiver =
      if (commitments.localParams.isFunder) receiverWithReserve
      else receiverWithReserve - fees
    val missingForSender =
      if (commitments.localParams.isFunder)
        senderWithReserve - fees.max(funderFeeBuffer.truncateToSatoshi)
      else senderWithReserve

    if (missingForSender < Satoshi(0L))
      return Left(ChannelNotAbleToSend(cmd.incompleteAdd))
    if (missingForReceiver < Satoshi(0L) && !localParams.isFunder)
      return Left(ChannelNotAbleToSend(cmd.incompleteAdd))
    if (
      commitments.allOutgoing.foldLeft(MilliSatoshi(0L))(
        _ + _.amountMsat
      ) > maxSendInFlight
    ) return Left(ChannelNotAbleToSend(cmd.incompleteAdd))
    if (totalOutgoingHtlcs > commitments.remoteParams.maxAcceptedHtlcs)
      return Left(
        ChannelNotAbleToSend(
          cmd.incompleteAdd
        )
      ) // This is from spec and prevents remote force-close
    if (totalOutgoingHtlcs > commitments.localParams.maxAcceptedHtlcs)
      return Left(
        ChannelNotAbleToSend(
          cmd.incompleteAdd
        )
      ) // This is needed for peer backup and routing to safely work
    Right(commitments, completeAdd)
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): (NormalCommits, UpdateFulfillHtlc) = {
    val msg = UpdateFulfillHtlc(channelId, cmd.theirAdd.id, cmd.preimage)
    (addLocalProposal(msg), msg)
  }

  def receiveAdd(add: UpdateAddHtlc): NormalCommits = {
    if (localParams.htlcMinimum.max(MilliSatoshi(1L)) > add.amountMsat)
      throw ChannelTransitionFail(channelId, add)
    if (add.id != remoteNextHtlcId) throw ChannelTransitionFail(channelId, add)

    // Let's compute the current commitment *as seen by us* including this change
    val currentCommitments =
      addRemoteProposal(add).copy(remoteNextHtlcId = remoteNextHtlcId + 1)
    val reduced = CommitmentSpec.reduce(
      currentCommitments.localCommit.spec,
      currentCommitments.localChanges.acked,
      currentCommitments.remoteChanges.proposed
    )

    val senderWithReserve =
      reduced.toRemote - currentCommitments.localParams.channelReserve
    val receiverWithReserve =
      reduced.toLocal - currentCommitments.remoteParams.channelReserve
    val fees = commitTxFeeMsat(
      currentCommitments.remoteParams.dustLimit,
      reduced,
      channelFeatures.commitmentFormat
    ).truncateToSatoshi
    val missingForSender =
      if (currentCommitments.localParams.isFunder) senderWithReserve
      else senderWithReserve - fees
    val missingForReceiver =
      if (currentCommitments.localParams.isFunder) receiverWithReserve - fees
      else receiverWithReserve

    if (missingForSender < Satoshi(0L))
      throw ChannelTransitionFail(channelId, add)
    else if (missingForReceiver < Satoshi(0L) && localParams.isFunder)
      throw ChannelTransitionFail(channelId, add)

    // We do not check whether total incoming payments amount exceeds our local
    // maxHtlcValueInFlightMsat becase it is always set to a whole channel capacity

    if (
      reduced.htlcs
        .collect(incoming)
        .size > currentCommitments.localParams.maxAcceptedHtlcs
    ) throw ChannelTransitionFail(channelId, add)

    currentCommitments
  }

  def receiveFulfill(
      fulfill: UpdateFulfillHtlc
  ): (NormalCommits, UpdateAddHtlc) =
    localCommit.spec.findOutgoingHtlcById(fulfill.id) match {
      case Some(ourAdd) if ourAdd.add.paymentHash != fulfill.paymentHash =>
        throw ChannelTransitionFail(channelId, fulfill)
      case Some(ourAdd) => (addRemoteProposal(fulfill), ourAdd.add)
      case None         => throw ChannelTransitionFail(channelId, fulfill)
    }

  def receiveFail(fail: UpdateFailHtlc): NormalCommits =
    localCommit.spec.findOutgoingHtlcById(fail.id) match {
      case None => throw ChannelTransitionFail(channelId, fail)
      case _    => addRemoteProposal(fail)
    }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): NormalCommits =
    localCommit.spec.findOutgoingHtlcById(fail.id) match {
      case None => throw ChannelTransitionFail(channelId, fail)
      case _    => addRemoteProposal(fail)
    }

  def sendFee(rate: FeeratePerKw): (NormalCommits, Satoshi, UpdateFee) = {
    val msg: UpdateFee = UpdateFee(channelId = channelId, feeratePerKw = rate)
    // Let's compute the current commitment *as seen by them* with this change taken into account
    val currentCommitments = this
      .modify(_.localChanges.proposed)
      .using(changes =>
        changes.filter {
          case _: UpdateFee => false
          case _            => true
        } :+ msg
      )
    val fees = commitTxFeeMsat(
      currentCommitments.remoteParams.dustLimit,
      currentCommitments.latestReducedRemoteSpec,
      channelFeatures.commitmentFormat
    ).truncateToSatoshi
    val reserve =
      currentCommitments.latestReducedRemoteSpec.toRemote.truncateToSatoshi - currentCommitments.remoteParams.channelReserve - fees
    (currentCommitments, reserve, msg)
  }

  def receiveFee(fee: UpdateFee): NormalCommits = {
    if (localParams.isFunder) throw ChannelTransitionFail(channelId, fee)
    if (fee.feeratePerKw < FeeratePerKw.MinimumFeeratePerKw)
      throw ChannelTransitionFail(channelId, fee)
    val currentCommitments = this
      .modify(_.remoteChanges.proposed)
      .using(changes =>
        changes.filter {
          case _: UpdateFee => false
          case _            => true
        } :+ fee
      )
    val reduced = CommitmentSpec.reduce(
      currentCommitments.localCommit.spec,
      currentCommitments.localChanges.acked,
      currentCommitments.remoteChanges.proposed
    )

    val threshold = Transactions.offeredHtlcTrimThreshold(
      remoteParams.dustLimit,
      localCommit.spec,
      channelFeatures.commitmentFormat
    )
    val largeRoutedExist = allOutgoing.exists(ourAdd =>
      ourAdd.amountMsat > threshold * LNParams.minForceClosableOutgoingHtlcAmountToFeeRatio && ourAdd.fullTag.tag == PaymentTagTlv.TRAMPLOINE_ROUTED
    )
    val dangerousState = largeRoutedExist && newFeerate(
      LNParams.feeRates.info,
      reduced,
      LNParams.shouldForceClosePaymentFeerateDiff
    ).isDefined && fee.feeratePerKw < currentCommitments.localCommit.spec.commitTxFeerate

    if (dangerousState) {
      // We force feerate update and block this thread while it's being executed, will have an updated info once done
      Rx.retry(
        Rx.ioQueue.map(_ => LNParams.feeRates.reloadData),
        Rx.incSec,
        1 to 3
      ).toBlocking
        .subscribe(LNParams.feeRates.updateInfo, none)
      // We have seen a suspiciously lower feerate update from peer, then force-checked current network feerates and they are NOT THAT low
      val stillDangerousState = newFeerate(
        LNParams.feeRates.info,
        reduced,
        LNParams.shouldForceClosePaymentFeerateDiff
      ).isDefined
      // It is too dangerous to have outgoing routed HTLCs with such a low feerate since they may not get confirmed in time
      if (stillDangerousState) throw ChannelTransitionFail(channelId, fee)
    }

    val fees = commitTxFeeMsat(
      currentCommitments.remoteParams.dustLimit,
      reduced,
      channelFeatures.commitmentFormat
    ).truncateToSatoshi
    val missing =
      reduced.toRemote.truncateToSatoshi - currentCommitments.localParams.channelReserve - fees
    if (missing < Satoshi(0L)) throw ChannelTransitionFail(channelId, fee)
    currentCommitments
  }

  def sendCommit: (NormalCommits, CommitSig, RemoteCommit) = {
    val remoteNextPoint = remoteNextCommitInfo.toOption.get

    val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) =
      NormalCommits.makeRemoteTxs(
        channelFeatures,
        remoteCommit.index + 1,
        localParams,
        remoteParams,
        commitInput,
        remoteNextPoint,
        latestReducedRemoteSpec
      )

    val sortedHtlcTxs =
      (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
    val htlcSigs =
      for (htlc <- sortedHtlcTxs)
        yield localParams.keys.sign(
          htlc,
          localParams.keys.htlcKey.privateKey,
          remoteNextPoint,
          TxOwner.Remote,
          channelFeatures.commitmentFormat
        )
    val commitSig = CommitSig(
      channelId,
      Transactions.sign(
        remoteCommitTx,
        localParams.keys.fundingKey.privateKey,
        TxOwner.Remote,
        channelFeatures.commitmentFormat
      ),
      htlcSigs.toList
    )
    val waiting = WaitingForRevocation(
      RemoteCommit(
        remoteCommit.index + 1,
        latestReducedRemoteSpec,
        remoteCommitTx.tx.txid,
        remoteNextPoint
      ),
      commitSig,
      localCommit.index
    )

    (
      copy(
        remoteNextCommitInfo = Left(waiting),
        localChanges =
          localChanges.copy(proposed = Nil, signed = localChanges.proposed),
        remoteChanges =
          remoteChanges.copy(acked = Nil, signed = remoteChanges.acked)
      ),
      commitSig,
      waiting.nextRemoteCommit
    )
  }

  def receiveCommit(commit: CommitSig): (NormalCommits, RevokeAndAck) = {
    val localPerCommitmentPoint =
      localParams.keys.commitmentPoint(localCommit.index + 1)
    val spec = CommitmentSpec.reduce(
      localCommit.spec,
      localChanges.acked,
      remoteChanges.proposed
    )

    val (localCommitTx, htlcTimeoutTxs, htlcSuccessTxs) =
      NormalCommits.makeLocalTxs(
        channelFeatures,
        localCommit.index + 1,
        localParams,
        remoteParams,
        commitInput,
        localPerCommitmentPoint,
        spec
      )

    val sortedHtlcTxs =
      (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
    val localCommitTxSig = Transactions.sign(
      localCommitTx,
      localParams.keys.fundingKey.privateKey,
      TxOwner.Local,
      channelFeatures.commitmentFormat
    )
    val signedCommitTx = Transactions.addSigs(
      localCommitTx,
      localParams.keys.fundingKey.publicKey,
      remoteParams.fundingPubKey,
      localCommitTxSig,
      commit.signature
    )
    val htlcSigs =
      for (htlc <- sortedHtlcTxs)
        yield localParams.keys.sign(
          htlc,
          localParams.keys.htlcKey.privateKey,
          localPerCommitmentPoint,
          TxOwner.Local,
          channelFeatures.commitmentFormat
        )
    val remoteHtlcPubkey = Generators.derivePubKey(
      remoteParams.htlcBasepoint,
      localPerCommitmentPoint
    )
    val combined =
      sortedHtlcTxs.lazyZip(htlcSigs).lazyZip(commit.htlcSignatures).toList

    if (Transactions.checkSpendable(signedCommitTx).isFailure)
      throw ChannelTransitionFail(channelId, commit)
    if (commit.htlcSignatures.size != sortedHtlcTxs.size)
      throw ChannelTransitionFail(channelId, commit)

    val htlcTxsAndSigs = combined.collect {
      case (htlcTx: HtlcTimeoutTx, localSig, remoteSig) =>
        val withSigs = Transactions.addSigs(
          htlcTx,
          localSig,
          remoteSig,
          channelFeatures.commitmentFormat
        )
        if (Transactions.checkSpendable(withSigs).isFailure)
          throw ChannelTransitionFail(channelId, commit)
        HtlcTxAndSigs(htlcTx, localSig, remoteSig)

      case (htlcTx: HtlcSuccessTx, localSig, remoteSig) =>
        // We can't check that htlc-success tx are spendable because we need the payment preimage
        // Thus we only check the remote sig, we verify the signature from their point of view, where it is a remote tx
        val sigChecks = Transactions.checkSig(
          htlcTx,
          remoteSig,
          remoteHtlcPubkey,
          TxOwner.Remote,
          channelFeatures.commitmentFormat
        )
        if (!sigChecks) throw ChannelTransitionFail(channelId, commit)
        HtlcTxAndSigs(htlcTx, localSig, remoteSig)
    }

    val publishableTxs = PublishableTxs(signedCommitTx, htlcTxsAndSigs)
    val localPerCommitmentSecret =
      localParams.keys.commitmentSecret(localCommit.index)
    val localNextPerCommitmentPoint =
      localParams.keys.commitmentPoint(localCommit.index + 2)
    val localCommit1 = LocalCommit(
      index = localCommit.index + 1,
      spec,
      publishableTxs = publishableTxs
    )
    val theirChanges1 = remoteChanges.copy(
      proposed = Nil,
      acked = remoteChanges.acked ++ remoteChanges.proposed
    )
    val revocation = RevokeAndAck(
      channelId,
      perCommitmentSecret = localPerCommitmentSecret,
      nextPerCommitmentPoint = localNextPerCommitmentPoint
    )
    val commitments1 = copy(
      localCommit = localCommit1,
      localChanges = localChanges.copy(acked = Nil),
      remoteChanges = theirChanges1
    )
    (commitments1, revocation)
  }

  def receiveRevocation(revocation: RevokeAndAck): NormalCommits =
    remoteNextCommitInfo match {
      case Left(d1: WaitingForRevocation)
          if revocation.perCommitmentSecret.publicKey == remoteCommit.remotePerCommitmentPoint =>
        val remotePerCommitmentSecrets1 = remotePerCommitmentSecrets.addHash(
          revocation.perCommitmentSecret.value,
          0xffffffffffffL - remoteCommit.index
        )
        copy(
          localChanges = localChanges.copy(
            signed = Nil,
            acked = localChanges.acked ++ localChanges.signed
          ),
          remoteChanges = remoteChanges.copy(signed = Nil),
          remoteCommit = d1.nextRemoteCommit,
          remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          remotePerCommitmentSecrets = remotePerCommitmentSecrets1
        )

      case _ =>
        throw ChannelTransitionFail(channelId, revocation)
    }
}

object NormalCommits {
  type HtlcTimeoutTxSeq = Seq[HtlcTimeoutTx]
  type HtlcSuccessTxSeq = Seq[HtlcSuccessTx]

  def makeLocalTxs(
      channelFeatures: ChannelFeatures,
      commitTxNumber: Long,
      localParams: LocalParams,
      remoteParams: RemoteParams,
      commitmentInput: InputInfo,
      localPerCommitmentPoint: PublicKey,
      spec: CommitmentSpec
  ): (CommitTx, HtlcTimeoutTxSeq, HtlcSuccessTxSeq) = {

    val localDelayedPayment = Generators.derivePubKey(
      localParams.keys.delayedPaymentKey.publicKey,
      localPerCommitmentPoint
    )
    val localRevocation = Generators.revocationPubKey(
      remoteParams.revocationBasepoint,
      localPerCommitmentPoint
    )
    val localHtlc = Generators.derivePubKey(
      localParams.keys.htlcKey.publicKey,
      localPerCommitmentPoint
    )
    val remoteHtlc = Generators.derivePubKey(
      remoteParams.htlcBasepoint,
      localPerCommitmentPoint
    )

    val outputs: CommitmentOutputs =
      makeCommitTxOutputs(
        localParams.isFunder,
        localParams.dustLimit,
        localRevocation,
        remoteParams.toSelfDelay,
        localDelayedPayment,
        remoteParams.paymentBasepoint,
        localHtlc,
        remoteHtlc,
        localParams.keys.fundingKey.publicKey,
        remoteParams.fundingPubKey,
        spec,
        channelFeatures.commitmentFormat
      )

    val commitTx =
      makeCommitTx(
        commitmentInput,
        commitTxNumber,
        localParams.walletStaticPaymentBasepoint,
        remoteParams.paymentBasepoint,
        localParams.isFunder,
        outputs
      )

    val (timeouts, successes) = makeHtlcTxs(
      commitTx.tx,
      localParams.dustLimit,
      localRevocation,
      remoteParams.toSelfDelay,
      localDelayedPayment,
      spec.commitTxFeerate,
      outputs,
      channelFeatures.commitmentFormat
    )

    (commitTx, timeouts, successes)
  }

  def makeRemoteTxs(
      channelFeatures: ChannelFeatures,
      commitTxNumber: Long,
      localParams: LocalParams,
      remoteParams: RemoteParams,
      commitmentInput: InputInfo,
      remotePerCommitmentPoint: PublicKey,
      spec: CommitmentSpec
  ): (CommitTx, HtlcTimeoutTxSeq, HtlcSuccessTxSeq) = {

    val localHtlc = Generators.derivePubKey(
      localParams.keys.htlcKey.publicKey,
      remotePerCommitmentPoint
    )
    val remoteDelayedPayment = Generators.derivePubKey(
      remoteParams.delayedPaymentBasepoint,
      remotePerCommitmentPoint
    )
    val remoteRevocation = Generators.revocationPubKey(
      localParams.keys.revocationKey.publicKey,
      remotePerCommitmentPoint
    )
    val remoteHtlc = Generators.derivePubKey(
      remoteParams.htlcBasepoint,
      remotePerCommitmentPoint
    )

    val outputs: CommitmentOutputs =
      makeCommitTxOutputs(
        !localParams.isFunder,
        remoteParams.dustLimit,
        remoteRevocation,
        localParams.toSelfDelay,
        remoteDelayedPayment,
        localParams.walletStaticPaymentBasepoint,
        remoteHtlc,
        localHtlc,
        remoteParams.fundingPubKey,
        localParams.keys.fundingKey.publicKey,
        spec,
        channelFeatures.commitmentFormat
      )

    val commitTx =
      makeCommitTx(
        commitmentInput,
        commitTxNumber,
        remoteParams.paymentBasepoint,
        localParams.walletStaticPaymentBasepoint,
        !localParams.isFunder,
        outputs
      )

    val (timeouts, successes) =
      makeHtlcTxs(
        commitTx.tx,
        remoteParams.dustLimit,
        remoteRevocation,
        localParams.toSelfDelay,
        remoteDelayedPayment,
        spec.commitTxFeerate,
        outputs,
        channelFeatures.commitmentFormat
      )

    (commitTx, timeouts, successes)
  }
}

sealed trait ExtParams
case class ChannelLabel(label: String) extends ExtParams
