package immortan.wire

import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.HostedMessagesCodecs._
import fr.acinq.eclair.wire.LastCrossSignedState
import fr.acinq.bitcoin.Crypto.PublicKey
import scodec.Codec


case class HostedState(nodeId1: PublicKey, nodeId2: PublicKey, lastCrossSignedState: LastCrossSignedState)

object ExtCodecs {
  val hostedStateCodec: Codec[HostedState] = {
    (publicKey withContext "nodeId1") ::
      (publicKey withContext "nodeId2") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState")
  }.as[HostedState]
}
