package immortan.wire

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.LastCrossSignedState
import fr.acinq.eclair.wire.LightningMessageCodecs._
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

  val mnemonicsCodec = {
    (listOfN(uint8, text) withContext "mnemonic")
  }.as[List[String]]
}
