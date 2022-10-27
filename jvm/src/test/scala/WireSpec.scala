package immortan

import utest._
import scoin._
import scoin.Crypto.randomBytes
import scoin.ln._
import scoin.ln.Sphinx
import scoin.ln.LightningMessageCodecs._
import scoin.hc._
import scoin.hc.HostedChannelCodecs._

import immortan.channel._
import immortan.router._
import immortan.SphinxTestHelpers._

object WireSpec extends TestSuite {
  val tests = Tests {
    test("UpdateAddHtlc tag encryption and partId equivalence") {
      LNParams.secret = WalletSecret.random()

      val payload = PaymentOnion.createSinglePartPayload(
        MilliSatoshi(1000000L),
        CltvExpiry(144),
        randomBytes32(),
        None
      )
      val packetAndSecrets = Sphinx
        .create(
          sessionKey,
          1300,
          publicKeys,
          referencePaymentPayloads,
          associatedData
        )
        .get
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
        randomBytes32(),
        None
      )
      val packetAndSecrets = Sphinx
        .create(
          sessionKey,
          1300,
          publicKeys,
          referencePaymentPayloads,
          associatedData
        )
        .get
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
        randomBytes32(),
        id = 1000L,
        cmd.firstAmount,
        cmd.fullTag.paymentHash,
        cmd.cltvExpiry,
        cmd.packetAndSecrets.packet,
        TlvStream(records = List.empty, unknown = List(cmd.encryptedTag))
      )
      val add2 = UpdateAddHtlc(
        randomBytes32(),
        id = 1000L,
        cmd.firstAmount,
        cmd.fullTag.paymentHash,
        cmd.cltvExpiry,
        cmd.packetAndSecrets.packet
      )

      val init = InitHostedChannel(
        UInt64(1000000000L),
        htlcMinimumMsat = MilliSatoshi(100),
        maxAcceptedHtlcs = 12,
        channelCapacityMsat = MilliSatoshi(10000000000L),
        MilliSatoshi(100000L),
        List(
          HostedChannels.mandatory
        )
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
        NodeIdTrampolineParams(nodeId = randomKey().publicKey, trampolineOn)
      val params2 =
        NodeIdTrampolineParams(nodeId = randomKey().publicKey, trampolineOn)

      val update1 = TrampolineStatusInit(
        List(List(params1), List(params1, params2)),
        trampolineOn
      )
      val update2 = TrampolineStatusUpdate(
        List(List(params1), List(params1, params2)),
        Map(randomKey().publicKey -> trampolineOn),
        Some(trampolineOn),
        Set(randomKey().publicKey, randomKey().publicKey)
      )

      assert(
        TrampolineStatusInit.codec
          .decode(TrampolineStatusInit.codec.encode(update1).require)
          .require
          .value == update1
      )
      assert(
        TrampolineStatusUpdate.codec
          .decode(TrampolineStatusUpdate.codec.encode(update2).require)
          .require
          .value == update2
      )
    }

    test("HC short channel ids are random") {
      val hostNodeId = randomBytes32()
      val iterations = 1000000
      val sids =
        List.fill(iterations)(
          hostedShortChannelId(randomBytes32(), hostNodeId)
        )
      assert(sids.size == sids.toSet.size)
    }
  }
}
