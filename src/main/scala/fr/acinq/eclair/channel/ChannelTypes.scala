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

import fr.acinq.eclair.wire._
import scodec.bits.{BitVector, ByteVector}
import fr.acinq.eclair.crypto.Sphinx.{DecryptedPacket, PacketAndSecrets}
import fr.acinq.bitcoin.{ByteVector32, DeterministicWallet, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, ShortChannelId, UInt64}
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, CommitTx, CommitmentFormat, DefaultCommitmentFormat}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.wire.Onion.FinalPayload
import fr.acinq.bitcoin.Crypto.PublicKey

/*
      8888888888 888     888 8888888888 888b    888 88888888888 .d8888b.
      888        888     888 888        8888b   888     888    d88P  Y88b
      888        888     888 888        88888b  888     888    Y88b.
      8888888    Y88b   d88P 8888888    888Y88b 888     888     "Y888b.
      888         Y88b d88P  888        888 Y88b888     888        "Y88b.
      888          Y88o88P   888        888  Y88888     888          "888
      888           Y888P    888        888   Y8888     888    Y88b  d88P
      8888888888     Y8P     8888888888 888    Y888     888     "Y8888P"
 */

case class INPUT_INIT_FUNDER(temporaryChannelId: ByteVector32,
                             fundingAmount: Satoshi,
                             pushAmount: MilliSatoshi,
                             initialFeeratePerKw: FeeratePerKw,
                             fundingTxFeeratePerKw: FeeratePerKw,
                             initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                             localParams: LocalParams,
                             remoteInit: Init,
                             channelFlags: Byte,
                             channelVersion: ChannelVersion)

case class INPUT_INIT_FUNDEE(temporaryChannelId: ByteVector32,
                             localParams: LocalParams,
                             remoteInit: Init,
                             channelVersion: ChannelVersion)

sealed trait BitcoinEvent
case object BITCOIN_FUNDING_PUBLISH_FAILED extends BitcoinEvent
case object BITCOIN_FUNDING_DEPTHOK extends BitcoinEvent
case object BITCOIN_FUNDING_DEEPLYBURIED extends BitcoinEvent
case object BITCOIN_FUNDING_LOST extends BitcoinEvent
case object BITCOIN_FUNDING_TIMEOUT extends BitcoinEvent
case object BITCOIN_FUNDING_SPENT extends BitcoinEvent
case object BITCOIN_OUTPUT_SPENT extends BitcoinEvent
case class BITCOIN_TX_CONFIRMED(tx: Transaction) extends BitcoinEvent
case class BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId: ShortChannelId) extends BitcoinEvent
case class BITCOIN_PARENT_TX_CONFIRMED(childTx: Transaction) extends BitcoinEvent

/*
       .d8888b.   .d88888b.  888b     d888 888b     d888        d8888 888b    888 8888888b.   .d8888b.
      d88P  Y88b d88P" "Y88b 8888b   d8888 8888b   d8888       d88888 8888b   888 888  "Y88b d88P  Y88b
      888    888 888     888 88888b.d88888 88888b.d88888      d88P888 88888b  888 888    888 Y88b.
      888        888     888 888Y88888P888 888Y88888P888     d88P 888 888Y88b 888 888    888  "Y888b.
      888        888     888 888 Y888P 888 888 Y888P 888    d88P  888 888 Y88b888 888    888     "Y88b.
      888    888 888     888 888  Y8P  888 888  Y8P  888   d88P   888 888  Y88888 888    888       "888
      Y88b  d88P Y88b. .d88P 888   "   888 888   "   888  d8888888888 888   Y8888 888  .d88P Y88b  d88P
       "Y8888P"   "Y88888P"  888       888 888       888 d88P     888 888    Y888 8888888P"   "Y8888P"
 */

sealed trait Command
sealed trait AddResolution { val add: UpdateAddHtlc }
sealed trait BadAddResolution extends Command with AddResolution

case class CMD_FAIL_HTLC(reason: Either[ByteVector, FailureMessage], add: UpdateAddHtlc) extends BadAddResolution
case class CMD_FAIL_MALFORMED_HTLC(onionHash: ByteVector32, failureCode: Int, add: UpdateAddHtlc) extends BadAddResolution
case class FinalPayloadSpec(packet: DecryptedPacket, payload: FinalPayload, add: UpdateAddHtlc) extends AddResolution
case class CMD_FULFILL_HTLC(preimage: ByteVector32, add: UpdateAddHtlc) extends Command with AddResolution

