package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import org.scalatest.funsuite.AnyFunSuite
import fr.acinq.bitcoin.SatoshiLong


class WireSpec extends AnyFunSuite {
  test("HC wraps normal messages before sending") {
    val packet = OnionRoutingPacket(version = 2, publicKey = randomBytes(33), payload = randomBytes(1300), hmac = randomBytes32)
    val add = UpdateAddHtlc(randomBytes32, id = 100L, amountMsat = 1000000L.msat, paymentHash = randomBytes32, cltvExpiry = CltvExpiry(288), onionRoutingPacket = packet)

    // Normal message gets wrapped because it comes from HC, then falls through unchanged when prepared
    val msg1 @ UnknownMessage(LightningMessageCodecs.HC_UPDATE_ADD_HTLC_TAG, _) = LightningMessageCodecs.prepareNormal(add)
    val msg2 @ UnknownMessage(LightningMessageCodecs.HC_UPDATE_ADD_HTLC_TAG, _) = LightningMessageCodecs.prepare(msg1)

    val encoded = lightningMessageCodecWithFallback.encode(msg2).require
    val decoded = lightningMessageCodecWithFallback.decode(encoded).require.value
    assert(decoded == msg2)
  }

  test("HC does not wrap extended messages before sending") {
    val resizeMessage = ResizeChannel(newCapacity = 10000000000L.sat)

    // Extended message falls through `prepareNormal`, but gets wrapped when prepared
    val msg1 = LightningMessageCodecs.prepareNormal(resizeMessage).asInstanceOf[ResizeChannel]
    val msg2 @ UnknownMessage(LightningMessageCodecs.HC_RESIZE_CHANNEL_TAG, _) = LightningMessageCodecs.prepare(msg1)

    val encoded = lightningMessageCodecWithFallback.encode(msg2).require
    val decoded = lightningMessageCodecWithFallback.decode(encoded).require.value
    assert(decoded == msg2)
  }

  test("Normal does not wrap normal messages") {
    val packet = OnionRoutingPacket(version = 2, publicKey = randomBytes(33), payload = randomBytes(1300), hmac = randomBytes32)
    val add = UpdateAddHtlc(randomBytes32, id = 100L, amountMsat = 1000000L.msat, paymentHash = randomBytes32, cltvExpiry = CltvExpiry(288), onionRoutingPacket = packet)

    // Normal message coming from normal channel does not get wrapped in any way
    val msg2 = LightningMessageCodecs.prepare(add).asInstanceOf[UpdateAddHtlc]

    val encoded = lightningMessageCodecWithFallback.encode(msg2).require
    val decoded = lightningMessageCodecWithFallback.decode(encoded).require.value
    assert(decoded == msg2)
  }

  test("Non-HC extended messages also get wrapped") {
    val swapInState = SwapInState(pending = Nil, ready = Nil, processing = Nil)
    val msg1 @ UnknownMessage(LightningMessageCodecs.SWAP_IN_STATE_MESSAGE_TAG, _) = LightningMessageCodecs.prepare(swapInState)

    val encoded = lightningMessageCodecWithFallback.encode(msg1).require
    val decoded = lightningMessageCodecWithFallback.decode(encoded).require.value
    assert(decoded == msg1)
  }
}
