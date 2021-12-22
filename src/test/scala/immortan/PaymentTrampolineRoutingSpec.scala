package immortan

import fr.acinq.bitcoin.{Block, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.IncomingPaymentPacket.{FinalPacket, NodeRelayPacket, decrypt}
import fr.acinq.eclair.payment.{IncomingPaymentPacket, OutgoingPaymentPacket, PaymentRequest}
import fr.acinq.eclair.router.Router.NodeHop
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteUpdateFail, RemoteUpdateMalform}
import fr.acinq.eclair.wire._
import immortan.fsm._
import immortan.utils.ChannelUtils._
import immortan.utils.GraphUtils._
import immortan.utils.PaymentUtils._
import immortan.utils.TestUtils._
import org.scalatest.funsuite.AnyFunSuite


class PaymentTrampolineRoutingSpec extends AnyFunSuite {
  test("Correctly parse trampoline routed payments sent to our fake nodeId") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

    val ourParams = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0D, logExponent = 0D, CltvExpiryDelta(72))
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(100000L.msat), randomBytes32, randomBytes32, dP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer)
    val outerPaymentSecret = randomBytes32

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), trampolineFees = 1000L.msat)
    val reasonableTrampoline1 = createResolution(pr, 11000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 90000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val fsm = new TrampolinePaymentRelayer(fullTag = FullPaymentTag(null, null, PaymentTagTlv.TRAMPLOINE_ROUTED), cm) { lastAmountIn = List(reasonableTrampoline1, reasonableTrampoline2).map(_.add.amountMsat).sum  }
    assert(fsm.validateRelay(ourParams, List(reasonableTrampoline1, reasonableTrampoline2), LNParams.blockCount.get).isEmpty)
  }

  test("Successfully parse a trampoline-to-legacy payment on payee side") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    // s -> us -> a

    val preimage = randomBytes32
    val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), randomBytes32, randomBytes32, aP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is A which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, a, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 707000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)

    WAIT_UNTIL_TRUE {
      val Seq(add1) = cm.all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing)
      IncomingPaymentPacket.decrypt(add1, aP).right.get.isInstanceOf[FinalPacket]
    }
  }

  test("Successfully parse a multipart native trampoline payment on payee side") {
    // In this case payer sends 400,000 sat through us while total requested amount is 700,000 sat
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    // s -> us -> a

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, randomBytes32, aP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is A which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (_, trampolineExpiry, trampolineOnion) = createInnerNativeTrampoline(partAmount = 400000L.msat, pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, a, CltvExpiryDelta(720), feeReserve)
    val theirAdd = createTrampolineAdd(pr, outerPartAmount = 404000L.msat, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, trampolineAmountTotal = 404000L.msat, trampolineExpiry, trampolineOnion, outerPaymentSecret)
    val reasonableTrampoline1 = cm.initResolve(UpdateAddHtlcExt(theirAdd = theirAdd, remoteInfo = remoteNodeInfo)).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)

    WAIT_UNTIL_TRUE {
      val Seq(add1) = cm.all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing)
      val finalPacket = IncomingPaymentPacket.decrypt(add1, aP).right.get.asInstanceOf[FinalPacket]
      assert(finalPacket.payload.paymentSecret == pr.paymentSecret.get) // Payment secret is internal, payee will be able to group trampolines from various sources together
      assert(finalPacket.payload.totalAmount == 700000L.msat) // Total amount was not seen by relaying trampoline node, but equal to requested by payee
      finalPacket.payload.amount == add1.amountMsat
    }
  }

  test("Successfully parse a double trampoline payment") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)

    // e -(trampoline)-> s -(trampoline)-> us -(legacy)-> d

    val paymentHash = Crypto.sha256(randomBytes32)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(500000L.msat), paymentHash, randomBytes32, dP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is D which we have direct channel with
    val upstreamRemoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an intermediary trampoline router (who is our peer with a private channel)
    val feeReserve = 5000L.msat

    val trampolineRoute = Seq(
      NodeHop(e, s, CltvExpiryDelta(0), 0.msat), // a hop from sender to their peer, only needed because of sender NodeId
      NodeHop(s, upstreamRemoteNodeInfo.nodeSpecificPubKey, LNParams.ourRoutingCltvExpiryDelta, feeReserve), // Intermediary router
      NodeHop(upstreamRemoteNodeInfo.nodeSpecificPubKey, d, LNParams.ourRoutingCltvExpiryDelta, feeReserve) // Final payee
    )

    // We send to a receiver who does not support trampoline, so relay node will send a basic MPP with inner payment secret provided and revealed
    val finalInnerPayload = PaymentOnion.createSinglePartPayload(pr.amount.get, CltvExpiry(18), pr.paymentSecret.get) // Final CLTV is supposed to be taken from invoice (+ assuming tip = 0 when testing)
    val (trampolineAmountTotal, trampolineExpiry, finalOnion) = OutgoingPaymentPacket.buildTrampolineToLegacyPacket(randomKey, pr, trampolineRoute, finalInnerPayload)

    val intermediaryPayload = PaymentOnion.createTrampolinePayload(trampolineAmountTotal, trampolineAmountTotal, trampolineExpiry, randomBytes32, finalOnion.packet)
    val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPaymentPacket(randomKey, paymentHash, Seq(NodeHop(e, s, CltvExpiryDelta(0), 0.msat)), intermediaryPayload)

    val add_e_s = UpdateAddHtlc(randomBytes32, secureRandom.nextInt(1000), firstAmount, pr.paymentHash, firstExpiry, onion.packet)
    val Right(NodeRelayPacket(_, outer_s, inner_s, packet_s)) = decrypt(add_e_s, sP)

    assert(outer_s.amount === pr.amount.get + feeReserve * 2)
    assert(outer_s.totalAmount === pr.amount.get + feeReserve * 2)
    assert(CltvExpiryDelta(outer_s.expiry.underlying.toInt) === LNParams.ourRoutingCltvExpiryDelta + LNParams.ourRoutingCltvExpiryDelta + pr.minFinalCltvExpiryDelta.get)
    assert(outer_s.paymentSecret !== pr.paymentSecret)
    assert(inner_s.amountToForward === pr.amount.get + feeReserve)
    assert(CltvExpiryDelta(inner_s.outgoingCltv.underlying.toInt) === LNParams.ourRoutingCltvExpiryDelta + pr.minFinalCltvExpiryDelta.get)
    assert(inner_s.outgoingNodeId === upstreamRemoteNodeInfo.nodeSpecificPubKey)
    assert(inner_s.invoiceRoutingInfo === None)
    assert(inner_s.invoiceFeatures === None)
    assert(inner_s.paymentSecret === None)

    val finalNodeHop = NodeHop(s, upstreamRemoteNodeInfo.nodeSpecificPubKey, LNParams.ourRoutingCltvExpiryDelta, feeReserve)
    val finalPayload = PaymentOnion.createTrampolinePayload(outer_s.totalAmount - feeReserve, outer_s.totalAmount - feeReserve, outer_s.expiry - LNParams.ourRoutingCltvExpiryDelta, randomBytes32, packet_s)
    val (amount_s_us, expiry_s_us, onion_s_us) = OutgoingPaymentPacket.buildPaymentPacket(randomKey, paymentHash, finalNodeHop :: Nil, finalPayload)

    val add_s_us = UpdateAddHtlc(randomBytes32, secureRandom.nextInt(1000), amount_s_us, pr.paymentHash, expiry_s_us, onion_s_us.packet)
    val Right(NodeRelayPacket(_, outer_s_us, inner_s_us, _)) = decrypt(add_s_us, upstreamRemoteNodeInfo.nodeSpecificPrivKey)

    assert(outer_s_us.amount === pr.amount.get + feeReserve)
    assert(outer_s_us.totalAmount === pr.amount.get + feeReserve)
    assert(CltvExpiryDelta(outer_s_us.expiry.underlying.toInt) === LNParams.ourRoutingCltvExpiryDelta + pr.minFinalCltvExpiryDelta.get)
    assert(outer_s_us.paymentSecret !== pr.paymentSecret)
    assert(inner_s_us.amountToForward === pr.amount.get)
    assert(inner_s_us.outgoingCltv.underlying === 18)
    assert(inner_s_us.outgoingNodeId === d)
    assert(inner_s_us.totalAmount === pr.amount.get)
    assert(inner_s_us.paymentSecret === pr.paymentSecret)
    assert(inner_s_us.invoiceRoutingInfo === Some(pr.routingInfo))
  }

  test("Successfully route a multipart trampoline payment") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    //             / b \
    // s -> us -> a     d
    //             \ c /

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, randomBytes32, dP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline3 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.RECEIVING)

    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.SENDING)

    val outPacket = WAIT_UNTIL_RESULT(cm.opm.data.payments(reasonableTrampoline1.fullTag).data.inFlightParts.head.cmd.packetAndSecrets.packet)
    val ourAdd = UpdateAddHtlc(null, 1, MilliSatoshi(0L), paymentHash, CltvExpiry(100L), outPacket, null)

    val ourMinimalFee = LNParams.trampoline.relayFee(reasonableTrampoline3.packet.innerPayload.amountToForward)
    WAIT_UNTIL_TRUE(cm.opm.data.payments(reasonableTrampoline1.fullTag).data.cmd.split.myPart == pr.amount.get) // With trampoline-to-legacy we find out a final amount
    assert(cm.opm.data.payments(reasonableTrampoline1.fullTag).data.cmd.totalFeeReserve == feeReserve - ourMinimalFee) // At the very least we collect base trampoline fee

    // Sender FSM in turn notifies relay FSM, meanwhile we simulate multiple noisy incoming messages
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    cm.opm process RemoteFulfill(ourAdd, preimage)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline2 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    WAIT_UNTIL_TRUE(fsm.data.isInstanceOf[TrampolineRevealed])
    // We are guaranteed to receive InFlightPayments in a same thread after fulfill because FSM asks for it
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    val history = cm.payBag.listRecentRelays(10).headTry(cm.payBag.toRelayedPreimageInfo).get
    WAIT_UNTIL_TRUE(history.relayed == pr.amount.get)
    WAIT_UNTIL_TRUE(history.earned == 6984L.msat)
  }

  test("Reject on incoming timeout") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), randomBytes32, randomBytes32, dP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    var replies = List.empty[Any]
    cm.sendTo = (change, _) => replies ::= change

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.RECEIVING)

    fsm doProcess IncomingPaymentProcessor.CMDTimeout
    assert(fsm.state == IncomingPaymentProcessor.FINALIZING)
    // FSM asks channel master to provide current HTLC data right away
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)

    WAIT_UNTIL_TRUE(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(PaymentTimeout))
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    // Sender FSM has been removed
    WAIT_UNTIL_TRUE(cm.opm.data.payments.isEmpty)
  }

  test("Reject on outgoing timeout") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), randomBytes32, randomBytes32, dP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    var replies = List.empty[Any]
    cm.sendTo = (change, _) => replies ::= change

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 707000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.SENDING)
    // Channels are not open so outgoing payments are waiting for timeout

    WAIT_UNTIL_TRUE {
      cm.opm.data.payments(reasonableTrampoline1.fullTag) doProcess OutgoingPaymentMaster.CMDAbort
      fsm.state == IncomingPaymentProcessor.FINALIZING
    }

    // FSM asks channel master to provide current HTLC data right away
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    WAIT_UNTIL_TRUE(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(TemporaryNodeFailure))
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    // Sender FSM has been removed
    WAIT_UNTIL_TRUE(cm.opm.data.payments.isEmpty)
  }

  test("Fail to relay with outgoing channel getting SUSPENDED") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, randomBytes32, dP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    var replies = List.empty[Any]
    cm.sendTo = (change, _) => replies ::= change

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    // Our only outgoing channel got unusable while we were collecting payment parts
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.CLOSING))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 707000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    // FSM asks channel master to provide current HTLC data right away
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    WAIT_UNTIL_TRUE(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(TemporaryNodeFailure))
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    // Sender FSM has been removed
    WAIT_UNTIL_TRUE(cm.opm.data.payments.isEmpty)
  }

  test("Fail to relay with no route found") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, randomBytes32, eP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is E which is not in a graph!
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    var replies = List.empty[Any]
    cm.sendTo = (change, _) => replies ::= change

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, e, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 707000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    // FSM asks channel master to provide current HTLC data right away
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    WAIT_UNTIL_TRUE(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(TemporaryNodeFailure))
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    // Sender FSM has been removed
    WAIT_UNTIL_TRUE(cm.opm.data.payments.isEmpty)
  }

  test("Restart after first fail, wind down on second fail") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf.copy(maxRemoteAttempts = 0) // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val (normalStore, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, randomBytes32, dP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    fillDirectGraph(normalStore)

    // s -> us -> a == d

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline3 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)

    // Simulate making new FSM on restart
    fsm.become(null, IncomingPaymentProcessor.RECEIVING)
    WAIT_UNTIL_TRUE(cm.all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing).size == 2)
    val Seq(out1, out2) = cm.all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing).filter(_.fullTag == reasonableTrampoline1.fullTag)
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    assert(fsm.data.asInstanceOf[TrampolineStopping].retry)
    // User has removed an outgoing HC meanwhile
    cm.all = Map.empty

    cm.opm process RemoteUpdateMalform(UpdateFailMalformedHtlc(out1.channelId, out1.id, randomBytes32, failureCode = 1), out1)
    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out2.channelId, out2.id, randomBytes32.bytes), out2) // Finishes it
    WAIT_UNTIL_TRUE(fsm.data == null && fsm.state == IncomingPaymentProcessor.RECEIVING)

    // All outgoing parts have been cleared, but we still have incoming parts and maybe can try again (unless CLTV delta has expired)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    assert(fsm.state == IncomingPaymentProcessor.SENDING)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
  }

  test("Wind down after pathologc fail") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val (normalStore, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, randomBytes32, dP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    fillDirectGraph(normalStore)

    // s -> us -> a == d

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline3 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)

    // Simulate making new FSM on restart
    fsm.become(null, IncomingPaymentProcessor.RECEIVING)
    WAIT_UNTIL_TRUE(cm.all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing).size == 2)
    val Seq(out1, out2) = cm.all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing).filter(_.fullTag == reasonableTrampoline1.fullTag)
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = reasonableTrampoline2 :: Nil)
    // Pathologic state: we do not have enough incoming payments, yet have outgoing payments
    assert(!fsm.data.asInstanceOf[TrampolineStopping].retry)
    // User has removed an outgoing HC meanwhile
    cm.all = Map.empty

    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out1.channelId, out1.id, randomBytes32.bytes), out1)
    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out1.channelId, out1.id, randomBytes32.bytes), out1) // Noisy event
    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out2.channelId, out2.id, randomBytes32.bytes), out2) // Finishes it

    var replies = List.empty[Any]
    cm.sendTo = (change, _) => replies ::= change

    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline2 :: Nil) // Noisy event
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline2 :: Nil) // Got after `wholePaymentFailed` has fired
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    println(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason)
    WAIT_UNTIL_TRUE(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(TemporaryNodeFailure))
  }

  test("Fulfill in a pathologic fail state") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(minMsat = 1000L.msat, maxMsat = Long.MaxValue.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, randomBytes32, dP, "Invoice", CltvExpiryDelta(18), Nil) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (normalStore, _, cm) = makeChannelMaster(Nil)
    fillDirectGraph(normalStore)

    // s -> us -> a == d

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline3 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret, cm).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)

    // Simulate making new FSM on restart
    fsm.become(null, IncomingPaymentProcessor.RECEIVING)
    WAIT_UNTIL_TRUE(cm.all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing).size == 2)
    val Seq(out1, out2) = cm.all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing).filter(_.fullTag == reasonableTrampoline1.fullTag)
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = reasonableTrampoline2 :: Nil)
    // Pathologic state: we do not have enough incoming payments, yet have outgoing payments
    assert(!fsm.data.asInstanceOf[TrampolineStopping].retry)

    val senderDataSnapshot = cm.opm.data.payments(reasonableTrampoline1.fullTag).data
    val ourAdd = UpdateAddHtlc(null, 1, MilliSatoshi(0L), paymentHash, CltvExpiry(100L), senderDataSnapshot.inFlightParts.head.cmd.packetAndSecrets.packet, null)
    cm.opm process RemoteFulfill(ourAdd, preimage)

    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = reasonableTrampoline2 :: Nil)
    WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[TrampolineRevealed].preimage == preimage)
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = Nil)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)

    fsm.become(null, IncomingPaymentProcessor.RECEIVING)
    WAIT_UNTIL_TRUE(cm.payBag.getPreimage(paymentHash).toOption.contains(preimage))
    // All incoming have been fulfilled, but we still have outgoing (channel offline?)
    // This is fine since we have a preiamge in case of what, but FSM is kept working
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = Nil)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
  }
}