case class CMD_ADD_HTLC(internalId: ByteVector, firstAmount: MilliSatoshi, paymentHash: ByteVector32,
                        cltvExpiry: CltvExpiry, packetAndSecrets: PacketAndSecrets, payload: FinalPayload) extends Command

case class CMD_HOSTED_STATE_OVERRIDE(so: StateOverride) extends Command
case class HC_CMD_RESIZE(delta: Satoshi) extends Command

case object CMD_INCOMING_TIMEOUT extends Command
case object CMD_CHAIN_TIP_KNOWN extends Command
case object CMD_CHAIN_TIP_LOST extends Command
case object CMD_SOCKET_OFFLINE extends Command
case object CMD_SOCKET_ONLINE extends Command
case object CMD_SIGN extends Command

/*
      8888888b.        d8888 88888888888     d8888
      888  "Y88b      d88888     888        d88888
      888    888     d88P888     888       d88P888
      888    888    d88P 888     888      d88P 888
      888    888   d88P  888     888     d88P  888
      888    888  d88P   888     888    d88P   888
      888  .d88P d8888888888     888   d8888888888
      8888888P" d88P     888     888  d88P     888
 */

sealed trait Data {
  def channelId: ByteVector32
}

case object Nothing extends Data {
  val channelId: ByteVector32 = ByteVector32.Zeroes
}

sealed trait HasCommitments extends Data {
  val channelId: ByteVector32 = commitments.channelId
  def commitments: Commitments
}

case class ClosingTxProposed(unsignedTx: Transaction, localClosingSigned: ClosingSigned)

case class LocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[Transaction], htlcSuccessTxs: List[Transaction], htlcTimeoutTxs: List[Transaction], claimHtlcDelayedTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
  def isConfirmed: Boolean = {
    // NB: if multiple transactions end up in the same block, the first confirmation we receive may not be the commit tx.
    // However if the confirmed tx spends from the commit tx, we know that the commit tx is already confirmed and we know
    // the type of closing.
    val confirmedTxs = irrevocablySpent.values.toSet
    (commitTx :: claimMainDelayedOutputTx.toList ::: htlcSuccessTxs ::: htlcTimeoutTxs ::: claimHtlcDelayedTxs).exists(tx => confirmedTxs.contains(tx.txid))
  }
}
case class RemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], claimHtlcSuccessTxs: List[Transaction], claimHtlcTimeoutTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
  def isConfirmed: Boolean = {
    // NB: if multiple transactions end up in the same block, the first confirmation we receive may not be the commit tx.
    // However if the confirmed tx spends from the commit tx, we know that the commit tx is already confirmed and we know
    // the type of closing.
    val confirmedTxs = irrevocablySpent.values.toSet
    (commitTx :: claimMainOutputTx.toList ::: claimHtlcSuccessTxs ::: claimHtlcTimeoutTxs).exists(tx => confirmedTxs.contains(tx.txid))
  }
}
case class RevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], mainPenaltyTx: Option[Transaction], htlcPenaltyTxs: List[Transaction], claimHtlcDelayedPenaltyTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32])

