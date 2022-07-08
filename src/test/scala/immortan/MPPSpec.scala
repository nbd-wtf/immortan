package immortan

import scala.language.existentials
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.DescAndCapacity
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteUpdateFail}
import fr.acinq.eclair.wire._
import immortan.fsm._
import immortan.utils.ChannelUtils._
import immortan.utils.GraphUtils._
import immortan.utils.TestUtils._
import utest._

object MPPSpec extends TestSuite {
  val tests = Tests {
    test("Gradually reduce failed-at-amount") {
      val desc = ChannelDesc(3L, b, d)
      val capacity = MilliSatoshi(2000000L)
      val failedAt = MilliSatoshi(200000L)
      val stamp = System.currentTimeMillis
      val fail = Map(
        DescAndCapacity(desc, capacity) -> StampedChannelFailed(failedAt, stamp)
      )
      val data1 = OutgoingPaymentMasterData(
        TrampolineRoutingStates(Map.empty),
        paymentSenders = Map.empty,
        chanFailedAtAmount = fail
      )
      assert(
        data1
          .withFailuresReduced(
            stamp + LNParams.failedChanRecoveryMsec.toLong / 2
          )
          .chanFailedAtAmount
          .head
          ._2
          .amount == failedAt + (capacity - failedAt) / 2
      )
      assert(
        data1
          .withFailuresReduced(stamp + LNParams.failedChanRecoveryMsec.toLong)
          .chanFailedAtAmount
          .isEmpty
      )
    }

    test("Split between direct and non-direct channel") {
      LNParams.secret = WalletSecret.random()
      val (normalStore, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      // Add a US -> C -> A channel

      val channelCAAnn = makeAnnouncement(5L, c, a)
      val updateCAFromC = makeUpdate(
        5L,
        c,
        a,
        MilliSatoshi(1L),
        10,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = MilliSatoshi(100000000L)
      )
      val updateCAFromA = makeUpdate(
        5L,
        a,
        c,
        MilliSatoshi(1L),
        10,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = MilliSatoshi(100000000L)
      )

      val addChannelAnnouncementNewSqlPQ =
        normalStore.db.makePreparedQuery(normalStore.announceTable.newSql)
      val addChannelUpdateByPositionNewSqlPQ =
        normalStore.db.makePreparedQuery(normalStore.updateTable.newSql)
      val addChannelUpdateByPositionUpdSqlPQ =
        normalStore.db.makePreparedQuery(normalStore.updateTable.updSQL)

      normalStore.addChannelAnnouncement(
        channelCAAnn,
        addChannelAnnouncementNewSqlPQ
      )
      normalStore.addChannelUpdateByPosition(
        updateCAFromC,
        addChannelUpdateByPositionNewSqlPQ,
        addChannelUpdateByPositionUpdSqlPQ
      )
      normalStore.addChannelUpdateByPosition(updateCAFromA)

      addChannelAnnouncementNewSqlPQ.close()
      addChannelUpdateByPositionNewSqlPQ.close()
      addChannelUpdateByPositionUpdSqlPQ.close()

      // Add direct channels with A and C

      val hcs1 = makeHostedCommits(
        nodeId = a,
        alias = "peer1"
      ) // Direct channel, but can't handle a whole amount
      val hcs2 = makeHostedCommits(
        nodeId = c,
        alias = "peer2"
      ) // Indirect channel to be used for the rest of the amount
      cm.chanBag.put(hcs1)
      cm.chanBag.put(hcs2)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val tag = FullPaymentTag(
        paymentHash = ByteVector32.One,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val send = SendMultiPart(
        tag,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(190000000L), MilliSatoshi(190000000L)),
        routerConf,
        targetNodeId = a,
        None,
        None,
        totalFeeReserve = MilliSatoshi(1900000L),
        allowedChans = cm.all.values.toSeq
      )

      cm.opm.createSenderFSM(
        Set(noopListener),
        tag
      ) // Create since FSM is missing
      cm.opm.createSenderFSM(
        null,
        tag
      ) // Disregard since FSM will be present

      // First created FSM has been retained
      WAIT_UNTIL_TRUE(
        cm.opm.data.paymentSenders(tag).listeners.head == noopListener
      )
      // Suppose this time we attempt a send when all channels are connected already
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      cm.opm process send

      WAIT_UNTIL_TRUE(
        cm.opm.data.paymentSenders(tag).data.inFlightParts.size == 2
      )
      val List(part1, part2) =
        cm.opm.data
          .paymentSenders(tag)
          .data
          .inFlightParts
          .toList
          .sortBy(_.route.fee)

      assert(part1.route.hops.size == 1) // US -> A
      assert(part1.route.fee == MilliSatoshi(0L))
      assert(part2.route.hops.size == 2) // US -> C -> A
      assert(part2.route.fee == MilliSatoshi(920L))

      LNParams.blockCount.set(10) // One more than receiver CLTV
      val out1 = cm.all.values
        .flatMap(Channel.chanAndCommitsOpt)
        .flatMap(_.commits.allOutgoing)
        .head
      cm.opm process RemoteUpdateFail(
        UpdateFailHtlc(out1.channelId, out1.id, randomBytes32.bytes),
        out1
      )

      WAIT_UNTIL_TRUE(
        cm.opm.data.paymentSenders(tag).state == PaymentStatus.ABORTED
      )
      WAIT_UNTIL_TRUE(
        cm.opm.data.paymentSenders(tag).data.inFlightParts.size == 1
      )
      LNParams.blockCount.set(0)
    }

    test("Split after no route found on first attempt") {
      LNParams.secret = WalletSecret.random()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val sendable1 =
        cm.opm
          .getSendable(cm.all.values, maxFee = MilliSatoshi(1000000L))
          .values
          .head
      assert(sendable1 == MilliSatoshi(99000000L))

      val tag = FullPaymentTag(
        paymentHash = ByteVector32.One,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val edgeDSFromD = makeEdge(
        6L,
        d,
        s,
        MilliSatoshi(1L),
        10,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = Long.MaxValue.msat
      )
      val send = SendMultiPart(
        tag,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(600000L), MilliSatoshi(600000L)),
        routerConf,
        targetNodeId = s,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq,
        assistedEdges = Set(edgeDSFromD)
      )

      cm.opm.createSenderFSM(Set(noopListener), tag)
      cm.opm process send

      // Our only channel is offline, sender FSM awaits for it to become operational
      WAIT_UNTIL_TRUE(
        cm.opm.data
          .paymentSenders(tag)
          .data
          .parts
          .values
          .head
          .asInstanceOf[WaitForChanOnline]
          .amount == send.split.myPart
      )
      WAIT_UNTIL_TRUE(
        cm.opm.data.paymentSenders(tag).data.parts.values.size == 1
      )

      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      // Channel got online, sending is resumed
      WAIT_UNTIL_TRUE(
        cm.opm.data.paymentSenders(tag).data.inFlightParts.size == 2
      )
      val List(part1, part2) =
        cm.opm.data.paymentSenders(tag).data.inFlightParts
      // First chosen route can not handle a second part so another route is chosen
      WAIT_UNTIL_TRUE(
        part2.route.hops.map(_.nodeId) == Seq(invalidPubKey, a, c, d)
      )
      WAIT_UNTIL_TRUE(
        part1.route.hops.map(_.nodeId) == Seq(invalidPubKey, a, b, d)
      )
      WAIT_UNTIL_TRUE(
        cm.opm.data.paymentSenders(tag).data.usedFee == MilliSatoshi(24L)
      )
      WAIT_UNTIL_TRUE(part2.cmd.firstAmount == MilliSatoshi(300012L))
      WAIT_UNTIL_TRUE(part1.cmd.firstAmount == MilliSatoshi(300012L))
    }

    test("Halt on excessive local failures") {
      LNParams.secret = WalletSecret.random()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
        .modify(_.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs)
        .setTo(0) // Payments will fail locally
      val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
        .modify(_.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs)
        .setTo(0) // Payments will fail locally
      val hcs3 = makeHostedCommits(nodeId = c, alias = "peer3")
        .modify(_.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs)
        .setTo(0) // Payments will fail locally
      cm.chanBag.put(hcs1)
      cm.chanBag.put(hcs2)
      cm.chanBag.put(hcs3)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val tag = FullPaymentTag(
        paymentHash = ByteVector32.One,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val edgeDSFromD = makeEdge(
        6L,
        d,
        s,
        MilliSatoshi(1),
        10,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = Long.MaxValue.msat
      )
      val send = SendMultiPart(
        tag,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(600000L), MilliSatoshi(600000L)),
        routerConf,
        targetNodeId = s,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq,
        assistedEdges = Set(edgeDSFromD)
      )

      var senderDataWhenFailed = List.empty[OutgoingPaymentSenderData]
      val failedListener: OutgoingPaymentListener =
        new OutgoingPaymentListener {
          override def wholePaymentFailed(
              data: OutgoingPaymentSenderData
          ): Unit =
            senderDataWhenFailed ::= data
        }

      // Payment is going to be split in two, both of them will fail locally
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))
      cm.opm.createSenderFSM(Set(failedListener), tag)
      cm.opm process send

      WAIT_UNTIL_TRUE(
        senderDataWhenFailed.size == 1
      ) // We have got exactly one failure event
      assert(
        senderDataWhenFailed.head.failures.head
          .asInstanceOf[LocalFailure]
          .status == PaymentFailure.RUN_OUT_OF_CAPABLE_CHANNELS
      )
      assert(senderDataWhenFailed.head.inFlightParts.isEmpty)
    }

