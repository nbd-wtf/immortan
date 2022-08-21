package immortan.wire

import scoin.Crypto.PublicKey
import scoin.ln.ChannelCodecs._
import scoin.ln.CommonCodecs._
import scoin.ln.LastCrossSignedState
import scoin.ln.LightningMessageCodecs._
import immortan._
import scodec.codecs._

case class HostedState(
    nodeId1: PublicKey,
    nodeId2: PublicKey,
    lastCrossSignedState: LastCrossSignedState
)

object ExtCodecs {
  val compressedByteVecCodec = {
    val plain = variableSizeBytes(uint24, bytes)
    zlib(plain)
  }

  val hostedStateCodec = {
    (publicKey withContext "nodeId1") ::
      (publicKey withContext "nodeId2") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState")
  }.as[HostedState]
}
