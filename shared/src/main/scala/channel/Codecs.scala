package immortan.channel

import scodec.{Codec, Attempt}
import scodec.codecs._
import scodec.bits.ByteVector
import scoin._
import scoin.Crypto.PublicKey
import scoin.DeterministicWallet.{
  KeyPath,
  ExtendedPrivateKey,
  ExtendedPublicKey
}
import scoin.ln._
import scoin.ln.CommonCodecs._
import scoin.ln.LightningMessageCodecs._
import scoin.hc._
import scoin.hc.HostedChannelCodecs._

import immortan._
import immortan.channel.Transactions._
import immortan.blockchain.TxConfirmedAt

case class HostedState(
    nodeId1: PublicKey,
    nodeId2: PublicKey,
    lastCrossSignedState: LastCrossSignedState
)

object Codecs {
  def mapCodec[K, V](
      keyCodec: Codec[K],
      valueCodec: Codec[V]
  ): Codec[Map[K, V]] =
    listOfN(uint16, keyCodec ~ valueCodec).xmap(_.toMap, _.toList)

  val text: Codec[String] = variableSizeBytes(uint16, utf8)

  val compressedByteVecCodec = {
    val plain = variableSizeBytes(uint24, bytes)
    zlib(plain)
  }

  val hostedStateCodec = {
    ("nodeId1" | publicKey) ::
      ("nodeId2" | publicKey) ::
      ("lastCrossSignedState" | lastCrossSignedStateCodec)
  }.as[HostedState]

  // All LN protocol message must be stored as length-delimited, because they may have arbitrary trailing data
  def lengthDelimited[T](codec: Codec[T]): Codec[T] =
    variableSizeBytesLong(varintoverflow, codec)

  val keyPathCodec = ("path" | listOfN(uint16, uint32))
    .xmap[KeyPath](KeyPath.apply, _.path.toList)
    .as[KeyPath]

  val extendedPrivateKeyCodec = {
    ("secretkeybytes" | bytes32) ::
      ("chaincode" | bytes32) ::
      ("depth" | uint16) ::
      ("path" | keyPathCodec) ::
      ("parent" | int64)
  }.as[ExtendedPrivateKey]

  val extendedPublicKeyCodec = {
    ("publickeybytes" | varsizebinarydata) ::
      ("chaincode" | bytes32) ::
      ("depth" | uint16) ::
      ("path" | keyPathCodec) ::
      ("parent" | int64)
  }.as[ExtendedPublicKey]

  val outPointCodec = lengthDelimited(
    bytes.xmap(d => OutPoint.read(d.toArray), OutPoint.write)
  )

  val txOutCodec = lengthDelimited(
    bytes.xmap(d => TxOut.read(d.toArray), TxOut.write)
  )

  val txCodec = lengthDelimited(
    bytes.xmap(d => Transaction.read(d.toArray), Transaction.write)
  )

  def setCodec[T](codec: Codec[T]): Codec[Set[T]] =
    listOfN(uint16, codec).xmap(_.toSet, _.toList)

  val bool8: Codec[Boolean] = bool(8)

  val htlcCodec =
    discriminated[DirectedHtlc]
      .by(bool8)
      .typecase(true, lengthDelimited(updateAddHtlcCodec).as[IncomingHtlc])
      .typecase(false, lengthDelimited(updateAddHtlcCodec).as[OutgoingHtlc])

  val commitmentSpecCodec = (
    ("htlcs" | setCodec(htlcCodec)) ::
      ("toLocal" | millisatoshi) ::
      ("toRemote" | millisatoshi) ::
      ("commitTxFeerate" | feeratePerKw)
  ).as[CommitmentSpec]

  val inputInfoCodec = (
    ("outPoint" | outPointCodec) ::
      ("txOut" | txOutCodec) ::
      ("redeemScript" | lengthDelimited(bytes))
  ).as[InputInfo]

  val commitTxCodec = {
    ("inputInfo" | inputInfoCodec) ::
      ("tx" | txCodec)
  }.as[CommitTx]