    test("Correctly process failed-at-amount") {
      LNParams.secret = WalletSecret.random()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
      val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
      cm.chanBag.put(hcs1)
      cm.chanBag.put(hcs2)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val tag = FullPaymentTag(
        paymentHash = ByteVector32.One,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val edgeDSFromD = makeEdge(
        6L,
        d,
        s,
        MilliSatoshi(1),
        10,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = Long.MaxValue.msat
      )
      val send = SendMultiPart(
        tag,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(600000L), MilliSatoshi(600000L)),
        routerConf,
        targetNodeId = s,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq,
        assistedEdges = Set(edgeDSFromD)
      )

      val desc = ChannelDesc(3L, b, d)
      // B -> D channel is now unable to handle the first split, but still usable for second split
      cm.opm.data = cm.opm.data.copy(chanFailedAtAmount =
        Map(
          DescAndCapacity(desc, Long.MaxValue.msat) -> StampedChannelFailed(
            MilliSatoshi(200000L),
            System.currentTimeMillis
          )
        )
      )

      cm.opm.createSenderFSM(
        Set(noopListener),
        tag
      ) // Create since FSM is missing
      cm.opm.createSenderFSM(
        null,
        tag
      ) // Disregard since FSM will be present

      // First created FSM has been retained
      WAIT_UNTIL_TRUE(
        cm.opm.data.paymentSenders(tag).listeners.head == noopListener
      )
      // Suppose this time we attempt a send when all channels are connected already
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      cm.opm.clearFailures = false
      cm.opm process send

      WAIT_UNTIL_TRUE {
        val parts = cm.opm.data.paymentSenders(tag).data.parts.values.collect {
          case inFlight: WaitForRouteOrInFlight => inFlight
        }
        assert(
          cm.opm.data
            .paymentSenders(tag)
            .feeLeftover == send.totalFeeReserve - parts
            .flatMap(_.flight)
            .map(_.route.fee)
            .sum
        )
        // Initial split was 300k/300k, but one of routes has previously failed at 200k so we need to split further
        assert(
          List(
            MilliSatoshi(150000L),
            MilliSatoshi(150000L),
            MilliSatoshi(300000L)
          ).sorted == parts
            .map(_.amount)
            .toList
            .sorted
        )
        cm.opm.data.paymentSenders(tag).data.parts.size == 3
      }
    }

