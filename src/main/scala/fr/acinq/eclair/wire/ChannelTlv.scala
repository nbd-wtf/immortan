package fr.acinq.eclair.wire

import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.TlvCodecs.tlvStream
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

sealed trait OpenChannelTlv extends Tlv
sealed trait AcceptChannelTlv extends Tlv

object ChannelTlv {

  /** Commitment to where the funds will go in case of a mutual close, which
    * remote node will enforce in case we're compromised.
    */
  case class UpfrontShutdownScript(script: ByteVector)
      extends OpenChannelTlv
      with AcceptChannelTlv {
    val isEmpty: Boolean = script.isEmpty
  }

}

object OpenChannelTlv {

  import ChannelTlv._

  val openTlvCodec: Codec[TlvStream[OpenChannelTlv]] = tlvStream(
    discriminated[OpenChannelTlv]
      .by(varint)
      .\(UInt64(0)) { case v: UpfrontShutdownScript => v }(
        variableSizeBytesLong(varintoverflow, bytes).as[UpfrontShutdownScript]
      )
  )

}

object AcceptChannelTlv {

  import ChannelTlv._

  val acceptTlvCodec: Codec[TlvStream[AcceptChannelTlv]] = tlvStream(
    discriminated[AcceptChannelTlv]
      .by(varint)
      .subcaseP(UInt64(0))(toUpfrontShutdownScript)(
        variableSizeBytesLong(varintoverflow, bytes).as[UpfrontShutdownScript]
      )
  )

  def toUpfrontShutdownScript
      : PartialFunction[AcceptChannelTlv, UpfrontShutdownScript] = {
    case v: UpfrontShutdownScript => v
  }
}
