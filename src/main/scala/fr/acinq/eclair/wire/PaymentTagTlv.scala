package fr.acinq.eclair.wire

import scodec.codecs._
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.{Attempt, Codec}

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.UInt64
import scodec.bits.ByteVector


case class FullPaymentTag(paymentSecret: ByteVector32, paymentHash: ByteVector32)

object PaymentTagTlv {
  case class EncryptedPaymentTag(data: ByteVector) extends Tlv

  val encryptedTagCodec: Codec[EncryptedPaymentTag] = {
    varsizebinarydata withContext "data"
  }.as[EncryptedPaymentTag]

  val fullPaymentTagCodec: Codec[FullPaymentTag] = {
    (bytes32 withContext "paymentSecret") ::
      (bytes32 withContext "paymentHash")
  }.as[FullPaymentTag]

  val codec: Codec[TlvStream.GenericTlvStream] = {
    val discriminatorCodec: DiscriminatorCodec[Tlv, UInt64] = discriminated.by(varint).typecase(UInt64(TlvStream.paymentTag), encryptedTagCodec)
    val prefixedTlvCodec: Codec[TlvStream.GenericTlvStream] = variableSizeBytesLong(value = TlvCodecs.tlvStream(discriminatorCodec), size = varintoverflow)

    fallback(provide(TlvStream.empty[Tlv]), prefixedTlvCodec).narrow(f = {
      case Left(emptyFallback) => Attempt.successful(emptyFallback)
      case Right(realStream) => Attempt.successful(realStream)
    }, g = Right.apply)
  }
}