    test("Correctly process fulfilled payment") {
      LNParams.secret = WalletSecret.random()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
      val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
      cm.chanBag.put(hcs1)
      cm.chanBag.put(hcs2)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val preimage = ByteVector32.One
      val hash = Crypto.sha256(preimage)

      val tag = FullPaymentTag(
        paymentHash = hash,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val edgeDSFromD = makeEdge(
        6L,
        d,
        s,
        MilliSatoshi(1L),
        10,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = Long.MaxValue.msat
      )
      val send = SendMultiPart(
        tag,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(600000L), MilliSatoshi(600000L)),
        routerConf,
        targetNodeId = s,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq,
        assistedEdges = Set(edgeDSFromD)
      )

      val desc = ChannelDesc(3L, b, d)
      // B -> D channel is now unable to handle the first split, but still usable for second split
      cm.opm.data = cm.opm.data.copy(chanFailedAtAmount =
        Map(
          DescAndCapacity(desc, Long.MaxValue.msat) -> StampedChannelFailed(
            MilliSatoshi(200000L),
            System.currentTimeMillis
          )
        )
      )

      var results = List.empty[OutgoingPaymentSenderData]
      val listener: OutgoingPaymentListener = new OutgoingPaymentListener {
        override def gotFirstPreimage(
            data: OutgoingPaymentSenderData,
            fulfill: RemoteFulfill
        ): Unit = results ::= data
        override def wholePaymentSucceeded(
            data: OutgoingPaymentSenderData
        ): Unit = cm.opm.removeSenderFSM(data.cmd.fullTag)
      }

      cm.opm.createSenderFSM(
        Set(listener),
        tag
      ) // Create since FSM is missing
      cm.opm.createSenderFSM(
        null,
        tag
      ) // Disregard since FSM will be present

      // First created FSM has been retained
      WAIT_UNTIL_TRUE(
        cm.opm.data.paymentSenders(tag).listeners.head == listener
      )
      // Suppose this time we attempt a send when all channels are connected already
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      cm.opm.clearFailures = false
      cm.opm process send

      WAIT_UNTIL_RESULT {
        val List(p1, p2, p3) =
          cm.opm.data.paymentSenders(tag).data.inFlightParts.toList
        cm.opm process RemoteFulfill(
          UpdateAddHtlc(
            null,
            1,
            MilliSatoshi(0L),
            hash,
            CltvExpiry(100L),
            p1.cmd.packetAndSecrets.packet,
            null
          ),
          preimage
        )
        cm.opm process RemoteFulfill(
          UpdateAddHtlc(
            null,
            1,
            MilliSatoshi(0L),
            hash,
            CltvExpiry(100L),
            p2.cmd.packetAndSecrets.packet,
            null
          ),
          preimage
        )
        cm.opm process RemoteFulfill(
          UpdateAddHtlc(
            null,
            1,
            MilliSatoshi(0L),
            hash,
            CltvExpiry(100L),
            p3.cmd.packetAndSecrets.packet,
            null
          ),
          preimage
        )
        cm.opm.stateUpdated(InFlightPayments(Map.empty, Map.empty))
      }

      WAIT_UNTIL_TRUE {
        // Original data contains all successful routes
        assert(results.head.inFlightParts.size == 3)
        // FSM has been removed on payment success
        assert(cm.opm.data.paymentSenders.isEmpty)
        // We got a single revealed message
        results.size == 1
      }
    }

