package immortan

import immortan.fsm._
import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.utils.TestUtils._
import immortan.utils.GraphUtils._
import com.softwaremill.quicklens._
import immortan.utils.ChannelUtils._
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.CMD_SOCKET_OFFLINE
import fr.acinq.eclair.router.Graph.GraphStructure.DescAndCapacity
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteUpdateFail}
import fr.acinq.eclair.router.Router.ChannelDesc
import org.scalatest.funsuite.AnyFunSuite


class MPPSpec extends AnyFunSuite {
  test("Gradually reduce failed-at-amount") {
    val desc = ChannelDesc(ShortChannelId(3L), b, d)
    val capacity = 2000000L.msat
    val failedAt = 200000L.msat
    val stamp = System.currentTimeMillis
    val fail = Map(DescAndCapacity(desc,capacity) -> StampedChannelFailed(failedAt, stamp))
    val data1 = OutgoingPaymentMasterData(payments = Map.empty, chanFailedAtAmount = fail)
    assert(data1.withFailuresReduced(stamp + 150 * 1000L).chanFailedAtAmount.head._2.amount == failedAt + (capacity - failedAt) / 2)
    assert(data1.withFailuresReduced(stamp + 300 * 1000L).chanFailedAtAmount.isEmpty)
  }

  test("Split between direct and non-direct channel") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (normalStore, _, cm) = makeChannelMasterWithBasicGraph

    // Add a US -> C -> A channel

    val channelCAAnn = makeAnnouncement(5L, c, a)
    val updateCAFromC = makeUpdate(ShortChannelId(5L), c, a, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 100000000L.msat)
    val updateCAFromA = makeUpdate(ShortChannelId(5L), a, c, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 100000000L.msat)

    val addChannelAnnouncementNewSqlPQ = normalStore.db.makePreparedQuery(normalStore.announceTable.newSql)
    val addChannelUpdateByPositionNewSqlPQ = normalStore.db.makePreparedQuery(normalStore.updateTable.newSql)
    val addChannelUpdateByPositionUpdSqlPQ = normalStore.db.makePreparedQuery(normalStore.updateTable.updSQL)

    normalStore.addChannelAnnouncement(channelCAAnn, addChannelAnnouncementNewSqlPQ)
    normalStore.addChannelUpdateByPosition(updateCAFromC, addChannelUpdateByPositionNewSqlPQ, addChannelUpdateByPositionUpdSqlPQ)
    normalStore.addChannelUpdateByPosition(updateCAFromA)

    addChannelAnnouncementNewSqlPQ.close
    addChannelUpdateByPositionNewSqlPQ.close
    addChannelUpdateByPositionUpdSqlPQ.close

    // Add direct channels with A and C

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1") // Direct channel, but can't handle a whole amount
    val hcs2 = makeHostedCommits(nodeId = c, alias = "peer2") // Indirect channel to be used for the rest of the amount
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val send = SendMultiPart(tag, Left(CltvExpiry(9)), SplitInfo(190000000L.msat, 190000000L.msat), routerConf,
      targetNodeId = a, totalFeeReserve = 1900000L.msat, allowedChans = cm.all.values.toSeq)

    cm.opm process CreateSenderFSM(Set(noopListener), tag) // Create since FSM is missing
    cm.opm process CreateSenderFSM(null, tag) // Disregard since FSM will be present

