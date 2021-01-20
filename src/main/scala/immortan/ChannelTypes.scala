package immortan

import fr.acinq.eclair.crypto.Sphinx.{DecryptedPacket, PacketAndSecrets}
import fr.acinq.eclair.wire.{StateOverride, UpdateAddHtlc}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi}
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.wire.Onion.FinalPayload
import scodec.bits.ByteVector


sealed trait Command
sealed trait AddResolution { val add: UpdateAddHtlc }
sealed trait BadAddResolution extends Command with AddResolution

case class CMD_FAIL_HTLC(reason: ByteVector, add: UpdateAddHtlc) extends BadAddResolution
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