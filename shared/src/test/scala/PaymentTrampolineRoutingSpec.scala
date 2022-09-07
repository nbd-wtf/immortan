package immortan

import scala.language.existentials
import utest._
import scoin._
import scoin.Crypto.randomBytes
import scoin.ln.IncomingPaymentPacket.{FinalPacket, NodeRelayPacket, decrypt}
import scoin.ln._

import immortan.channel._
import immortan.router._
import immortan.router.Router.NodeHop
import immortan.channel.{RemoteFulfill, RemoteUpdateFail, RemoteUpdateMalform}
import immortan.fsm._
import immortan.utils.ChannelUtils._
import immortan.utils.GraphUtils._
import immortan.utils.PaymentUtils._
import immortan.utils.TestUtils._

object PaymentTrampolineRoutingSpec extends TestSuite {
  val tests = Tests {
    test("Correctly parse trampoline routed payments sent to our fake nodeId") {
      LNParams.secret = WalletSecret.random()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      val ourParams = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0d,
        logExponent = 0d,
        CltvExpiryDelta(72)
      )
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(100000L)),
        randomBytes32(),
        dP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer)
      val outerPaymentSecret = randomBytes32()

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          d,
          CltvExpiryDelta(720),
          trampolineFees = MilliSatoshi(1000L)
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(11000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val reasonableTrampoline2 = createResolution(
        pr,
        MilliSatoshi(90000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val fsm = new TrampolinePaymentRelayer(
        fullTag = FullPaymentTag(null, null, PaymentTagTlv.TRAMPLOINE_ROUTED),
        cm
      ) {
        lastAmountIn = List(reasonableTrampoline1, reasonableTrampoline2)
          .map(_.add.amountMsat)
          .fold(MilliSatoshi(0))(_ + _)
      }
      assert(
        fsm
          .validateRelay(
            ourParams,
            List(reasonableTrampoline1, reasonableTrampoline2),
            LNParams.blockCount.get
          )
          .isEmpty
      )
    }

    test("Successfully parse a trampoline-to-legacy payment on payee side") {
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf =
        routerConf // Replace with the one which allows for smaller parts

      // s -> us -> a

      val preimage = randomBytes32()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        randomBytes32(),
        aP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is A which we do not have direct channels with
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)

      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      // Private channel US -> A
      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          a,
          CltvExpiryDelta(720),
          feeReserve
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(707000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )

      WAIT_UNTIL_TRUE {
        val Seq(add1) = cm.all.values
          .flatMap(Channel.chanAndCommitsOpt)
          .flatMap(_.commits.allOutgoing)
        IncomingPaymentPacket
          .decrypt(add1, aP)
          .toOption
          .get
          .isInstanceOf[FinalPacket]
      }
    }

    test(
      "Successfully parse a multipart native trampoline payment on payee side"
    ) {
      // In this case payer sends 400,000 sat through us while total requested amount is 700,000 sat
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf =
        routerConf // Replace with the one which allows for smaller parts

      // s -> us -> a

      val preimage = randomBytes32()
      val paymentHash = Crypto.sha256(preimage)
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        paymentHash,
        aP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is A which we do not have direct channels with
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)

      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      // Private channel US -> A
      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      val (_, trampolineExpiry, trampolineOnion) = createInnerNativeTrampoline(
        partAmount = MilliSatoshi(400000L),
        pr,
        remoteNodeInfo.nodeId,
        remoteNodeInfo.nodeSpecificPubKey,
        a,
        CltvExpiryDelta(720),
        feeReserve
      )
      val theirAdd = createTrampolineAdd(
        pr,
        outerPartAmount = MilliSatoshi(404000L),
        remoteNodeInfo.nodeId,
        remoteNodeInfo.nodeSpecificPubKey,
        trampolineAmountTotal = MilliSatoshi(404000L),
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret
      )
      val reasonableTrampoline1 = cm
        .initResolve(
          UpdateAddHtlcExt(theirAdd = theirAdd, remoteInfo = remoteNodeInfo)
        )
        .asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )

      WAIT_UNTIL_TRUE {
        val Seq(add1) = cm.all.values
          .flatMap(Channel.chanAndCommitsOpt)
          .flatMap(_.commits.allOutgoing)
        val finalPacket = IncomingPaymentPacket
          .decrypt(add1, aP)
          .toOption
          .get
          .asInstanceOf[FinalPacket]
        assert(
          finalPacket.payload.paymentSecret == pr.paymentSecret.get
        ) // Payment secret is internal, payee will be able to group trampolines from various sources together
        assert(
          finalPacket.payload.totalAmount == MilliSatoshi(700000L)
        ) // Total amount was not seen by relaying trampoline node, but equal to requested by payee
        finalPacket.payload.amount == add1.amountMsat
      }
    }

    test("Successfully parse a double trampoline payment") {
      LNParams.secret = WalletSecret.random()

      // e -(trampoline)-> s -(trampoline)-> us -(legacy)-> d

      val paymentHash = Crypto.sha256(randomBytes32())
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(500000L)),
        paymentHash,
        dP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is D which we have direct channel with
      val upstreamRemoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an intermediary trampoline router (who is our peer with a private channel)
      val feeReserve = MilliSatoshi(5000L)

      val trampolineRoute = Seq(
        NodeHop(
          e,
          s,
          CltvExpiryDelta(0),
          MilliSatoshi(0L)
        ), // a hop from sender to their peer, only needed because of sender NodeId
        NodeHop(
          s,
          upstreamRemoteNodeInfo.nodeSpecificPubKey,
          LNParams.ourRoutingCltvExpiryDelta,
          feeReserve
        ), // Intermediary router
        NodeHop(
          upstreamRemoteNodeInfo.nodeSpecificPubKey,
          d,
          LNParams.ourRoutingCltvExpiryDelta,
          feeReserve
        ) // Final payee
      )

      // We send to a receiver who does not support trampoline, so relay node will send a basic MPP with inner payment secret provided and revealed
      val finalInnerPayload = PaymentOnion.createSinglePartPayload(
        pr.amount_opt.get,
        CltvExpiry(18),
        pr.paymentSecret.get,
        None
      ) // Final CLTV is supposed to be taken from invoice (+ assuming tip = 0 when testing)
      val (trampolineAmountTotal, trampolineExpiry, finalOnion) =
        OutgoingPaymentPacket
          .buildTrampolineToLegacyPacket(
            pr,
            trampolineRoute,
            finalInnerPayload
          )
          .toOption
          .get

      val intermediaryPayload = PaymentOnion.createTrampolinePayload(
        trampolineAmountTotal,
        trampolineAmountTotal,
        trampolineExpiry,
        randomBytes32(),
        finalOnion.packet
      )
      val (firstAmount, firstExpiry, onion) =
        OutgoingPaymentPacket
          .buildPaymentPacket(
            paymentHash,
            Seq(NodeHop(e, s, CltvExpiryDelta(0), MilliSatoshi(0L))),
            intermediaryPayload
          )
          .toOption
          .get

      val add_e_s = UpdateAddHtlc(
        randomBytes32(),
        randomBytes(2).toLong() % 1000,
        firstAmount,
        pr.paymentHash,
        firstExpiry,
        onion.packet
      )
      val Right(NodeRelayPacket(_, outer_s, inner_s, packet_s)) =
        decrypt(add_e_s, sP)

      assert(outer_s.amount == pr.amount_opt.get + feeReserve * 2)
      assert(outer_s.totalAmount == pr.amount_opt.get + feeReserve * 2)
      assert(
        CltvExpiryDelta(
          outer_s.expiry.toLong.toInt
        ) == LNParams.ourRoutingCltvExpiryDelta + LNParams.ourRoutingCltvExpiryDelta + pr.minFinalCltvExpiryDelta
      )
      assert(
        pr.paymentSecret.isEmpty || outer_s.paymentSecret != pr.paymentSecret.get
      )
      assert(inner_s.amountToForward == pr.amount_opt.get + feeReserve)
      assert(
        CltvExpiryDelta(
          inner_s.outgoingCltv.toLong.toInt
        ) == LNParams.ourRoutingCltvExpiryDelta + pr.minFinalCltvExpiryDelta
      )
      assert(
        inner_s.outgoingNodeId == upstreamRemoteNodeInfo.nodeSpecificPubKey
      )
      assert(inner_s.invoiceRoutingInfo == None)
      assert(inner_s.invoiceFeatures == None)
      assert(inner_s.paymentSecret == None)

      val finalNodeHop = NodeHop(
        s,
        upstreamRemoteNodeInfo.nodeSpecificPubKey,
        LNParams.ourRoutingCltvExpiryDelta,
        feeReserve
      )
      val finalPayload = PaymentOnion.createTrampolinePayload(
        outer_s.totalAmount - feeReserve,
        outer_s.totalAmount - feeReserve,
        outer_s.expiry - LNParams.ourRoutingCltvExpiryDelta,
        randomBytes32(),
        packet_s
      )
      val (amount_s_us, expiry_s_us, onion_s_us) =
        OutgoingPaymentPacket
          .buildPaymentPacket(
            paymentHash,
            finalNodeHop :: Nil,
            finalPayload
          )
          .toOption
          .get

      val add_s_us = UpdateAddHtlc(
        randomBytes32(),
        randomBytes(2).toLong() % 1000,
        amount_s_us,
        pr.paymentHash,
        expiry_s_us,
        onion_s_us.packet
      )
      val Right(NodeRelayPacket(_, outer_s_us, inner_s_us, _)) =
        decrypt(add_s_us, upstreamRemoteNodeInfo.nodeSpecificPrivKey)

      assert(outer_s_us.amount == pr.amount_opt.get + feeReserve)
      assert(outer_s_us.totalAmount == pr.amount_opt.get + feeReserve)
      assert(
        CltvExpiryDelta(
          outer_s_us.expiry.toLong.toInt
        ) == LNParams.ourRoutingCltvExpiryDelta + pr.minFinalCltvExpiryDelta
      )
      pr.paymentSecret match {
        case Some(s) => { assert(outer_s_us.paymentSecret != s) }
        case None    => {}
      }
      assert(inner_s_us.amountToForward == pr.amount_opt.get)
      assert(inner_s_us.outgoingCltv.toLong == 18)
      assert(inner_s_us.outgoingNodeId == d)
      assert(inner_s_us.totalAmount == pr.amount_opt.get)
      assert(inner_s_us.paymentSecret == pr.paymentSecret)
      assert(inner_s_us.invoiceRoutingInfo == Some(pr.routingInfo))
    }

    test("Successfully route a multipart trampoline payment") {
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf =
        routerConf // Replace with the one which allows for smaller parts

      //             / b \
      // s -> us -> a     d
      //             \ c /

      val preimage = randomBytes32()
      val paymentHash = Crypto.sha256(preimage)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        paymentHash,
        dP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is D which we do not have direct channels with
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      // Private channel US -> A
      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          d,
          CltvExpiryDelta(720),
          feeReserve
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(105000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val reasonableTrampoline2 = createResolution(
        pr,
        MilliSatoshi(301000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val reasonableTrampoline3 = createResolution(
        pr,
        MilliSatoshi(301000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Receiving
      )

      fsm doProcess makeInFlightPayments(
        out = Nil,
        in =
          reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )

      WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.Sending)

      val outPacket = WAIT_UNTIL_RESULT(
        cm.opm.data
          .paymentSenders(reasonableTrampoline1.fullTag)
          .data
          .inFlightParts
          .head
          .cmd
          .packetAndSecrets
          .packet
      )
      val ourAdd = UpdateAddHtlc(
        null,
        1,
        MilliSatoshi(0L),
        paymentHash,
        CltvExpiry(100L),
        outPacket
      )

      val ourMinimalFee = LNParams.trampoline.relayFee(
        reasonableTrampoline3.packet.innerPayload.amountToForward
      )
      WAIT_UNTIL_TRUE(
        cm.opm.data
          .paymentSenders(reasonableTrampoline1.fullTag)
          .data
          .cmd
          .split
          .myPart == pr.amount_opt.get
      ) // With trampoline-to-legacy we find out a final amount
      assert(
        cm.opm.data
          .paymentSenders(reasonableTrampoline1.fullTag)
          .data
          .cmd
          .totalFeeReserve == feeReserve - ourMinimalFee
      ) // At the very least we collect base trampoline fee

      // Sender FSM in turn notifies relay FSM, meanwhile we simulate multiple noisy incoming messages
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in =
          reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )
      cm.opm process RemoteFulfill(ourAdd, preimage)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: reasonableTrampoline2 :: Nil
      )
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in =
          reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )
      WAIT_UNTIL_TRUE(fsm.data.isInstanceOf[TrampolineRevealed])
      // We are guaranteed to receive InFlightPayments in a same thread after fulfill because FSM asks for it
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in =
          reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)

      WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.Shutdown)
      val history = cm.payBag
        .listRecentRelays(10)
        .headTry(cm.payBag.toRelayedPreimageInfo)
        .get
      WAIT_UNTIL_TRUE(history.relayed == pr.amount_opt.get)
      WAIT_UNTIL_TRUE(history.earned == MilliSatoshi(6984L))
    }

    test("Reject on incoming timeout") {
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf =
        routerConf // Replace with the one which allows for smaller parts

      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        randomBytes32(),
        dP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is D which we do not have direct channels with
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)
      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      var replies = List.empty[Any]
      cm.sendTo = (change, _) => replies ::= change

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          d,
          CltvExpiryDelta(720),
          feeReserve
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(105000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )
      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Receiving
      )

      fsm doProcess IncomingPaymentProcessor.CMDTimeout
      assert(fsm.state == IncomingPaymentProcessor.Finalizing)
      // FSM asks channel master to provide current HTLC data right away
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )

      WAIT_UNTIL_TRUE(
        replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(PaymentTimeout)
      )
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
      WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.Shutdown)
      // Sender FSM has been removed
      WAIT_UNTIL_TRUE(cm.opm.data.paymentSenders.isEmpty)
    }

    test("Reject on outgoing timeout") {
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf =
        routerConf // Replace with the one which allows for smaller parts

      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        randomBytes32(),
        dP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is D which we do not have direct channels with
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)
      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      var replies = List.empty[Any]
      cm.sendTo = (change, _) => replies ::= change

      // Private channel US -> A
      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          d,
          CltvExpiryDelta(720),
          feeReserve
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(707000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )

      WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.Sending)
      // Channels are not open so outgoing payments are waiting for timeout

      WAIT_UNTIL_TRUE {
        cm.opm.data.paymentSenders(
          reasonableTrampoline1.fullTag
        ) doProcess OutgoingPaymentMaster.CMDAbort
        fsm.state == IncomingPaymentProcessor.Finalizing
      }

      // FSM asks channel master to provide current HTLC data right away
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )
      WAIT_UNTIL_TRUE(
        replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(
          TemporaryNodeFailure
        )
      )
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
      assert(fsm.state == IncomingPaymentProcessor.Shutdown)
      // Sender FSM has been removed
      WAIT_UNTIL_TRUE(cm.opm.data.paymentSenders.isEmpty)
    }

    test("Fail to relay with outgoing channel getting SUSPENDED") {
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf =
        routerConf // Replace with the one which allows for smaller parts

      val preimage = randomBytes32()
      val paymentHash = Crypto.sha256(preimage)
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        paymentHash,
        dP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is D which we do not have direct channels with
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)
      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      var replies = List.empty[Any]
      cm.sendTo = (change, _) => replies ::= change

      // Private channel US -> A
      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)
      // Our only outgoing channel got unusable while we were collecting payment parts
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Closing))

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          d,
          CltvExpiryDelta(720),
          feeReserve
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(707000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      // FSM asks channel master to provide current HTLC data right away
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )
      WAIT_UNTIL_TRUE(
        replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(
          TemporaryNodeFailure
        )
      )
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
      assert(fsm.state == IncomingPaymentProcessor.Shutdown)
      // Sender FSM has been removed
      WAIT_UNTIL_TRUE(cm.opm.data.paymentSenders.isEmpty)
    }

    test("Fail to relay with no route found") {
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf =
        routerConf // Replace with the one which allows for smaller parts

      val preimage = randomBytes32()
      val paymentHash = Crypto.sha256(preimage)
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        paymentHash,
        eP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is E which is not in a graph!
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)
      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      var replies = List.empty[Any]
      cm.sendTo = (change, _) => replies ::= change

      // Private channel US -> A
      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          e,
          CltvExpiryDelta(720),
          feeReserve
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(707000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      // FSM asks channel master to provide current HTLC data right away
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline1 :: Nil
      )
      WAIT_UNTIL_TRUE(
        replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(
          TemporaryNodeFailure
        )
      )
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
      assert(fsm.state == IncomingPaymentProcessor.Shutdown)
      // Sender FSM has been removed
      WAIT_UNTIL_TRUE(cm.opm.data.paymentSenders.isEmpty)
    }

    test("Restart after first fail, wind down on second fail") {
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf = routerConf.copy(maxRemoteAttempts =
        0
      ) // Replace with the one which allows for smaller parts

      val preimage = randomBytes32()
      val paymentHash = Crypto.sha256(preimage)
      val (normalStore, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        paymentHash,
        dP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is D which we do not have direct channels with
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)
      fillDirectGraph(normalStore)

      // s -> us -> a == d

      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      // Private channel US -> A
      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          d,
          CltvExpiryDelta(720),
          feeReserve
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(105000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val reasonableTrampoline2 = createResolution(
        pr,
        MilliSatoshi(301000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val reasonableTrampoline3 = createResolution(
        pr,
        MilliSatoshi(301000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in =
          reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )

      // Simulate making new FSM on restart
      fsm.become(null, IncomingPaymentProcessor.Receiving)
      WAIT_UNTIL_TRUE(
        cm.all.values
          .flatMap(Channel.chanAndCommitsOpt)
          .flatMap(_.commits.allOutgoing)
          .size == 2
      )
      val Seq(out1, out2) = cm.all.values
        .flatMap(Channel.chanAndCommitsOpt)
        .flatMap(_.commits.allOutgoing)
        .filter(_.fullTag == reasonableTrampoline1.fullTag)
      fsm doProcess makeInFlightPayments(
        out = out1 :: out2 :: Nil,
        in =
          reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )
      assert(fsm.data.asInstanceOf[TrampolineStopping].retry)
      // User has removed an outgoing HC meanwhile
      cm.all = Map.empty

      cm.opm process RemoteUpdateMalform(
        UpdateFailMalformedHtlc(
          out1.channelId,
          out1.id,
          randomBytes32(),
          failureCode = 1
        ),
        out1
      )
      cm.opm process RemoteUpdateFail(
        UpdateFailHtlc(out2.channelId, out2.id, randomBytes32().bytes),
        out2
      ) // Finishes it
      WAIT_UNTIL_TRUE(
        fsm.data == null && fsm.state == IncomingPaymentProcessor.Receiving
      )

      // All outgoing parts have been cleared, but we still have incoming parts and maybe can try again (unless CLTV delta has expired)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in =
          reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )
      assert(fsm.state == IncomingPaymentProcessor.Sending)
      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
      assert(fsm.state == IncomingPaymentProcessor.Shutdown)
    }

    test("Wind down after pathologc fail") {
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf =
        routerConf // Replace with the one which allows for smaller parts

      val preimage = randomBytes32()
      val paymentHash = Crypto.sha256(preimage)
      val (normalStore, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        paymentHash,
        dP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is D which we do not have direct channels with
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)
      fillDirectGraph(normalStore)

      // s -> us -> a == d

      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      // Private channel US -> A
      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          d,
          CltvExpiryDelta(720),
          feeReserve
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(105000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val reasonableTrampoline2 = createResolution(
        pr,
        MilliSatoshi(301000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val reasonableTrampoline3 = createResolution(
        pr,
        MilliSatoshi(301000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in =
          reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )

      // Simulate making new FSM on restart
      fsm.become(null, IncomingPaymentProcessor.Receiving)
      WAIT_UNTIL_TRUE(
        cm.all.values
          .flatMap(Channel.chanAndCommitsOpt)
          .flatMap(_.commits.allOutgoing)
          .size == 2
      )
      val Seq(out1, out2) = cm.all.values
        .flatMap(Channel.chanAndCommitsOpt)
        .flatMap(_.commits.allOutgoing)
        .filter(_.fullTag == reasonableTrampoline1.fullTag)
      fsm doProcess makeInFlightPayments(
        out = out1 :: out2 :: Nil,
        in = reasonableTrampoline2 :: Nil
      )
      // Pathologic state: we do not have enough incoming payments, yet have outgoing payments
      assert(!fsm.data.asInstanceOf[TrampolineStopping].retry)
      // User has removed an outgoing HC meanwhile
      cm.all = Map.empty

      cm.opm process RemoteUpdateFail(
        UpdateFailHtlc(out1.channelId, out1.id, randomBytes32().bytes),
        out1
      )
      cm.opm process RemoteUpdateFail(
        UpdateFailHtlc(out1.channelId, out1.id, randomBytes32().bytes),
        out1
      ) // Noisy event
      cm.opm process RemoteUpdateFail(
        UpdateFailHtlc(out2.channelId, out2.id, randomBytes32().bytes),
        out2
      ) // Finishes it

      var replies = List.empty[Any]
      cm.sendTo = (change, _) => replies ::= change

      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline2 :: Nil
      ) // Noisy event
      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = reasonableTrampoline2 :: Nil
      ) // Got after `wholePaymentFailed` has fired
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
      WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.Shutdown)
      WAIT_UNTIL_TRUE(
        replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(
          TemporaryNodeFailure
        )
      )
    }

    test("Fulfill in a pathologic fail state") {
      LNParams.secret = WalletSecret.random()
      LNParams.trampoline = TrampolineOn(
        minMsat = MilliSatoshi(1000L),
        maxMsat = MilliSatoshi(Long.MaxValue),
        feeProportionalMillionths = 100,
        exponent = 0.97d,
        logExponent = 3.9d,
        CltvExpiryDelta(72)
      )
      LNParams.routerConf =
        routerConf // Replace with the one which allows for smaller parts

      val preimage = randomBytes32()
      val paymentHash = Crypto.sha256(preimage)
      val pr = Bolt11Invoice(
        Block.TestnetGenesisBlock.hash,
        Some(MilliSatoshi(700000L)),
        paymentHash,
        dP,
        Left("Invoice"),
        CltvExpiryDelta(18)
      ) // Final payee is D which we do not have direct channels with
      val remoteNodeInfo = RemoteNodeInfo(
        nodeId = s,
        address = null,
        alias = "peer-1"
      ) // How we see an initial sender (who is our peer with a private channel)
      val (normalStore, _, cm) = makeChannelMaster(Nil)
      fillDirectGraph(normalStore)

      // s -> us -> a == d

      val outerPaymentSecret = randomBytes32()
      val feeReserve = MilliSatoshi(7000L)

      // Private channel US -> A
      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
        createInnerLegacyTrampoline(
          pr,
          remoteNodeInfo.nodeId,
          remoteNodeInfo.nodeSpecificPubKey,
          d,
          CltvExpiryDelta(720),
          feeReserve
        )
      val reasonableTrampoline1 = createResolution(
        pr,
        MilliSatoshi(105000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val reasonableTrampoline2 = createResolution(
        pr,
        MilliSatoshi(301000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]
      val reasonableTrampoline3 = createResolution(
        pr,
        MilliSatoshi(301000L),
        remoteNodeInfo,
        trampolineAmountTotal,
        trampolineExpiry,
        trampolineOnion,
        outerPaymentSecret,
        cm
      ).asInstanceOf[ReasonableTrampoline]

      val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in =
          reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil
      )

      // Simulate making new FSM on restart
      fsm.become(null, IncomingPaymentProcessor.Receiving)
      WAIT_UNTIL_TRUE(
        cm.all.values
          .flatMap(Channel.chanAndCommitsOpt)
          .flatMap(_.commits.allOutgoing)
          .size == 2
      )
      val x = cm.all.values
        .flatMap(Channel.chanAndCommitsOpt)
        .flatMap(_.commits.allOutgoing)
      val Seq(out1, out2) = x
        .filter(_.fullTag == reasonableTrampoline1.fullTag)
      fsm doProcess makeInFlightPayments(
        out = out1 :: out2 :: Nil,
        in = reasonableTrampoline2 :: Nil
      )
      // Pathologic state: we do not have enough incoming payments, yet have outgoing payments
      assert(!fsm.data.asInstanceOf[TrampolineStopping].retry)

      val senderDataSnapshot =
        cm.opm.data.paymentSenders(reasonableTrampoline1.fullTag).data
      val ourAdd = UpdateAddHtlc(
        null,
        1,
        MilliSatoshi(0L),
        paymentHash,
        CltvExpiry(100L),
        senderDataSnapshot.inFlightParts.head.cmd.packetAndSecrets.packet,
        null
      )
      cm.opm process RemoteFulfill(ourAdd, preimage)

      fsm doProcess makeInFlightPayments(
        out = out1 :: out2 :: Nil,
        in = reasonableTrampoline2 :: Nil
      )
      WAIT_UNTIL_TRUE(
        fsm.data.asInstanceOf[TrampolineRevealed].preimage == preimage
      )
      fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = Nil)
      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )

      fsm.become(null, IncomingPaymentProcessor.Receiving)
      WAIT_UNTIL_TRUE(
        cm.payBag.getPreimage(paymentHash).toOption.contains(preimage)
      )
      // All incoming have been fulfilled, but we still have outgoing (channel offline?)
      // This is fine since we have a preiamge in case of what, but FSM is kept working
      fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = Nil)
      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
      WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.Shutdown)
    }
  }
}