    // First created FSM has been retained
    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).listeners.head == noopListener)
    // Suppose this time we attempt a send when all channels are connected already
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    cm.opm process send

    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).data.inFlightParts.size == 2)
    val List(part1, part2) = cm.opm.data.payments(tag).data.inFlightParts.toList.sortBy(_.route.fee)

    assert(part1.route.hops.size == 1) // US -> A
    assert(part1.route.fee == 0L.msat)
    assert(part2.route.hops.size == 2) // US -> C -> A
    assert(part2.route.fee == 920L.msat)

    LNParams.blockCount.set(10) // One more than receiver CLTV
    val out1 = cm.all.values.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing).head
    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out1.channelId, out1.id, randomBytes32.bytes), out1)

    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).state == PaymentStatus.ABORTED)
    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).data.inFlightParts.size == 1)
    LNParams.blockCount.set(0)
  }

  test("Split after no route found on first attempt") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.pf.debugMode = true

    val sendable1 = cm.opm.getSendable(cm.all.values, maxFee = 1000000L.msat).values.head
    assert(sendable1 == 99000000L.msat)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, Left(CltvExpiry(9)), SplitInfo(600000L.msat, 600000L.msat), routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    cm.opm process CreateSenderFSM(Set(noopListener), tag)
    cm.opm process send

    // Our only channel is offline, sender FSM awaits for it to become operational
    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).data.parts.values.head.asInstanceOf[WaitForChanOnline].amount == send.split.myPart)
    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).data.parts.values.size == 1)

    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    // Channel got online so part now awaits for a route, but graph is not loaded (debug mode = true)
    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).data.parts.values.head.asInstanceOf[WaitForRouteOrInFlight].amount == send.split.myPart)
    WAIT_UNTIL_TRUE(cm.pf.extraEdgesMap.size == 1)

    // Payment is not yet in channel, but it is waiting in sender so amount without fees is taken into account
    val sendable2 = cm.opm.getSendable(cm.all.values, maxFee = 1000000L.msat).values.head
    assert(sendable2 == sendable1 - send.split.myPart)

    cm.pf process PathFinder.CMDLoadGraph

    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).data.inFlightParts.size == 2)
    val List(part1, part2) = cm.opm.data.payments(tag).data.inFlightParts
    // First chosen route can not handle a second part so another route is chosen
    WAIT_UNTIL_TRUE(part1.route.hops.map(_.nodeId) == Seq(invalidPubKey, a, c, d))
    WAIT_UNTIL_TRUE(part2.route.hops.map(_.nodeId) == Seq(invalidPubKey, a, b, d))
    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).data.usedFee == 24L.msat)
    WAIT_UNTIL_TRUE(part1.cmd.firstAmount == 300012L.msat)
    WAIT_UNTIL_TRUE(part2.cmd.firstAmount == 300012L.msat)
  }

  test("Halt on excessive local failures") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1").modify(_.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs).setTo(0) // Payments will fail locally
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2").modify(_.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs).setTo(0) // Payments will fail locally
    val hcs3 = makeHostedCommits(nodeId = c, alias = "peer3").modify(_.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs).setTo(0) // Payments will fail locally
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.chanBag.put(hcs3)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, Left(CltvExpiry(9)), SplitInfo(600000L.msat, 600000L.msat),  routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    var senderDataWhenFailed = List.empty[OutgoingPaymentSenderData]
    val failedListener: OutgoingPaymentListener = new OutgoingPaymentListener {
      override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = senderDataWhenFailed ::= data
    }

    // Payment is going to be split in two, both of them will fail locally
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    cm.opm process CreateSenderFSM(Set(failedListener), tag)
    cm.opm process send

    WAIT_UNTIL_TRUE(senderDataWhenFailed.size == 1) // We have got exactly one failure event
    assert(senderDataWhenFailed.head.failures.head.asInstanceOf[LocalFailure].status == PaymentFailure.RUN_OUT_OF_CAPABLE_CHANNELS)
    assert(senderDataWhenFailed.head.inFlightParts.isEmpty)
  }

  test("Switch channel on first one becoming SLEEPING") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    cm.pf.debugMode = true

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, Left(CltvExpiry(9)), SplitInfo(400000L.msat, 400000L.msat), routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    cm.opm process CreateSenderFSM(Set(noopListener), tag)
    cm.opm process send

    // The only channel has been chosen because it is OPEN, but graph is not ready yet
    val wait1 = WAIT_UNTIL_RESULT(cm.opm.data.payments(tag).data.parts.values.head.asInstanceOf[WaitForRouteOrInFlight])
    val originalChosenCnc = cm.all.values.flatMap(Channel.chanAndCommitsOpt).find(_.commits.channelId == wait1.cnc.commits.channelId).get
    assert(cm.opm.data.payments(tag).data.parts.size == 1)
    assert(wait1.flight.isEmpty)

    // In the meantime two new channels are added to the system
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2", toLocal = 300000L.msat)
    val hcs3 = makeHostedCommits(nodeId = c, alias = "peer3", toLocal = 300000L.msat)
    cm.chanBag.put(hcs2)
    cm.chanBag.put(hcs3)

    cm.all ++= Channel.load(Set(cm), cm.chanBag) - originalChosenCnc.commits.channelId // Add two new channels
    val senderData1 = cm.opm.data.payments(tag).data.modify(_.cmd.allowedChans).setTo(cm.all.values.toSeq) // also update FSM
    cm.opm.data.payments(tag).data = senderData1

    // Graph becomes ready, but chosen chan has gone offline in a meantime
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    originalChosenCnc.chan process CMD_SOCKET_OFFLINE
    cm.pf process PathFinder.CMDLoadGraph

    WAIT_UNTIL_TRUE {
      // Payment gets split in two because no remote hop in route can handle a whole and both parts end up with second channel
      val List(part1, part2) = cm.opm.data.payments(tag).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
      assert(part1.cnc.commits.channelId != originalChosenCnc.commits.channelId)
      assert(part2.cnc.commits.channelId != originalChosenCnc.commits.channelId)
      assert(cm.opm.data.payments(tag).data.inFlightParts.size == 2)
      assert(cm.opm.data.payments(tag).data.parts.size == 2)
      part1.amount + part2.amount == send.split.myPart
    }
  }

  test("Correctly process failed-at-amount") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, Left(CltvExpiry(9)), SplitInfo(600000L.msat, 600000L.msat), routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    val desc = ChannelDesc(ShortChannelId(3L), b, d)
    // B -> D channel is now unable to handle the first split, but still usable for second split
    cm.opm.data = cm.opm.data.copy(chanFailedAtAmount = Map(DescAndCapacity(desc, Long.MaxValue.msat) -> StampedChannelFailed(200000L.msat, System.currentTimeMillis)))

    cm.opm process CreateSenderFSM(Set(noopListener), tag) // Create since FSM is missing
    cm.opm process CreateSenderFSM(null, tag) // Disregard since FSM will be present

    // First created FSM has been retained
    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).listeners.head == noopListener)
    // Suppose this time we attempt a send when all channels are connected already
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    cm.opm process send

    WAIT_UNTIL_TRUE {
      val parts = cm.opm.data.payments(tag).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
      assert(cm.opm.data.payments(tag).feeLeftover == send.totalFeeReserve - parts.flatMap(_.flight).map(_.route.fee).sum)
      // Initial split was 300k/300k, but one of routes has previously failed at 200k so we need to split further
      assert(List(150000L.msat, 150000L.msat, 300000L.msat).sorted == parts.map(_.amount).toList.sorted)
      cm.opm.data.payments(tag).data.parts.size == 3
    }
  }

  test("Correctly process fulfilled payment") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val preimage = ByteVector32.One
    val hash = Crypto.sha256(preimage)

    val tag = FullPaymentTag(paymentHash = hash, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, Left(CltvExpiry(9)), SplitInfo(600000L.msat, 600000L.msat), routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    val desc = ChannelDesc(ShortChannelId(3L), b, d)
    // B -> D channel is now unable to handle the first split, but still usable for second split
    cm.opm.data = cm.opm.data.copy(chanFailedAtAmount = Map(DescAndCapacity(desc, Long.MaxValue.msat) -> StampedChannelFailed(200000L.msat, System.currentTimeMillis)))

    var results = List.empty[OutgoingPaymentSenderData]
    val listener: OutgoingPaymentListener = new OutgoingPaymentListener {
      override def gotFirstPreimage(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill): Unit = results ::= data
      override def wholePaymentSucceeded(data: OutgoingPaymentSenderData): Unit = cm.opm process RemoveSenderFSM(data.cmd.fullTag)
    }

    cm.opm process CreateSenderFSM(Set(listener), tag) // Create since FSM is missing
    cm.opm process CreateSenderFSM(null, tag) // Disregard since FSM will be present

    // First created FSM has been retained
    WAIT_UNTIL_TRUE(cm.opm.data.payments(tag).listeners.head == listener)
    // Suppose this time we attempt a send when all channels are connected already
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    cm.opm process send

    WAIT_UNTIL_RESULT {
      val List(p1, p2, p3) = cm.opm.data.payments(tag).data.inFlightParts.toList
      cm.opm process RemoteFulfill(UpdateAddHtlc(null, 1, null, hash, null, p1.cmd.packetAndSecrets.packet, null), preimage)
      cm.opm process RemoteFulfill(UpdateAddHtlc(null, 1, null, hash, null, p2.cmd.packetAndSecrets.packet, null), preimage)
      cm.opm process RemoteFulfill(UpdateAddHtlc(null, 1, null, hash, null, p3.cmd.packetAndSecrets.packet, null), preimage)
      cm.opm process InFlightPayments(Map.empty, Map.empty)
    }

    WAIT_UNTIL_TRUE {
      // Original data contains all successful routes
      assert(results.head.inFlightParts.size == 3)
      // FSM has been removed on payment success
      assert(cm.opm.data.payments.isEmpty)
      // We got a single revealed message
      results.size == 1
    }
  }

  test("Handle multiple competing payments") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)

    import scodec.bits._
    val tag1 = FullPaymentTag(paymentHash = ByteVector32(hex"0200000000000000000000000000000000000000000000000000000000000000"),
      paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val send1 = SendMultiPart(tag1, Left(CltvExpiry(9)), SplitInfo(300000L.msat, 300000L.msat), routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    val tag2 = FullPaymentTag(paymentHash = ByteVector32(hex"0300000000000000000000000000000000000000000000000000000000000000"),
      paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val send2 = SendMultiPart(tag2, Left(CltvExpiry(9)), SplitInfo(600000L.msat, 600000L.msat), routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    val tag3 = FullPaymentTag(paymentHash = ByteVector32(hex"0400000000000000000000000000000000000000000000000000000000000000"),
      paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val send3 = SendMultiPart(tag3, Left(CltvExpiry(9)), SplitInfo(200000L.msat, 200000L.msat), routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    cm.opm process CreateSenderFSM(Set(noopListener), tag1)
    cm.opm process send1

    cm.opm process CreateSenderFSM(Set(noopListener), tag2)
    cm.opm process send2

    cm.opm process CreateSenderFSM(Set(noopListener), tag3)
    cm.opm process send3

    WAIT_UNTIL_TRUE {
      // First one enjoys full channel capacity
      val ws1 = cm.opm.data.payments(tag1).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
      assert(Set(300000L.msat) == ws1.map(_.amount).toSet)

      // Second one had to be split to get through
      val ws2 = cm.opm.data.payments(tag2).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
      assert(Set(150000L.msat, 150000L.msat, 300000L.msat) == ws2.map(_.amount).toSet)

      // Third one has been knocked out
      cm.opm.data.payments(tag3).state == PaymentStatus.ABORTED
    }
  }

  test("Fail on local timeout") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val preimage = ByteVector32.One
    val hash = Crypto.sha256(preimage)

    val tag = FullPaymentTag(paymentHash = hash, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, Left(CltvExpiry(9)), SplitInfo(600000L.msat, 600000L.msat), routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    var senderDataWhenFailed = List.empty[OutgoingPaymentSenderData]
    val listener: OutgoingPaymentListener = new OutgoingPaymentListener {
      override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = senderDataWhenFailed ::= data
    }

    cm.opm process CreateSenderFSM(Set(listener), tag)
    cm.opm process send

    WAIT_UNTIL_TRUE {
      val parts1 = cm.opm.data.payments(tag).data.parts.values
      // Our only channel is offline, sender FSM awaits for it to become operational
      assert(parts1.head.asInstanceOf[WaitForChanOnline].amount == send.split.myPart)
      parts1.size == 1
    }

    cm.opm.data.payments(tag) doProcess OutgoingPaymentMaster.CMDAbort

    WAIT_UNTIL_TRUE {
      assert(senderDataWhenFailed.head.parts.isEmpty)
      assert(senderDataWhenFailed.head.failures.head.asInstanceOf[LocalFailure].status == PaymentFailure.TIMED_OUT)
      senderDataWhenFailed.size == 1
    }
  }

  test("Halt fast on terminal failure") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    LNParams.blockCount.set(Int.MaxValue)

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer1")
    val hcs3 = makeHostedCommits(nodeId = c, alias = "peer1")
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.chanBag.put(hcs3)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, Left(CltvExpiry(9)), SplitInfo(600000L.msat, 600000L.msat), routerConf, targetNodeId = s,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    var senderDataWhenFailed = List.empty[OutgoingPaymentSenderData]
    val failedListener: OutgoingPaymentListener = new OutgoingPaymentListener {
      override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = senderDataWhenFailed ::= data
    }

    // Payment is going to be split in two, both of them will fail locally
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    cm.opm process CreateSenderFSM(Set(failedListener), tag)
    cm.opm process send

    WAIT_UNTIL_TRUE {
      assert(senderDataWhenFailed.size == 1) // We have got exactly one failure event
      assert(senderDataWhenFailed.head.failures.head.asInstanceOf[LocalFailure].status == PaymentFailure.PAYMENT_NOT_SENDABLE)
      senderDataWhenFailed.head.inFlightParts.isEmpty
    }

    LNParams.blockCount.set(0)
  }

  test("Smaller part takes disproportionally larger fee from reserve") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val send = SendMultiPart(tag, Left(CltvExpiry(9)), SplitInfo(600000L.msat, 600000L.msat), routerConf, targetNodeId = d,
      totalFeeReserve = 6000L.msat, allowedChans = cm.all.values.toSeq)

    cm.opm process CreateSenderFSM(Set(noopListener), tag)
    // Suppose this time we attempt a send when all channels are connected already
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    cm.pf process PathFinder.CMDLoadGraph
    cm.pf process makeUpdate(ShortChannelId(3L), b, d, 5950L.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000L.msat)

    cm.opm process send

    WAIT_UNTIL_TRUE {
      val List(part1, part2) = cm.opm.data.payments(tag).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
      assert(part1.flight.get.route.fee == 8L.msat) // First part takes a very cheap route, but that route can't handle the second part
      assert(part2.flight.get.route.fee == 5984L.msat) // Another route is very expensive, but we can afford it because first part took very little, so we are still within fee bounds for a payment as a whole
      part1.amount == part2.amount
    }
  }
}