    test("Handle multiple competing payments") {
      LNParams.secret = WalletSecret.random()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
      val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
      cm.chanBag.put(hcs1)
      cm.chanBag.put(hcs2)
      cm.all = Channel.load(Set(cm), cm.chanBag)
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      val edgeDSFromD = makeEdge(
        6L,
        d,
        s,
        MilliSatoshi(1L),
        10,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = Long.MaxValue.msat
      )

      import scodec.bits._
      val tag1 = FullPaymentTag(
        paymentHash = ByteVector32(
          hex"0200000000000000000000000000000000000000000000000000000000000000"
        ),
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val send1 = SendMultiPart(
        tag1,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(300000L), MilliSatoshi(300000L)),
        routerConf,
        targetNodeId = s,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq,
        assistedEdges = Set(edgeDSFromD)
      )

      val tag2 = FullPaymentTag(
        paymentHash = ByteVector32(
          hex"0300000000000000000000000000000000000000000000000000000000000000"
        ),
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val send2 = SendMultiPart(
        tag2,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(600000L), MilliSatoshi(600000L)),
        routerConf,
        targetNodeId = s,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq,
        assistedEdges = Set(edgeDSFromD)
      )

      val tag3 = FullPaymentTag(
        paymentHash = ByteVector32(
          hex"0400000000000000000000000000000000000000000000000000000000000000"
        ),
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val send3 = SendMultiPart(
        tag3,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(200000L), MilliSatoshi(200000L)),
        routerConf,
        targetNodeId = s,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq,
        assistedEdges = Set(edgeDSFromD)
      )

      cm.opm.createSenderFSM(Set(noopListener), tag1)
      cm.opm process send1

      cm.opm.createSenderFSM(Set(noopListener), tag2)
      cm.opm process send2

      cm.opm.createSenderFSM(Set(noopListener), tag3)
      cm.opm process send3

      WAIT_UNTIL_TRUE {
        // First one enjoys full channel capacity
        val ws1 = cm.opm.data.paymentSenders(tag1).data.parts.values.collect {
          case inFlight: WaitForRouteOrInFlight => inFlight
        }
        assert(Set(MilliSatoshi(300000L)) == ws1.map(_.amount).toSet)

        // Second one had to be split to get through
        val ws2 = cm.opm.data.paymentSenders(tag2).data.parts.values.collect {
          case inFlight: WaitForRouteOrInFlight => inFlight
        }
        assert(
          Set(
            MilliSatoshi(150000L),
            MilliSatoshi(150000L),
            MilliSatoshi(300000L)
          ) == ws2.map(_.amount).toSet
        )

        // Third one has been knocked out
        cm.opm.data.paymentSenders(tag3).state == PaymentStatus.ABORTED
      }
    }