final case class DATA_WAIT_FOR_OPEN_CHANNEL(initFundee: INPUT_INIT_FUNDEE) extends Data {
  val channelId: ByteVector32 = initFundee.temporaryChannelId
}
final case class DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder: INPUT_INIT_FUNDER, lastSent: OpenChannel) extends Data {
  val channelId: ByteVector32 = initFunder.temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId: ByteVector32,
                                                localParams: LocalParams,
                                                remoteParams: RemoteParams,
                                                fundingAmount: Satoshi,
                                                pushAmount: MilliSatoshi,
                                                initialFeeratePerKw: FeeratePerKw,
                                                initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                                                remoteFirstPerCommitmentPoint: PublicKey,
                                                channelVersion: ChannelVersion,
                                                lastSent: OpenChannel) extends Data {
  val channelId: ByteVector32 = temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId: ByteVector32,
                                               localParams: LocalParams,
                                               remoteParams: RemoteParams,
                                               fundingAmount: Satoshi,
                                               pushAmount: MilliSatoshi,
                                               initialFeeratePerKw: FeeratePerKw,
                                               initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                                               remoteFirstPerCommitmentPoint: PublicKey,
                                               channelFlags: Byte,
                                               channelVersion: ChannelVersion,
                                               lastSent: AcceptChannel) extends Data {
  val channelId: ByteVector32 = temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_SIGNED(channelId: ByteVector32,
                                              localParams: LocalParams,
                                              remoteParams: RemoteParams,
                                              fundingTx: Transaction,
                                              fundingTxFee: Satoshi,
                                              initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                                              localSpec: CommitmentSpec,
                                              localCommitTx: CommitTx,
                                              remoteCommit: RemoteCommit,
                                              channelFlags: Byte,
                                              channelVersion: ChannelVersion,
                                              lastSent: FundingCreated) extends Data
final case class DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments: Commitments,
                                                 fundingTx: Option[Transaction],
                                                 initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                                                 waitingSince: Long, // how long have we been waiting for the funding tx to confirm
                                                 deferred: Option[FundingLocked],
                                                 lastSent: Either[FundingCreated, FundingSigned]) extends Data with HasCommitments
final case class DATA_WAIT_FOR_FUNDING_LOCKED(commitments: Commitments, shortChannelId: ShortChannelId, lastSent: FundingLocked, initialRelayFees_opt: Option[(MilliSatoshi, Int)]) extends Data with HasCommitments
final case class DATA_NORMAL(commitments: Commitments,
                             shortChannelId: ShortChannelId,
                             buried: Boolean,
                             channelAnnouncement: Option[ChannelAnnouncement],
                             channelUpdate: ChannelUpdate,
                             localShutdown: Option[Shutdown],
                             remoteShutdown: Option[Shutdown]) extends Data with HasCommitments
final case class DATA_SHUTDOWN(commitments: Commitments,
                               localShutdown: Shutdown, remoteShutdown: Shutdown) extends Data with HasCommitments
final case class DATA_NEGOTIATING(commitments: Commitments,
                                  localShutdown: Shutdown, remoteShutdown: Shutdown,
                                  closingTxProposed: List[List[ClosingTxProposed]], // one list for every negotiation (there can be several in case of disconnection)
                                  bestUnpublishedClosingTx_opt: Option[Transaction]) extends Data with HasCommitments {
  require(closingTxProposed.nonEmpty, "there must always be a list for the current negotiation")
  require(!commitments.localParams.isFunder || closingTxProposed.forall(_.nonEmpty), "funder must have at least one closing signature for every negotation attempt because it initiates the closing")
}
final case class DATA_CLOSING(commitments: Commitments,
                              fundingTx: Option[Transaction], // this will be non-empty if we are funder and we got in closing while waiting for our own tx to be published
                              waitingSince: Long, // how long since we initiated the closing
                              mutualCloseProposed: List[Transaction], // all exchanged closing sigs are flattened, we use this only to keep track of what publishable tx they have
                              mutualClosePublished: List[Transaction] = Nil,
                              localCommitPublished: Option[LocalCommitPublished] = None,
                              remoteCommitPublished: Option[RemoteCommitPublished] = None,
                              nextRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              futureRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              revokedCommitPublished: List[RevokedCommitPublished] = Nil) extends Data with HasCommitments {
  val spendingTxes: Seq[Transaction] = mutualClosePublished ::: localCommitPublished.map(_.commitTx).toList ::: remoteCommitPublished.map(_.commitTx).toList ::: nextRemoteCommitPublished.map(_.commitTx).toList ::: futureRemoteCommitPublished.map(_.commitTx).toList ::: revokedCommitPublished.map(_.commitTx)
  require(spendingTxes.nonEmpty, "there must be at least one tx published in this state")
}

final case class DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(commitments: Commitments, remoteChannelReestablish: ChannelReestablish) extends Data with HasCommitments


/**
 * @param features current connection features, or last features used if the channel is disconnected. Note that these
 *                 features are updated at each reconnection and may be different from the ones that were used when the
 *                 channel was created. See [[ChannelVersion]] for permanent features associated to a channel.
 */
