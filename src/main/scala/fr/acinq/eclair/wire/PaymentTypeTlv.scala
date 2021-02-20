package fr.acinq.eclair.wire

import scodec.codecs._
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.{Attempt, Codec}

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.UInt64
import scodec.bits.ByteVector


case class PaymentType(paymentHash: ByteVector32, tag: Long)

sealed trait PaymentTypeTlv extends Tlv

object PaymentTypeTlv {
  final val UNDEFINED = 0L
  final val CHANNEL_ROUTED = 4L
  final val NODE_ROUTED = 3L
  final val JIT_ROUTED = 2L
  final val LOCAL = 1L

  case class EncryptedType(data: ByteVector) extends PaymentTypeTlv

  val codec: Codec[TlvStream.GenericTlvStream] = {
    val encryptedTypeCodec: Codec[EncryptedType] = Codec(varsizebinarydata withContext "data").as[EncryptedType]
    val discriminatorCodec: DiscriminatorCodec[Tlv, UInt64] = discriminated.by(varint).typecase(UInt64(TlvStream.paymentTypeTag), encryptedTypeCodec)
    val prefixedTlvCodec: Codec[TlvStream.GenericTlvStream] = variableSizeBytesLong(value = TlvCodecs.tlvStream(discriminatorCodec), size = varintoverflow)

    fallback(provide(TlvStream.empty[Tlv]), prefixedTlvCodec).narrow(f = {
      case Left(emptyFallback) => Attempt.successful(emptyFallback)
      case Right(realStream) => Attempt.successful(realStream)
    }, g = Right.apply)
  }
}