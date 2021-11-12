package immortan

import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.channel.CMD_ADD_HTLC
import fr.acinq.eclair.crypto.Sphinx.PaymentPacket
import fr.acinq.eclair.crypto.SphinxSpec._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools
import org.scalatest.funsuite.AnyFunSuite


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

  test("NC does not wrap normal messages") {
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

    val encoded1 = lightningMessageCodecWithFallback.encode(msg1).require
    val decoded1 = lightningMessageCodecWithFallback.decode(encoded1).require.value
    assert(decoded1 == msg1)

    val queryPreimages = QueryPreimages(randomBytes32 :: randomBytes32 :: randomBytes32 :: Nil)
    val msg2 @ UnknownMessage(LightningMessageCodecs.HC_QUERY_PREIMAGES_TAG, _) = LightningMessageCodecs.prepare(queryPreimages)

    val encoded2 = lightningMessageCodecWithFallback.encode(msg2).require
    val decoded2 = lightningMessageCodecWithFallback.decode(encoded2).require.value
    assert(decoded2 == msg2)
  }

  test("UpdateAddHtlc tag encryption and partId equivalence") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)

    val payload = Onion.createSinglePartPayload(1000000L.msat, CltvExpiry(144), randomBytes32)
    val packetAndSecrets = PaymentPacket.create(sessionKey, publicKeys, variableSizePayloadsFull, associatedData)
    val fullTag = FullPaymentTag(paymentHash = ByteVector32.Zeroes, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val cmd = CMD_ADD_HTLC(fullTag, firstAmount = 1000000L.msat, CltvExpiry(144), packetAndSecrets, payload)
    assert(cmd.incompleteAdd.partId == cmd.packetAndSecrets.packet.publicKey)
    assert(cmd.incompleteAdd.fullTag == fullTag)
  }

  test("LCSS") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)

    val payload = Onion.createSinglePartPayload(1000000L.msat, CltvExpiry(144), randomBytes32)
    val packetAndSecrets = PaymentPacket.create(sessionKey, publicKeys, variableSizePayloadsFull, associatedData)
    val fullTag = FullPaymentTag(paymentHash = ByteVector32.Zeroes, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val cmd = CMD_ADD_HTLC(fullTag, firstAmount = 1000000L.msat, CltvExpiry(144), packetAndSecrets, payload)

    val add1 = UpdateAddHtlc(randomBytes32, id = 1000L, cmd.firstAmount, cmd.fullTag.paymentHash, cmd.cltvExpiry, cmd.packetAndSecrets.packet, cmd.encryptedTag)
    val add2 = UpdateAddHtlc(randomBytes32, id = 1000L, cmd.firstAmount, cmd.fullTag.paymentHash, cmd.cltvExpiry, cmd.packetAndSecrets.packet)

    val features = List(Features.HostedChannels.mandatory, Features.ResizeableHostedChannels.mandatory)
    val init = InitHostedChannel(UInt64(1000000000L), htlcMinimumMsat = 100.msat, maxAcceptedHtlcs = 12, channelCapacityMsat = 10000000000L.msat, 100000L.msat, features)

    val lcss = LastCrossSignedState(isHost = false, refundScriptPubKey = randomBytes(78), init, blockDay = 12594, localBalanceMsat = 100000L.msat,
      remoteBalanceMsat = 100000L.msat, localUpdates = 123, remoteUpdates = 294, List(add1, add2, add1), List(add2, add1, add2),
      remoteSigOfLocal = ByteVector64.Zeroes, localSigOfRemote = ByteVector64.Zeroes)

    assert(lastCrossSignedStateCodec.decode(lastCrossSignedStateCodec.encode(lcss).require).require.value == lcss)
  }

  test("HC short channel ids are random") {
    val hostNodeId = randomBytes32
    val iterations = 1000000
    val sids = List.fill(iterations)(Tools.hostedShortChanId(randomBytes32, hostNodeId))
    assert(sids.size == sids.toSet.size)
  }
}
