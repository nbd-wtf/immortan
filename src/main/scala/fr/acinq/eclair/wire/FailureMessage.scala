package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.crypto.Mac32
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.FailureMessageCodecs.failureMessageCodec
import fr.acinq.eclair.wire.LightningMessageCodecs.{
  channelUpdateCodec,
  lightningMessageCodec
}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, MilliSatoshiLong, UInt64}
import scodec.codecs._
import scodec.{Attempt, Codec}


// @formatter:off
sealed trait FailureMessage {
  def message: String
  // We actually encode the failure message, which is a bit clunky and not particularly efficient.
  // It would be nice to be able to get that value from the discriminated codec directly.
  lazy val code: Int = failureMessageCodec.encode(this).flatMap(uint16.decode).require.value
}
sealed trait BadOnion extends FailureMessage { def onionHash: ByteVector32 }
sealed trait Perm extends FailureMessage
sealed trait Node extends FailureMessage
sealed trait Update extends FailureMessage { def update: ChannelUpdate }

case object InvalidRealm extends Perm { def message = "InvalidRealm" }
case object TemporaryNodeFailure extends Node { def message = "TemporaryNodeFailure" }
case object PermanentNodeFailure extends Perm with Node { def message = "PermanentNodeFailure" }
case object RequiredNodeFeatureMissing extends Perm with Node { def message = "RequiredNodeFeatureMissing" }
case class InvalidOnionVersion(onionHash: ByteVector32) extends BadOnion with Perm { def message = "InvalidOnionVersion" }
case class InvalidOnionHmac(onionHash: ByteVector32) extends BadOnion with Perm { def message = "InvalidOnionHmac" }
case class InvalidOnionKey(onionHash: ByteVector32) extends BadOnion with Perm { def message = "InvalidOnionKey" }
case class TemporaryChannelFailure(update: ChannelUpdate) extends Update { def message = "TemporaryChannelFailure" }
case object PermanentChannelFailure extends Perm { def message = "PermanentChannelFailure" }
case object RequiredChannelFeatureMissing extends Perm { def message = "RequiredChannelFeatureMissing" }
case object UnknownNextPeer extends Perm { def message = "UnknownNextPeer" }
case class AmountBelowMinimum(amount: MilliSatoshi, update: ChannelUpdate) extends Update { def message = "AmountBelowMinimum" }
case class FeeInsufficient(amount: MilliSatoshi, update: ChannelUpdate) extends Update { def message = "FeeInsufficient" }
case object TrampolineFeeInsufficient extends Node { def message = "TrampolineFeeInsufficient" }
case class ChannelDisabled(messageFlags: Byte, channelFlags: Byte, update: ChannelUpdate) extends Update { def message = "ChannelDisabled" }
case class IncorrectCltvExpiry(expiry: CltvExpiry, update: ChannelUpdate) extends Update { def message = "IncorrectCltvExpiry" }
case class IncorrectOrUnknownPaymentDetails(amount: MilliSatoshi, height: Long) extends Perm { def message = "IncorrectOrUnknownPaymentDetails" }
case class ExpiryTooSoon(update: ChannelUpdate) extends Update { def message = "ExpiryTooSoon" }
case object TrampolineExpiryTooSoon extends Node { def message = "TrampolineExpiryTooSoon" }
case class FinalIncorrectCltvExpiry(expiry: CltvExpiry) extends FailureMessage { def message = "FinalIncorrectCltvExpiry" }
case class FinalIncorrectHtlcAmount(amount: MilliSatoshi) extends FailureMessage { def message = "FinalIncorrectHtlcAmount" }
case object ExpiryTooFar extends FailureMessage { def message = "ExpiryTooFar" }
case class InvalidOnionPayload(tag: UInt64, offset: Int) extends Perm { def message = "InvalidOnionPayload" }
case object PaymentTimeout extends FailureMessage { def message = "PaymentTimeout" }

