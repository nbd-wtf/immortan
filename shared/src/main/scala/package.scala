import scodec.{Codec, DecodeResult}
import scodec.bits.ByteVector
import scodec.codecs._
import scoin._
import scoin.ln._
import scoin.ln.CommonCodecs._

import immortan.crypto.Tools
import immortan.trampoline._

package object immortan {
  sealed trait ChannelKind
  case object IrrelevantChannelKind extends ChannelKind
  case object HostedChannelKind extends ChannelKind
  case object NormalChannelKind extends ChannelKind

  case class FullPaymentTag(
      paymentHash: ByteVector32,
      paymentSecret: ByteVector32,
      tag: Int
  )

  case class ShortPaymentTag(paymentSecret: ByteVector32, tag: Int)

  case class EncryptedPaymentSecret(data: ByteVector) extends Tlv

  object PaymentTagTlv {
    final val LOCALLY_SENT = 0
    final val TRAMPLOINE_ROUTED = 1
    final val FINAL_INCOMING = 2

    type EncryptedSecretStream = TlvStream[EncryptedPaymentSecret]

    val shortPaymentTagCodec =
      (("paymentSecret" | bytes32) ::
        ("tag" | int32)).as[ShortPaymentTag]

    private val encryptedPaymentSecretCodec =
      variableSizeBytesLong(varintoverflow, bytes).as[EncryptedPaymentSecret]

    private val discriminator = discriminated[EncryptedPaymentSecret]
      .by(varint)
      .typecase(UInt64(4127926135L), encryptedPaymentSecretCodec)

    val codec: Codec[EncryptedSecretStream] = TlvCodecs.tlvStream(discriminator)
  }

  object TrampolineStatus {
    type NodeIdTrampolineParamsRoute = List[NodeIdTrampolineParams]
  }

  trait TrampolineStatus extends LightningMessage

  implicit class UpdateAddHtlcOps(add: UpdateAddHtlc) {
    // Important: LNParams.secret must be defined
    def fullTagOpt: Option[FullPaymentTag] = for {
      EncryptedPaymentSecret(cipherBytes) <- add.tlvStream.records
        .collectFirst { case v: EncryptedPaymentSecret => v }
      plainBytes <- Tools
        .chaChaDecrypt(
          LNParams.secret.keys.ourNodePrivateKey.value,
          cipherBytes
        )
        .toOption
      DecodeResult(shortTag, _) <- PaymentTagTlv.shortPaymentTagCodec
        .decode(plainBytes.toBitVector)
        .toOption
    } yield FullPaymentTag(
      add.paymentHash,
      shortTag.paymentSecret,
      shortTag.tag
    )

    // This is relevant for outgoing payments,
    // NO_SECRET means this is NOT an outgoing local or trampoline-routed payment
    lazy val fullTag: FullPaymentTag = fullTagOpt getOrElse FullPaymentTag(
      add.paymentHash,
      ChannelMaster.NO_SECRET,
      PaymentTagTlv.LOCALLY_SENT
    )
  }
}