    test("Fail on local timeout") {
      LNParams.secret = WalletSecret.random()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
      val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
      cm.chanBag.put(hcs1)
      cm.chanBag.put(hcs2)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val preimage = ByteVector32.One
      val hash = Crypto.sha256(preimage)

      val tag = FullPaymentTag(
        paymentHash = hash,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val edgeDSFromD = makeEdge(
        6L,
        d,
        s,
        MilliSatoshi(1L),
        10,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = Long.MaxValue.msat
      )
      val send = SendMultiPart(
        tag,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(600000L), MilliSatoshi(600000L)),
        routerConf,
        targetNodeId = s,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq,
        assistedEdges = Set(edgeDSFromD)
      )

      var senderDataWhenFailed = List.empty[OutgoingPaymentSenderData]
      val listener: OutgoingPaymentListener = new OutgoingPaymentListener {
        override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit =
          senderDataWhenFailed ::= data
      }

      cm.opm.createSenderFSM(Set(listener), tag)
      cm.opm process send

      WAIT_UNTIL_TRUE {
        val parts1 = cm.opm.data.paymentSenders(tag).data.parts.values
        // Our only channel is offline, sender FSM awaits for it to become operational
        assert(
          parts1.head
            .asInstanceOf[WaitForChanOnline]
            .amount == send.split.myPart
        )
        parts1.size == 1
      }

      cm.opm.data.paymentSenders(tag) doProcess OutgoingPaymentMaster.CMDAbort

      WAIT_UNTIL_TRUE {
        assert(senderDataWhenFailed.head.parts.isEmpty)
        assert(
          senderDataWhenFailed.head.failures.head
            .asInstanceOf[LocalFailure]
            .status == PaymentFailure.TIMED_OUT
        )
        senderDataWhenFailed.size == 1
      }
    }