/**
 * We allow remote nodes to send us unknown failure codes (e.g. deprecated failure codes).
 * By reading the PERM and NODE bits we can still extract useful information for payment retry even without knowing how
 * to decode the failure payload (but we can't extract a channel update or onion hash).
 */
sealed trait UnknownFailureMessage extends FailureMessage {
  def message = "unknown failure message"
  override def toString = s"$message (${code.toHexString})"
  override def equals(obj: Any): Boolean = obj match {
    case f: UnknownFailureMessage => f.code == code
    case _ => false
  }
}
// @formatter:on

object FailureMessageCodecs {
  val BADONION = 0x8000
  val PERM = 0x4000
  val NODE = 0x2000
  val UPDATE = 0x1000

  val channelUpdateCodecWithType = lightningMessageCodec.narrow[ChannelUpdate](
    f => Attempt.successful(f.asInstanceOf[ChannelUpdate]),
    g => g
  )

  // NB: for historical reasons some implementations were including/omitting the message type (258 for ChannelUpdate)
  // this codec supports both versions for decoding, and will encode with the message type
  val channelUpdateWithLengthCodec = variableSizeBytes(
    uint16,
    choice(channelUpdateCodecWithType, channelUpdateCodec)
  )

  val failureMessageCodec = discriminatorWithDefault(
    discriminated[FailureMessage]
      .by(uint16)
      .\(PERM | 1) { case v if v == InvalidRealm => v }(provide(InvalidRealm))
      .\(NODE | 2) { case v if v == TemporaryNodeFailure => v }(
        provide(TemporaryNodeFailure)
      )
      .\(PERM | NODE | 2) { case v if v == PermanentNodeFailure => v }(
        provide(PermanentNodeFailure)
      )
      .\(PERM | NODE | 3) { case v if v == RequiredNodeFeatureMissing => v }(
        provide(RequiredNodeFeatureMissing)
      )
      .\(BADONION | PERM | 4)(_.asInstanceOf[InvalidOnionVersion])(
        sha256.as[InvalidOnionVersion]
      )
      .\(BADONION | PERM | 5)(_.asInstanceOf[InvalidOnionHmac])(
        sha256.as[InvalidOnionHmac]
      )
      .\(BADONION | PERM | 6)(_.asInstanceOf[InvalidOnionKey])(
        sha256.as[InvalidOnionKey]
      )
      .\(UPDATE | 7)(_.asInstanceOf[TemporaryChannelFailure])(
        ("channelUpdate" | channelUpdateWithLengthCodec)
          .as[TemporaryChannelFailure]
      )
      .\(PERM | 8) { case v if v == PermanentChannelFailure => v }(
        provide(PermanentChannelFailure)
      )
      .\(PERM | 9) { case v if v == RequiredChannelFeatureMissing => v }(
        provide(RequiredChannelFeatureMissing)
      )
      .\(PERM | 10) { case v if v == UnknownNextPeer => v }(
        provide(UnknownNextPeer)
      )
      .\(UPDATE | 11)(_.asInstanceOf[AmountBelowMinimum])(
        (("amountMsat" | millisatoshi) :: ("channelUpdate" | channelUpdateWithLengthCodec))
          .as[AmountBelowMinimum]
      )
      .\(UPDATE | 12)(_.asInstanceOf[FeeInsufficient])(
        (("amountMsat" | millisatoshi) :: ("channelUpdate" | channelUpdateWithLengthCodec))
          .as[FeeInsufficient]
      )
      .\(UPDATE | 13)(_.asInstanceOf[IncorrectCltvExpiry])(
        (("expiry" | cltvExpiry) :: ("channelUpdate" | channelUpdateWithLengthCodec))
          .as[IncorrectCltvExpiry]
      )
      .\(UPDATE | 14)(_.asInstanceOf[ExpiryTooSoon])(
        ("channelUpdate" | channelUpdateWithLengthCodec).as[ExpiryTooSoon]
      )
      .\(UPDATE | 20)(_.asInstanceOf[ChannelDisabled])(
        (("messageFlags" | byte) :: ("channelFlags" | byte) :: ("channelUpdate" | channelUpdateWithLengthCodec))
          .as[ChannelDisabled]
      )
      .\(PERM | 15)(_.asInstanceOf[IncorrectOrUnknownPaymentDetails])(
        (("amountMsat" | withDefaultValue(
          optional(bitsRemaining, millisatoshi),
          MilliSatoshi(0L)
        )) ::
          ("height" | withDefaultValue(optional(bitsRemaining, uint32), 0L)))
          .as[IncorrectOrUnknownPaymentDetails]
      )
      // PERM | 16 (incorrect_payment_amount) has been deprecated because it allowed probing attacks: IncorrectOrUnknownPaymentDetails should be used instead.
      // PERM | 17 (final_expiry_too_soon) has been deprecated because it allowed probing attacks: IncorrectOrUnknownPaymentDetails should be used instead.
      .\(18)(_.asInstanceOf[FinalIncorrectCltvExpiry])(
        ("expiry" | cltvExpiry).as[FinalIncorrectCltvExpiry]
      )
      .\(19)(_.asInstanceOf[FinalIncorrectHtlcAmount])(
        ("amountMsat" | millisatoshi).as[FinalIncorrectHtlcAmount]
      )
      .\(21) { case v if v == ExpiryTooFar => v }(provide(ExpiryTooFar))
      .\(PERM | 22)(_.asInstanceOf[InvalidOnionPayload])(
        (("tag" | varint) :: ("offset" | uint16)).as[InvalidOnionPayload]
      )
      .\(23) { case v if v == PaymentTimeout => v }(provide(PaymentTimeout))
      // TODO: @t-bast: once fully spec-ed, these should probably include a NodeUpdate and use a different ID.
      // We should update Phoenix and our nodes at the same time, or first update Phoenix to understand both new and old errors.
      .\(NODE | 51) { case v if v == TrampolineFeeInsufficient => v }(
        provide(TrampolineFeeInsufficient)
      )
      .\(NODE | 52) { case v if v == TrampolineExpiryTooSoon => v }(
        provide(TrampolineExpiryTooSoon)
      ),
    uint16.xmap(
      code => {
        val failureMessage = code match {
        // @formatter:off
        case fc if (fc & PERM) != 0 && (fc & NODE) != 0 => new UnknownFailureMessage with Perm with Node { override lazy val code = fc }
        case fc if (fc & NODE) != 0 => new UnknownFailureMessage with Node { override lazy val code = fc }
        case fc if (fc & PERM) != 0 => new UnknownFailureMessage with Perm { override lazy val code = fc }
        case fc => new UnknownFailureMessage { override lazy val code  = fc }
        // @formatter:on
        }
        failureMessage.asInstanceOf[FailureMessage]
      },
      (_: FailureMessage).code
    )
  )

  /** An onion-encrypted failure from an intermediate node:
    * \+----------------+----------------------------------+-----------------+----------------------+-----+
    * \| HMAC(32 bytes) | failure message length (2 bytes) | failure message |
    * pad length (2 bytes) | pad |
    * \+----------------+----------------------------------+-----------------+----------------------+-----+
    * with failure message length + pad length = 256
    */
  def failureOnionCodec(mac: Mac32): Codec[FailureMessage] =
    CommonCodecs.prependmac(
      paddedFixedSizeBytesDependent(
        260,
        "failureMessage" | variableSizeBytes(
          uint16,
          FailureMessageCodecs.failureMessageCodec
        ),
        nBits =>
          "padding" | variableSizeBytes(
            uint16,
            ignore(nBits - 2 * 8)
          ) // two bytes are used to encode the padding length
      ).as[FailureMessage],
      mac
    )
}
