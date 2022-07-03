package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.wire.PaymentTimeout
import immortan.fsm.{
  IncomingAborted,
  IncomingPaymentProcessor,
  IncomingPaymentReceiver,
  IncomingRevealed
}
import immortan.utils.ChannelUtils.{
  makeChannelMasterWithBasicGraph,
  makeHostedCommits
}
import immortan.utils.GraphUtils._
import immortan.utils.PaymentUtils._
import immortan.utils.TestUtils._
import utest._

object PaymentIncomingFinalSpec extends TestSuite {
  val tests = Tests {
    test("Fulfill a single part incoming payment") {
      LNParams.secret = WalletSecret.random()
      val remoteNodeInfo =
        RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
      val (_, _, Seq(paymentSecret), cm) =
        makeChannelMasterWithBasicGraph(Seq(randomBytes32))

      val preimage = randomBytes32
      val invoice = recordIncomingPaymentToFakeNodeId(
        amount = Some(MilliSatoshi(100000L)),
        preimage,
        paymentSecret,
        cm.payBag,
        remoteNodeInfo
      )
      val add1 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(100000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )

      assert(cm.getPreimageMemo.get(invoice.paymentHash).isFailure)
      assert(
        cm.payBag
          .getPaymentInfo(invoice.paymentHash)
          .get
          .status == PaymentStatus.PENDING
      )

      val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      WAIT_UNTIL_TRUE(
        fsm.data.asInstanceOf[IncomingRevealed].preimage == preimage
      )
      WAIT_UNTIL_TRUE(
        cm.getPreimageMemo.get(invoice.paymentHash).get == preimage
      )
      WAIT_UNTIL_TRUE(
        cm.payBag
          .getPaymentInfo(invoice.paymentHash)
          .get
          .status == PaymentStatus.SUCCEEDED
      )
    }

    test("Fulfill multipart incoming payment") {
      LNParams.secret = WalletSecret.random()
      val remoteNodeInfo =
        RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
      val (_, _, Seq(paymentSecret), cm) =
        makeChannelMasterWithBasicGraph(Seq(randomBytes32))

      val preimage = randomBytes32
      val invoice = recordIncomingPaymentToFakeNodeId(
        amount = Some(MilliSatoshi(100000L)),
        preimage,
        paymentSecret,
        cm.payBag,
        remoteNodeInfo
      )
      val add1 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(35000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )

      // Need this to put something into cm.all so we can accept up to maxInChannelHtlcs parts
      val hcs1 = makeHostedCommits(nodeId = a, alias = "local-channel")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Receiving
      )
      WAIT_UNTIL_TRUE(fsm.data == null)

      val add2 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(35000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add3 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(30000L),
        totalAmount = MilliSatoshi(200000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )

      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Receiving
      )
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = add3 :: add1 :: add2 :: Nil
      )

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      WAIT_UNTIL_TRUE(
        fsm.data.asInstanceOf[IncomingRevealed].preimage == preimage
      )
      WAIT_UNTIL_TRUE(
        cm.getPreimageMemo.get(invoice.paymentHash).get == preimage
      )
      WAIT_UNTIL_TRUE(
        cm.payBag
          .getPaymentInfo(invoice.paymentHash)
          .get
          .status == PaymentStatus.SUCCEEDED
      )

      // Suppose user has restarted an app with only one part resolved in channels

      val fsm2 = new IncomingPaymentReceiver(add1.fullTag, cm)
      assert(fsm2.state == IncomingPaymentProcessor.Receiving)
      assert(fsm2.data == null)

