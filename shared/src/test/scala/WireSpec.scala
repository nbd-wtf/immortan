package immortan

import utest._
import scoin._
import scoin.ln._
import scoin.ln.Sphinx._
import scoin.ln.SphinxTestHelpers._
import scoin.ln.LightningMessageCodecs._

import immortan.channel.CMD_ADD_HTLC

object WireSpec extends TestSuite {
  val tests = Tests {
    test("HC wraps normal messages before sending") {
      val packet = OnionRoutingPacket(
        version = 2,
        publicKey = randomBytes(33),
        payload = randomBytes(1300),
        hmac = randomBytes32
      )
      val add = UpdateAddHtlc(
        randomBytes32,
        id = 100L,
        amountMsat = MilliSatoshi(1000000L),
        paymentHash = randomBytes32,
        cltvExpiry = CltvExpiry(288),
        onionRoutingPacket = packet
      )

      // Normal message gets wrapped because it comes from HC, then falls through unchanged when prepared
      val msg1 @ UnknownMessage(
        LightningMessageCodecs.HC_UPDATE_ADD_HTLC_TAG,
        _
      ) = LightningMessageCodecs.prepareNormal(add)
      val msg2 @ UnknownMessage(
        LightningMessageCodecs.HC_UPDATE_ADD_HTLC_TAG,
        _
      ) = LightningMessageCodecs.prepare(msg1)

      val encoded = lightningMessageCodecWithFallback.encode(msg2).require
      val decoded =
        lightningMessageCodecWithFallback.decode(encoded).require.value
      assert(decoded == msg2)
    }

    test("HC does not wrap extended messages before sending") {
      val resizeMessage = ResizeChannel(newCapacity = 10000000000L.sat)

      // Extended message falls through `prepareNormal`, but gets wrapped when prepared
      val msg1 = LightningMessageCodecs
        .prepareNormal(resizeMessage)
        .asInstanceOf[ResizeChannel]
      val msg2 @ UnknownMessage(
        LightningMessageCodecs.HC_RESIZE_CHANNEL_TAG,
        _
      ) =
        LightningMessageCodecs.prepare(msg1)

      val encoded = lightningMessageCodecWithFallback.encode(msg2).require
      val decoded =
        lightningMessageCodecWithFallback.decode(encoded).require.value
      assert(decoded == msg2)
    }

    test("NC does not wrap normal messages") {
      val packet = OnionRoutingPacket(
        version = 2,
        publicKey = randomBytes(33),
        payload = randomBytes(1300),
        hmac = randomBytes32
      )
      val add = UpdateAddHtlc(
        randomBytes32,
        id = 100L,
        amountMsat = MilliSatoshi(1000000L),
        paymentHash = randomBytes32,
        cltvExpiry = CltvExpiry(288),
        onionRoutingPacket = packet
      )

      // Normal message coming from normal channel does not get wrapped in any way
      val msg2 = LightningMessageCodecs.prepare(add).asInstanceOf[UpdateAddHtlc]

      val encoded = lightningMessageCodecWithFallback.encode(msg2).require
      val decoded =
        lightningMessageCodecWithFallback.decode(encoded).require.value
      assert(decoded == msg2)
    }

    test("UpdateAddHtlc tag encryption and partId equivalence") {
      LNParams.secret = WalletSecret.random()

      val payload = PaymentOnion.createSinglePartPayload(
        MilliSatoshi(1000000L),
        CltvExpiry(144),
        randomBytes32,
        None
      )
      val packetAndSecrets = create(
        sessionKey,
        1300,
        publicKeys,
        referenceFixedSizePaymentPayloads,
        associatedData
      ).toOption.get
      val fullTag = FullPaymentTag(
        paymentHash = ByteVector32.Zeroes,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val cmd = CMD_ADD_HTLC(
        fullTag,
        firstAmount = MilliSatoshi(1000000L),
        CltvExpiry(144),
        packetAndSecrets,
        payload
      )
      assert(cmd.incompleteAdd.partId == cmd.packetAndSecrets.packet.publicKey)
      assert(cmd.incompleteAdd.fullTag == fullTag)
    }

    test("LCSS") {
      LNParams.secret = WalletSecret.random()

      val payload = PaymentOnion.createSinglePartPayload(
        MilliSatoshi(1000000L),
        CltvExpiry(144),
        randomBytes32,
        None
      )
      val packetAndSecrets = create(
        sessionKey,
        1300,
        publicKeys,
        referenceFixedSizePaymentPayloads,
        associatedData
      ).toOption.get
      val fullTag = FullPaymentTag(
        paymentHash = ByteVector32.Zeroes,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val cmd = CMD_ADD_HTLC(
        fullTag,
        firstAmount = MilliSatoshi(1000000L),
        CltvExpiry(144),
        packetAndSecrets,
        payload
      )

      val add1 = UpdateAddHtlc(
        randomBytes32,
        id = 1000L,
        cmd.firstAmount,
        cmd.fullTag.paymentHash,
        cmd.cltvExpiry,
        cmd.packetAndSecrets.packet,
        cmd.encryptedTag
      )
      val add2 = UpdateAddHtlc(
        randomBytes32,
        id = 1000L,
        cmd.firstAmount,
        cmd.fullTag.paymentHash,
        cmd.cltvExpiry,
        cmd.packetAndSecrets.packet
      )

      val features = List(
        Features.HostedChannels.mandatory,
        Features.ResizeableHostedChannels.mandatory
      )
      val init = InitHostedChannel(
        UInt64(1000000000L),
        htlcMinimumMsat = MilliSatoshi(100),
        maxAcceptedHtlcs = 12,
        channelCapacityMsat = MilliSatoshi(10000000000L),
        MilliSatoshi(100000L),
        features
      )

      val lcss = LastCrossSignedState(
        isHost = false,
        refundScriptPubKey = randomBytes(78),
        init,
        blockDay = 12594,
        localBalanceMsat = MilliSatoshi(100000L),
        remoteBalanceMsat = MilliSatoshi(100000L),
        localUpdates = 123,
        remoteUpdates = 294,
        List(add1, add2, add1),
        List(add2, add1, add2),
        remoteSigOfLocal = ByteVector64.Zeroes,
        localSigOfRemote = ByteVector64.Zeroes
      )

      assert(
        lastCrossSignedStateCodec
          .decode(lastCrossSignedStateCodec.encode(lcss).require)
          .require
          .value == lcss
      )
    }

    test("Trampoline status") {
      val trampolineOn = TrampolineOn(
        LNParams.minPayment,
        MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 1000L,
        exponent = 0.0,
        logExponent = 0.0,
        LNParams.minRoutingCltvExpiryDelta
      )
      val params1 =
        NodeIdTrampolineParams(nodeId = randomKey.publicKey, trampolineOn)
      val params2 =
        NodeIdTrampolineParams(nodeId = randomKey.publicKey, trampolineOn)

      val update1 = TrampolineStatusInit(
        List(List(params1), List(params1, params2)),
        trampolineOn
      )
      val update2 = TrampolineStatusUpdate(
        List(List(params1), List(params1, params2)),
        Map(randomKey.publicKey -> trampolineOn),
        Some(trampolineOn),
        Set(randomKey.publicKey, randomKey.publicKey)
      )

      assert(
        trampolineStatusInitCodec
          .decode(trampolineStatusInitCodec.encode(update1).require)
          .require
          .value == update1
      )
      assert(
        trampolineStatusUpdateCodec
          .decode(trampolineStatusUpdateCodec.encode(update2).require)
          .require
          .value == update2
      )
    }

    test("HC short channel ids are random") {
      val hostNodeId = randomBytes32
      val iterations = 1000000
      val sids =
        List.fill(iterations)(
          hostedShortChannelId(randomBytes32, hostNodeId)
        )
      assert(sids.size == sids.toSet.size)
    }
  }
}
