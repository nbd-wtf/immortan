package immortan

import fr.acinq.eclair._
import immortan.utils.TestUtils._
import immortan.utils.GraphUtils._
import immortan.utils.PaymentUtils._
import fr.acinq.eclair.wire.PaymentTimeout
import immortan.fsm.{IncomingAborted, IncomingPaymentProcessor, IncomingPaymentReceiver, IncomingRevealed}
import fr.acinq.eclair.channel.{CMD_FAIL_MALFORMED_HTLC, ReasonableLocal}
import immortan.utils.ChannelUtils.makeChannelMasterWithBasicGraph
import org.scalatest.funsuite.AnyFunSuite


class PaymentIncomingFinalSpec extends AnyFunSuite {
  test("Correctly parse final payments sent to our fake nodeIds") {
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
    LNParams.secret = WalletSecret(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)

    // Sent to our peer-specific fake nodeId
    val addFromRemote1 = createFinalAdd(600L.msat, totalAmount = 1000L.msat, randomBytes32, randomBytes32, from = s, to = remoteNodeInfo.nodeSpecificPubKey, cltvDelta = 144)
    val reasonableLocal1 = ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote1, remoteInfo = remoteNodeInfo))
    assert(reasonableLocal1.asInstanceOf[ReasonableLocal].secret == remoteNodeInfo.nodeSpecificPrivKey)

    // Sent to invoice-specific fake nodeId
    val invoiceHash = randomBytes32
    val fakeInvoicePrivKey = LNParams.secret.keys.fakeInvoiceKey(invoiceHash)
    val addFromRemote2 = createFinalAdd(600L.msat, totalAmount = 1000L.msat, invoiceHash, randomBytes32, from = s, to = fakeInvoicePrivKey.publicKey, cltvDelta = 144)
    val reasonableLocal2 = ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote2, remoteInfo = remoteNodeInfo))
    assert(reasonableLocal2.asInstanceOf[ReasonableLocal].secret == fakeInvoicePrivKey)

    // Sent to someone else, could not be parsed
    val addFromRemote3 = createFinalAdd(600L.msat, totalAmount = 1000L.msat, randomBytes32, randomBytes32, from = s, to = randomKey.publicKey, cltvDelta = 144)
    val reasonableLocal3 = ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote3, remoteInfo = remoteNodeInfo))
    assert(reasonableLocal3.asInstanceOf[CMD_FAIL_MALFORMED_HTLC].theirAdd == addFromRemote3)
  }

  test("Fulfill a single part incoming payment") {
    LNParams.secret = WalletSecret(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val preimage = randomBytes32
    val invoice = recordIncomingPaymentToFakeNodeId(amount = Some(100000L.msat), preimage, cm.payBag, remoteNodeInfo)
    val add1 = makeRemoteAddToFakeNodeId(partAmount = 100000L.msat, totalAmount = 100000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)

    assert(cm.getPreimageMemo(invoice.paymentHash).isFailure)
    assert(cm.getPaymentInfoMemo(invoice.paymentHash).get.status == PaymentStatus.PENDING)

    val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[IncomingRevealed].preimage == preimage)
    WAIT_UNTIL_TRUE(cm.getPreimageMemo(invoice.paymentHash).get == preimage)
    println(cm.getPaymentInfoMemo(invoice.paymentHash).get.status)
    WAIT_UNTIL_TRUE(cm.getPaymentInfoMemo(invoice.paymentHash).get.status == PaymentStatus.SUCCEEDED)
  }

  test("Fulfill multipart incoming payment") {
    LNParams.secret = WalletSecret(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val preimage = randomBytes32
    val invoice = recordIncomingPaymentToFakeNodeId(amount = Some(100000L.msat), preimage, cm.payBag, remoteNodeInfo)
    val add1 = makeRemoteAddToFakeNodeId(partAmount = 35000L.msat, totalAmount = 100000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)

    val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.RECEIVING)
    WAIT_UNTIL_TRUE(fsm.data == null)

    val add2 = makeRemoteAddToFakeNodeId(partAmount = 35000L.msat, totalAmount = 100000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)
    val add3 = makeRemoteAddToFakeNodeId(partAmount = 30000L.msat, totalAmount = 200000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)

    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = add3 :: add1 :: add2 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[IncomingRevealed].preimage == preimage)
    WAIT_UNTIL_TRUE(cm.getPreimageMemo(invoice.paymentHash).get == preimage)
    WAIT_UNTIL_TRUE(cm.getPaymentInfoMemo(invoice.paymentHash).get.status == PaymentStatus.SUCCEEDED)

    // Suppose user has restarted an app with only one part resolved in channels

    val fsm2 = new IncomingPaymentReceiver(add1.fullTag, cm)
    assert(fsm2.state == IncomingPaymentProcessor.RECEIVING)
    assert(fsm2.data == null)

    fsm2 doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)

    WAIT_UNTIL_TRUE(fsm2.state == IncomingPaymentProcessor.FINALIZING)
    WAIT_UNTIL_TRUE(fsm2.data.asInstanceOf[IncomingRevealed].preimage == preimage)
    WAIT_UNTIL_TRUE(cm.getPreimageMemo(invoice.paymentHash).get == preimage)
    WAIT_UNTIL_TRUE(cm.getPaymentInfoMemo(invoice.paymentHash).get.received == 100000L.msat) // Original amount is retained
  }

  test("Do not react to incoming payment with same hash, but different secret") {
    LNParams.secret = WalletSecret(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val preimage = randomBytes32
    val invoice = recordIncomingPaymentToFakeNodeId(amount = Some(100000L.msat), preimage, cm.payBag, remoteNodeInfo)
    val add1 = makeRemoteAddToFakeNodeId(partAmount = 35000L.msat, totalAmount = 100000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)
    val add2 = makeRemoteAddToFakeNodeId(partAmount = 35000L.msat, totalAmount = 100000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)
    val add3 = makeRemoteAddToFakeNodeId(partAmount = 30000L.msat, totalAmount = 200000L.msat, invoice.paymentHash, randomBytes32, remoteNodeInfo) // Different secret

    val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = add3 :: add1 :: add2 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.RECEIVING)
    WAIT_UNTIL_TRUE(fsm.data == null)

    val add4 = makeRemoteAddToFakeNodeId(partAmount = 40000L.msat, totalAmount = 200000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo) // A correct one
    fsm doProcess makeInFlightPayments(out = Nil, in = add3 :: add1 :: add4 :: add2 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[IncomingRevealed].preimage == preimage)
    WAIT_UNTIL_TRUE(cm.getPreimageMemo(invoice.paymentHash).get == preimage)
    WAIT_UNTIL_TRUE(cm.getPaymentInfoMemo(invoice.paymentHash).get.received == 110000.msat) // Sender has sent a bit more

    fsm doProcess makeInFlightPayments(out = Nil, in = add3 :: Nil)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
  }

  test("Fail an unknown payment right away") {
    LNParams.secret = WalletSecret(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val unknownHash = randomBytes32
    val unknownSecret = randomBytes32
    val add1 = makeRemoteAddToFakeNodeId(partAmount = 100000.msat, totalAmount = 100000.msat, unknownHash, unknownSecret, remoteNodeInfo)
    val add2 = makeRemoteAddToFakeNodeId(partAmount = 200000.msat, totalAmount = 200000.msat, unknownHash, unknownSecret, remoteNodeInfo)
    val add3 = makeRemoteAddToFakeNodeId(partAmount = 300000.msat, totalAmount = 400000.msat, unknownHash, unknownSecret, remoteNodeInfo)

    val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = add3 :: add1 :: add2 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[IncomingAborted].failure.isEmpty)

    // All parts have been cleared in channels
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
  }

  test("Fail if one of parts is too close to chain tip") {
    LNParams.secret = WalletSecret(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val preimage = randomBytes32
    val invoice = recordIncomingPaymentToFakeNodeId(amount = Some(100000L.msat), preimage, cm.payBag, remoteNodeInfo)
    val add1 = makeRemoteAddToFakeNodeId(partAmount = 35000L.msat, totalAmount = 100000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)
    val add2 = makeRemoteAddToFakeNodeId(partAmount = 35000L.msat, totalAmount = 100000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo, cltvDelta = 143) // One block too close
    val add3 = makeRemoteAddToFakeNodeId(partAmount = 30000L.msat, totalAmount = 200000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)

    val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = add3 :: add1 :: add2 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[IncomingAborted].failure.isEmpty)
  }

  test("Do not reveal a preimage on FSM entering failed state") {
    LNParams.secret = WalletSecret(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val preimage = randomBytes32
    val invoice = recordIncomingPaymentToFakeNodeId(amount = Some(100000L.msat), preimage, cm.payBag, remoteNodeInfo)
    val add1 = makeRemoteAddToFakeNodeId(partAmount = 35000L.msat, totalAmount = 100000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)
    val add2 = makeRemoteAddToFakeNodeId(partAmount = 35000L.msat, totalAmount = 100000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)
    val add3 = makeRemoteAddToFakeNodeId(partAmount = 30000L.msat, totalAmount = 200000L.msat, invoice.paymentHash, invoice.paymentSecret.get, remoteNodeInfo)

    val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
    fsm doProcess IncomingPaymentProcessor.CMDTimeout
    // FSM asks ChannelMaster for in-flight payments on getting timeout message
    fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
    // In a moment we actually receive the last part, but preimage is still not revelaed
    fsm doProcess makeInFlightPayments(out = Nil, in = add3 :: add1 :: add2 :: Nil)

    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.FINALIZING)
    WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[IncomingAborted].failure.contains(PaymentTimeout))

    // All parts have been cleared in channels
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
  }
}