    test("Halt fast on terminal failure") {
      LNParams.secret = WalletSecret.random()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      LNParams.blockCount.set(Int.MaxValue)

      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
      val hcs2 = makeHostedCommits(nodeId = b, alias = "peer1")
      val hcs3 = makeHostedCommits(nodeId = c, alias = "peer1")
      cm.chanBag.put(hcs1)
      cm.chanBag.put(hcs2)
      cm.chanBag.put(hcs3)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val tag = FullPaymentTag(
        paymentHash = ByteVector32.One,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val edgeDSFromD = makeEdge(
        6L,
        d,
        s,
        MilliSatoshi(1),
        10,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = Long.MaxValue.msat
      )
      val send = SendMultiPart(
        tag,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(600000L), MilliSatoshi(600000L)),
        routerConf,
        targetNodeId = s,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq,
        assistedEdges = Set(edgeDSFromD)
      )

      var senderDataWhenFailed = List.empty[OutgoingPaymentSenderData]
      val failedListener: OutgoingPaymentListener =
        new OutgoingPaymentListener {
          override def wholePaymentFailed(
              data: OutgoingPaymentSenderData
          ): Unit =
            senderDataWhenFailed ::= data
        }

      // Payment is going to be split in two, both of them will fail locally
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))
      cm.opm.createSenderFSM(Set(failedListener), tag)
      cm.opm process send

      WAIT_UNTIL_TRUE {
        assert(
          senderDataWhenFailed.size == 1
        ) // We have got exactly one failure event
        assert(
          senderDataWhenFailed.head.failures.head
            .asInstanceOf[LocalFailure]
            .status == PaymentFailure.PAYMENT_NOT_SENDABLE
        )
        senderDataWhenFailed.head.inFlightParts.isEmpty
      }

      LNParams.blockCount.set(0)
    }

    test("Smaller part takes disproportionally larger fee from reserve") {
      LNParams.secret = WalletSecret.random()
      val (_, _, _, cm) = makeChannelMasterWithBasicGraph(Nil)

      val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
      cm.chanBag.put(hcs1)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val tag = FullPaymentTag(
        paymentHash = ByteVector32.One,
        paymentSecret = ByteVector32.One,
        tag = PaymentTagTlv.LOCALLY_SENT
      )
      val send = SendMultiPart(
        tag,
        Left(CltvExpiry(9)),
        SplitInfo(MilliSatoshi(600000L), MilliSatoshi(600000L)),
        routerConf,
        targetNodeId = d,
        None,
        None,
        totalFeeReserve = MilliSatoshi(6000L),
        allowedChans = cm.all.values.toSeq
      )

      cm.opm.createSenderFSM(Set(noopListener), tag)
      // Suppose this time we attempt a send when all channels are connected already
      cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.Open))

      cm.pf process PathFinder.CMDLoadGraph
      cm.pf process makeUpdate(
        3L,
        b,
        d,
        MilliSatoshi(5950L),
        100,
        cltvDelta = CltvExpiryDelta(144),
        minHtlc = MilliSatoshi(10L),
        maxHtlc = MilliSatoshi(500000L)
      )

      cm.opm process send

      WAIT_UNTIL_TRUE {
        val List(part1, part2) =
          cm.opm.data.paymentSenders(tag).data.parts.values.collect {
            case inFlight: WaitForRouteOrInFlight => inFlight
          }
        assert(
          part2.flight.get.route.fee == MilliSatoshi(8L)
        ) // One part takes a very cheap route, but that route can't handle the second part
        assert(
          part1.flight.get.route.fee == MilliSatoshi(5984L)
        ) // Another route is very expensive, but we can afford it because first part took very little, so we are still within fee bounds for a payment as a whole
        part1.amount == part2.amount
      }
    }
  }
}