      fsm2 doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)

      WAIT_UNTIL_TRUE(
        fsm2.state == IncomingPaymentProcessor.Finalizing
      )
      WAIT_UNTIL_TRUE(
        fsm2.data.asInstanceOf[IncomingRevealed].preimage == preimage
      )
      WAIT_UNTIL_TRUE(
        cm.getPreimageMemo.get(invoice.paymentHash).get == preimage
      )
      WAIT_UNTIL_TRUE(
        cm.payBag
          .getPaymentInfo(invoice.paymentHash)
          .get
          .received == MilliSatoshi(100000L)
      ) // Original amount is retained
    }

    test("Fail multipart incoming payment when we run out of slots") {
      LNParams.secret = WalletSecret.random()
      val remoteNodeInfo =
        RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
      val (_, _, Seq(paymentSecret), cm) =
        makeChannelMasterWithBasicGraph(Seq(randomBytes32))

      // Need this to put something into cm.all so we can accept up to maxInChannelHtlcs parts
      val hcs1 = makeHostedCommits(nodeId = a, alias = "local-channel")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val preimage = randomBytes32
      val invoice = recordIncomingPaymentToFakeNodeId(
        amount = Some(MilliSatoshi(100000L)),
        preimage,
        paymentSecret,
        cm.payBag,
        remoteNodeInfo
      )
      val add1 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add2 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add3 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add4 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add5 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add6 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add7 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add8 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add9 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add10 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(1000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )

      val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)
      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Receiving
      )
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in =
          add1 :: add2 :: add3 :: add4 :: add5 :: add6 :: add7 :: add8 :: add9 :: add10 :: Nil
      )
      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[IncomingAborted].failure.isEmpty)
    }

    test(
      "Do not react to incoming payment with same hash, but different secret"
    ) {
      LNParams.secret = WalletSecret.random()
      val remoteNodeInfo =
        RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
      val (_, _, Seq(paymentSecret), cm) =
        makeChannelMasterWithBasicGraph(Seq(randomBytes32))

      // Need this to put something into cm.all so we can accept up to maxInChannelHtlcs parts
      val hcs1 = makeHostedCommits(nodeId = a, alias = "local-channel")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val preimage = randomBytes32
      val invoice = recordIncomingPaymentToFakeNodeId(
        amount = Some(MilliSatoshi(100000L)),
        preimage,
        paymentSecret,
        cm.payBag,
        remoteNodeInfo
      )
      val add1 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(35000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add2 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(35000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add3 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(30000L),
        totalAmount = MilliSatoshi(200000L),
        invoice.paymentHash,
        randomBytes32,
        remoteNodeInfo,
        cm
      ) // Different secret

      val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = add3 :: add1 :: add2 :: Nil
      )

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Receiving
      )
      WAIT_UNTIL_TRUE(fsm.data == null)

      val add4 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(40000L),
        totalAmount = MilliSatoshi(200000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      ) // A correct one
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = add3 :: add1 :: add4 :: add2 :: Nil
      )

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      WAIT_UNTIL_TRUE(
        fsm.data.asInstanceOf[IncomingRevealed].preimage == preimage
      )
      WAIT_UNTIL_TRUE(
        cm.getPreimageMemo.get(invoice.paymentHash).get == preimage
      )
      WAIT_UNTIL_TRUE(
        cm.payBag
          .getPaymentInfo(invoice.paymentHash)
          .get
          .received == MilliSatoshi(110000)
      ) // Sender has sent a bit more

      fsm doProcess makeInFlightPayments(out = Nil, in = add3 :: Nil)
      WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.Shutdown)
    }

    test("Fail an unknown payment right away") {
      LNParams.secret = WalletSecret.random()
      val remoteNodeInfo =
        RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Seq(randomBytes32))

      val unknownHash = randomBytes32
      val unknownSecret = randomBytes32
      val add1 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(100000),
        totalAmount = MilliSatoshi(100000),
        unknownHash,
        unknownSecret,
        remoteNodeInfo,
        cm
      )
      val add2 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(200000),
        totalAmount = MilliSatoshi(200000),
        unknownHash,
        unknownSecret,
        remoteNodeInfo,
        cm
      )
      val add3 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(300000),
        totalAmount = MilliSatoshi(400000),
        unknownHash,
        unknownSecret,
        remoteNodeInfo,
        cm
      )

      val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = add3 :: add1 :: add2 :: Nil
      )

      fsm doProcess IncomingPaymentProcessor.CMDTimeout
      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[IncomingAborted].failure.isEmpty)

      // All parts have been cleared in channels
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
      WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.Shutdown)
    }

    test("Fail if one of parts is too close to chain tip") {
      LNParams.secret = WalletSecret.random()
      val remoteNodeInfo =
        RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
      val (_, _, Seq(paymentSecret), cm) =
        makeChannelMasterWithBasicGraph(Seq(randomBytes32))

      // Need this to put something into cm.all so we can accept up to maxInChannelHtlcs parts
      val hcs1 = makeHostedCommits(nodeId = a, alias = "local-channel")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val preimage = randomBytes32
      val invoice = recordIncomingPaymentToFakeNodeId(
        amount = Some(MilliSatoshi(100000L)),
        preimage,
        paymentSecret,
        cm.payBag,
        remoteNodeInfo
      )
      val add1 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(35000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add2 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(35000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm,
        cltvDelta = 71
      ) // One block too close
      val add3 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(30000L),
        totalAmount = MilliSatoshi(200000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )

      val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = add3 :: add1 :: add2 :: Nil
      )

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      WAIT_UNTIL_TRUE(fsm.data.asInstanceOf[IncomingAborted].failure.isEmpty)
    }

    test("Do not reveal a preimage on FSM entering failed state") {
      LNParams.secret = WalletSecret.random()
      val remoteNodeInfo =
        RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1")
      val (_, _, Seq(paymentSecret), cm) =
        makeChannelMasterWithBasicGraph(Seq(randomBytes32))

      // Need this to put something into cm.all so we can accept up to maxInChannelHtlcs parts
      val hcs1 = makeHostedCommits(nodeId = a, alias = "local-channel")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val preimage = randomBytes32
      val invoice = recordIncomingPaymentToFakeNodeId(
        amount = Some(MilliSatoshi(100000L)),
        preimage,
        paymentSecret,
        cm.payBag,
        remoteNodeInfo
      )
      val add1 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(35000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add2 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(35000L),
        totalAmount = MilliSatoshi(100000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )
      val add3 = makeRemoteAddToFakeNodeId(
        partAmount = MilliSatoshi(30000L),
        totalAmount = MilliSatoshi(200000L),
        invoice.paymentHash,
        invoice.paymentSecret.get,
        remoteNodeInfo,
        cm
      )

      val fsm = new IncomingPaymentReceiver(add1.fullTag, cm)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: Nil)
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
      fsm doProcess IncomingPaymentProcessor.CMDTimeout
      // FSM asks ChannelMaster for in-flight payments on getting timeout message
      fsm doProcess makeInFlightPayments(out = Nil, in = add1 :: add2 :: Nil)
      // In a moment we actually receive the last part, but preimage is still not revelaed
      fsm doProcess makeInFlightPayments(
        out = Nil,
        in = add3 :: add1 :: add2 :: Nil
      )

      WAIT_UNTIL_TRUE(
        fsm.state == IncomingPaymentProcessor.Finalizing
      )
      WAIT_UNTIL_TRUE(
        fsm.data.asInstanceOf[IncomingAborted].failure.contains(PaymentTimeout)
      )

      // All parts have been cleared in channels
      fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
      WAIT_UNTIL_TRUE(fsm.state == IncomingPaymentProcessor.Shutdown)
    }
  }
}
