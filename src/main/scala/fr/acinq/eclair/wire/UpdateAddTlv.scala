package fr.acinq.eclair.wire

import scodec.codecs._
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.{Attempt, Codec}
import fr.acinq.eclair.UInt64
import scodec.bits.ByteVector


sealed trait UpdateAddTlv extends Tlv

object UpdateAddTlv {
  case class InternalId(data: ByteVector) extends UpdateAddTlv

  val codec: Codec[TlvStream[Tlv]] = {
    val secretCodec: Codec[InternalId] = Codec(varsizebinarydata withContext "data").as[InternalId]

    val discriminatorCodec: DiscriminatorCodec[Tlv, UInt64] = discriminated.by(varint).typecase(UInt64(1), secretCodec)

    val prefixedTlvCodec = variableSizeBytesLong(value = TlvCodecs.tlvStream(discriminatorCodec), size = varintoverflow)

    fallback(provide(TlvStream.empty[Tlv]), prefixedTlvCodec).narrow(f = {
      case Left(emptyFallback) => Attempt.successful(emptyFallback)
      case Right(realStream) => Attempt.successful(realStream)
    }, g = Right.apply)
  }
}