final case class LocalParams(nodeId: PublicKey,
                             fundingKeyPath: DeterministicWallet.KeyPath,
                             dustLimit: Satoshi,
                             maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                             channelReserve: Satoshi,
                             htlcMinimum: MilliSatoshi,
                             toSelfDelay: CltvExpiryDelta,
                             maxAcceptedHtlcs: Int,
                             isFunder: Boolean,
                             defaultFinalScriptPubKey: ByteVector,
                             walletStaticPaymentBasepoint: Option[PublicKey],
                             features: Features)

/**
 * @param features see [[LocalParams.features]]
 */
final case class RemoteParams(nodeId: PublicKey,
                              dustLimit: Satoshi,
                              maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                              channelReserve: Satoshi,
                              htlcMinimum: MilliSatoshi,
                              toSelfDelay: CltvExpiryDelta,
                              maxAcceptedHtlcs: Int,
                              fundingPubKey: PublicKey,
                              revocationBasepoint: PublicKey,
                              paymentBasepoint: PublicKey,
                              delayedPaymentBasepoint: PublicKey,
                              htlcBasepoint: PublicKey,
                              features: Features)

object ChannelFlags {
  val AnnounceChannel = 0x01.toByte
  val Empty = 0x00.toByte
}

case class ChannelVersion(bits: BitVector) {
  import ChannelVersion._

  require(bits.size == ChannelVersion.LENGTH_BITS, "channel version takes 4 bytes")

  val commitmentFormat: CommitmentFormat = if (hasAnchorOutputs) {
    AnchorOutputsCommitmentFormat
  } else {
    DefaultCommitmentFormat
  }

  def |(other: ChannelVersion) = ChannelVersion(bits | other.bits)
  def &(other: ChannelVersion) = ChannelVersion(bits & other.bits)
  def ^(other: ChannelVersion) = ChannelVersion(bits ^ other.bits)

  def isSet(bit: Int): Boolean = bits.reverse.get(bit)

  def hasPubkeyKeyPath: Boolean = isSet(USE_PUBKEY_KEYPATH_BIT)
  def hasStaticRemotekey: Boolean = isSet(USE_STATIC_REMOTEKEY_BIT)
  def hasAnchorOutputs: Boolean = isSet(USE_ANCHOR_OUTPUTS_BIT)
  /** True if our main output in the remote commitment is directly sent (without any delay) to one of our wallet addresses. */
  def paysDirectlyToWallet: Boolean = hasStaticRemotekey && !hasAnchorOutputs
}

object ChannelVersion {
  import scodec.bits._

  val LENGTH_BITS: Int = 4 * 8

  private val USE_PUBKEY_KEYPATH_BIT = 0 // bit numbers start at 0
  private val USE_STATIC_REMOTEKEY_BIT = 1
  private val USE_ANCHOR_OUTPUTS_BIT = 2

  def fromBit(bit: Int): ChannelVersion = ChannelVersion(BitVector.low(LENGTH_BITS).set(bit).reverse)

  def pickChannelVersion(localFeatures: Features, remoteFeatures: Features): ChannelVersion = {
    if (Features.canUseFeature(localFeatures, remoteFeatures, Features.AnchorOutputs)) {
      ANCHOR_OUTPUTS
    } else if (Features.canUseFeature(localFeatures, remoteFeatures, Features.StaticRemoteKey)) {
      STATIC_REMOTEKEY
    } else {
      STANDARD
    }
  }

  val ZEROES = ChannelVersion(bin"00000000000000000000000000000000")
  val STANDARD = ZEROES | fromBit(USE_PUBKEY_KEYPATH_BIT)
  val STATIC_REMOTEKEY = STANDARD | fromBit(USE_STATIC_REMOTEKEY_BIT) // PUBKEY_KEYPATH + STATIC_REMOTEKEY
  val ANCHOR_OUTPUTS = STATIC_REMOTEKEY | fromBit(USE_ANCHOR_OUTPUTS_BIT) // PUBKEY_KEYPATH + STATIC_REMOTEKEY + ANCHOR_OUTPUTS
}

object HostedChannelVersion {
  val USE_RESIZE = 1

  val RESIZABLE: ChannelVersion = ChannelVersion.STANDARD | ChannelVersion.fromBit(USE_RESIZE)
}
// @formatter:on