  val txWithInputInfoCodec =
    discriminated[TransactionWithInputInfo]
      .by(uint16)
      .typecase(0x01, commitTxCodec)
      .typecase(
        0x02,
        (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32))
          .as[HtlcSuccessTx]
      )
      .typecase(
        0x03,
        (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcTimeoutTx]
      )
      .typecase(
        0x04,
        (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec))
          .as[ClaimHtlcSuccessTx]
      )
      .typecase(
        0x05,
        (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec))
          .as[ClaimHtlcTimeoutTx]
      )
      .typecase(
        0x06,
        (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec))
          .as[ClaimP2WPKHOutputTx]
      )
      .typecase(
        0x07,
        (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec))
          .as[ClaimLocalDelayedOutputTx]
      )
      .typecase(
        0x08,
        (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[MainPenaltyTx]
      )
      .typecase(
        0x09,
        (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcPenaltyTx]
      )
      .typecase(
        0x10,
        (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClosingTx]
      )

  val htlcTxAndSigsCodec = {
    ("txinfo" | txWithInputInfoCodec) ::
      ("localSig" | lengthDelimited(bytes64)) ::
      ("remoteSig" | lengthDelimited(bytes64))
  }.as[HtlcTxAndSigs]

  val publishableTxsCodec = (
    ("commitTx" | commitTxCodec) ::
      ("htlcTxsAndSigs" | listOfN(uint16, htlcTxAndSigsCodec))
  ).as[PublishableTxs]

  val localCommitCodec = (
    ("index" | uint64overflow) ::
      ("spec" | commitmentSpecCodec) ::
      ("publishableTxs" | publishableTxsCodec)
  ).as[LocalCommit]

  val remoteCommitCodec = (
    ("index" | uint64overflow) ::
      ("spec" | commitmentSpecCodec) ::
      ("txid" | bytes32) ::
      ("remotePerCommitmentPoint" | publicKey)
  ).as[RemoteCommit]

  val updateMessageCodec = lengthDelimited {
    lightningMessageCodec.narrow(
      (msg: LightningMessage) =>
        Attempt.successful(msg.asInstanceOf[UpdateMessage]),
      (msg: UpdateMessage) => msg.asInstanceOf[LightningMessage]
    )
  }

  val localChangesCodec = (
    ("proposed" | listOfN(uint16, updateMessageCodec)) ::
      ("signed" | listOfN(uint16, updateMessageCodec)) ::
      ("acked" | listOfN(uint16, updateMessageCodec))
  ).as[LocalChanges]

  val remoteChangesCodec = (
    ("proposed" | listOfN(uint16, updateMessageCodec)) ::
      ("acked" | listOfN(uint16, updateMessageCodec)) ::
      ("signed" | listOfN(uint16, updateMessageCodec))
  ).as[RemoteChanges]

  val waitingForRevocationCodec = (
    ("nextRemoteCommit" | remoteCommitCodec) ::
      ("sent" | lengthDelimited(commitSigCodec)) ::
      ("sentAfterLocalCommitIndex" | uint64overflow)
  ).as[WaitingForRevocation]

  val channelKeysCodec = (
    ("path" | keyPathCodec) ::
      ("shaSeed" | bytes32) ::
      ("fundingKey" | extendedPrivateKeyCodec) ::
      ("revocationKey" | extendedPrivateKeyCodec) ::
      ("paymentKey" | extendedPrivateKeyCodec) ::
      ("delayedPaymentKey" | extendedPrivateKeyCodec) ::
      ("htlcKey" | extendedPrivateKeyCodec)
  ).as[ChannelKeys]

  val channelFeaturesCodec: Codec[ChannelFeatures] =
    lengthDelimited(bytes).xmap(
      (b: ByteVector) =>
        ChannelFeatures(
          Features(b).activated.keySet
        ), // we make no difference between mandatory/optional, both are considered activated
      (cf: ChannelFeatures) =>
        Features(
          cf.activated.map(f => f -> FeatureSupport.Mandatory).toMap
        ).toByteVector // we encode features as mandatory, by convention
    )

  val localParamsCodec = (
    ("keys" | channelKeysCodec) ::
      ("dustLimit" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserve" | satoshi) ::
      ("htlcMinimum" | millisatoshi) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("isFunder" | bool8) ::
      ("defaultFinalScriptPubKey" | lengthDelimited(bytes)) ::
      ("walletStaticPaymentBasepoint" | publicKey)
  ).as[LocalParams]

  val remoteParamsCodec = (
    ("dustLimit" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserve" | satoshi) ::
      ("htlcMinimum" | millisatoshi) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubKey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("shutdownScript" | optional(bool8, lengthDelimited(bytes)))
  ).as[RemoteParams]

  val remoteNodeInfoCodec = (
    ("nodeId" | publicKey) ::
      ("address" | nodeaddress) ::
      ("alias" | zeropaddedstring(32))
  ).as[RemoteNodeInfo]

  val channelLabelCodec = ("label" | text).as[ChannelLabel]

  val extParamsCodec =
    discriminated[ExtParams]
      .by(uint16)
      .\(1) { case v: ChannelLabel => v }(channelLabelCodec)

  val commitmentsCodec = (
    ("channelFlags" | byte) ::
      ("channelId" | bytes32) ::
      ("channelFeatures" | channelFeaturesCodec) ::
      ("remoteNextCommitInfo" | either(
        bool8,
        waitingForRevocationCodec,
        publicKey
      )) ::
      ("remotePerCommitmentSecrets" | byteAligned(
        ShaChain.shaChainCodec
      )) ::
      ("updateOpt" | optional(
        bool8,
        lengthDelimited(channelUpdateCodec)
      )) ::
      ("postCloseOutgoingResolvedIds" | setCodec(uint64overflow)) ::
      ("remoteInfo" | remoteNodeInfoCodec) ::
      ("localParams" | localParamsCodec) ::
      ("remoteParams" | remoteParamsCodec) ::
      ("localCommit" | localCommitCodec) ::
      ("remoteCommit" | remoteCommitCodec) ::
      ("localChanges" | localChangesCodec) ::
      ("remoteChanges" | remoteChangesCodec) ::
      ("localNextHtlcId" | uint64overflow) ::
      ("remoteNextHtlcId" | uint64overflow) ::
      ("commitInput" | inputInfoCodec) ::
      ("extParams" | listOfN(uint16, extParamsCodec)) ::
      ("startedAt" | int64)
  ).as[NormalCommits]

  val closingTxProposedCodec = (
    ("unsignedTx" | txCodec) ::
      ("localClosingSigned" | lengthDelimited(closingSignedCodec))
  ).as[ClosingTxProposed]

  val txConfirmedAtCodec = (
    ("blockHeight" | uint24) ::
      ("tx" | txCodec)
  ).as[TxConfirmedAt]

  val spentMapCodec = mapCodec(outPointCodec, txConfirmedAtCodec)

  val localCommitPublishedCodec = (
    ("commitTx" | txCodec) ::
      ("claimMainDelayedOutputTx" | optional(bool8, txCodec)) ::
      ("htlcSuccessTxs" | listOfN(uint16, txCodec)) ::
      ("htlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcDelayedTx" | listOfN(uint16, txCodec)) ::
      ("irrevocablySpent" | spentMapCodec)
  ).as[LocalCommitPublished]

  val remoteCommitPublishedCodec = (
    ("commitTx" | txCodec) ::
      ("claimMainOutputTx" | optional(bool8, txCodec)) ::
      ("claimHtlcSuccessTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
      ("irrevocablySpent" | spentMapCodec)
  ).as[RemoteCommitPublished]

  val revokedCommitPublishedCodec = (
    ("commitTx" | txCodec) ::
      ("claimMainOutputTx" | optional(bool8, txCodec)) ::
      ("mainPenaltyTx" | optional(bool8, txCodec)) ::
      ("htlcPenaltyTxs" | listOfN(uint16, txCodec)) ::
      ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, txCodec)) ::
      ("irrevocablySpent" | spentMapCodec)
  ).as[RevokedCommitPublished]

  val DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec = (
    ("commitments" | commitmentsCodec) ::
      ("fundingTx" | optional(bool8, txCodec)) ::
      ("waitingSince" | int64) ::
      ("lastSent" | either(
        bool8,
        lengthDelimited(fundingCreatedCodec),
        lengthDelimited(fundingSignedCodec)
      )) ::
      ("deferred" | optional(
        bool8,
        lengthDelimited(fundingLockedCodec)
      ))
  ).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

  val DATA_WAIT_FOR_FUNDING_LOCKED_Codec = (
    ("commitments" | commitmentsCodec) ::
      ("shortChannelId" | int64) ::
      ("lastSent" | lengthDelimited(fundingLockedCodec))
  ).as[DATA_WAIT_FOR_FUNDING_LOCKED]

  val DATA_NORMAL_Codec = (
    ("commitments" | commitmentsCodec) ::
      ("shortChannelId" | int64) ::
      ("feeUpdateRequired" | bool8) ::
      ("extParams" | listOfN(uint16, varsizebinarydata)) ::
      ("localShutdown" | optional(
        bool8,
        lengthDelimited(shutdownCodec)
      )) ::
      ("remoteShutdown" | optional(
        bool8,
        lengthDelimited(shutdownCodec)
      ))
  ).as[DATA_NORMAL]

  val DATA_NEGOTIATING_Codec = (
    ("commitments" | commitmentsCodec) ::
      ("localShutdown" | lengthDelimited(shutdownCodec)) ::
      ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
      ("closingTxProposed" | listOfN(
        uint16,
        listOfN(uint16, lengthDelimited(closingTxProposedCodec))
      )) ::
      ("bestUnpublishedClosingTxOpt" | optional(bool8, txCodec))
  ).as[DATA_NEGOTIATING]

  val DATA_CLOSING_Codec = (
    ("commitments" | commitmentsCodec) ::
      ("waitingSince" | int64) ::
      ("mutualCloseProposed" | listOfN(uint16, txCodec)) ::
      ("mutualClosePublished" | listOfN(uint16, txCodec)) ::
      ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
      ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
      ("nextRemoteCommitPublished" | optional(
        bool8,
        remoteCommitPublishedCodec
      )) ::
      ("futureRemoteCommitPublished" | optional(
        bool8,
        remoteCommitPublishedCodec
      )) ::
      ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))
  ).as[DATA_CLOSING]

  val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec = (
    ("commitments" | commitmentsCodec) ::
      ("remoteChannelReestablish" | channelReestablishCodec)
  ).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]

  val hostedCommitsCodec = (
    ("remoteInfo" | remoteNodeInfoCodec) ::
      ("localSpec" | commitmentSpecCodec) ::
      ("lastCrossSignedState" | lengthDelimited(lastCrossSignedStateCodec)) ::
      ("nextLocalUpdates" | listOfN(uint16, updateMessageCodec)) ::
      ("nextRemoteUpdates" | listOfN(uint16, updateMessageCodec)) ::
      ("updateOpt" | optional(bool8, lengthDelimited(channelUpdateCodec))) ::
      ("postErrorOutgoingResolvedIds" | setCodec(uint64overflow)) ::
      ("localError" | optional(bool8, lengthDelimited(errorCodec))) ::
      ("remoteError" | optional(bool8, lengthDelimited(errorCodec))) ::
      ("resizeProposal" | optional(
        bool8,
        lengthDelimited(resizeChannelCodec)
      )) ::
      ("overrideProposal" | optional(
        bool8,
        lengthDelimited(stateOverrideCodec)
      )) ::
      ("extParams" | listOfN(uint16, extParamsCodec)) ::
      ("startedAt" | int64)
  ).as[HostedCommits]

  val persistentChannelDataCodec =
    discriminated[PersistentChannelData]
      .by(uint16)
      .typecase(1, DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec)
      .typecase(2, DATA_WAIT_FOR_FUNDING_LOCKED_Codec)
      .typecase(3, DATA_NORMAL_Codec)
      .typecase(4, DATA_NEGOTIATING_Codec)
      .typecase(5, DATA_CLOSING_Codec)
      .typecase(6, DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec)
      .typecase(7, hostedCommitsCodec)
}
