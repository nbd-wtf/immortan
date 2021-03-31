package immortan

import immortan.fsm._
import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.utils.GraphUtils._
import com.softwaremill.quicklens._
import immortan.utils.ChannelUtils._
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.CMD_SOCKET_OFFLINE
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteUpdateFail}
import fr.acinq.eclair.router.Router.ChannelDesc
import org.scalatest.funsuite.AnyFunSuite


class MPPSpec extends AnyFunSuite {
  test("Split between direct and non-direct channel") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
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
    val send = SendMultiPart(tag, routerConf, targetNodeId = a, onionTotal = 190000000L.msat, actualTotal = 190000000L.msat,
      totalFeeReserve = 1900000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq)

    cm.opm process CreateSenderFSM(tag, noopListener) // Create since FSM is missing
    cm.opm process CreateSenderFSM(tag, null) // Disregard since FSM will be present

    synchronized(wait(200))
    // First created FSM has been retained
    assert(cm.opm.data.payments(tag).listener == noopListener)
    // Suppose this time we attempt a send when all channels are connected already
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    cm.opm process send
    synchronized(wait(200))

    val List(part1, part2) = cm.opm.data.payments(tag).data.inFlightParts.toList.sortBy(_.route.fee)

    assert(part1.route.hops.size == 1) // US -> A
    assert(part1.route.fee == 0L.msat)
    assert(part2.route.hops.size == 2) // US -> C -> A
    assert(part2.route.fee == 920L.msat)

    LNParams.blockCount.set(10) // One more than receiver CLTV
    val out1 = cm.allInChannelOutgoing.values.flatten.head
    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out1.channelId, out1.id, randomBytes32.bytes), out1)
    synchronized(wait(200))

    assert(cm.opm.data.payments(tag).state == PaymentStatus.ABORTED)
    assert(cm.opm.data.payments(tag).data.inFlightParts.size == 1)
    LNParams.blockCount.set(0)
  }

  test("Split after no route found on first attempt") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.pf.debugMode = true

    val sendable1 = cm.opm.getSendable(cm.all.values, maxFee = 1000000L.msat).values.head
    assert(sendable1 == 99000000L.msat)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, routerConf, targetNodeId = s, onionTotal = 600000L.msat, actualTotal = 600000L.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    cm.opm process CreateSenderFSM(tag, noopListener)
    cm.opm process send

    synchronized(wait(200L))
    val parts1 = cm.opm.data.payments(tag).data.parts.values
    // Our only channel is offline, sender FSM awaits for it to become operational
    assert(parts1.head.asInstanceOf[WaitForChanOnline].amount == send.actualTotal)
    assert(parts1.size == 1)

    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    synchronized(wait(200L))
    val parts2 = cm.opm.data.payments(tag).data.parts.values
    // Channel got online so part now awaits for a route, but graph is not loaded (debug mode = true)
    assert(parts2.head.asInstanceOf[WaitForRouteOrInFlight].amount == send.actualTotal)
    assert(cm.pf.extraEdgesMap.size == 1)

    // Payment is not yet in channel, but it is waiting in sender so amount without fees is taken into account
    val sendable2 = cm.opm.getSendable(cm.all.values, maxFee = 1000000L.msat).values.head
    assert(sendable2 == sendable1 - send.actualTotal)

    cm.pf process PathFinder.CMDLoadGraph
    synchronized(wait(500))

    val List(part1, part2) = cm.opm.data.payments(tag).data.inFlightParts
    // First chosen route can not handle a second part so another route is chosen
    assert(part1.route.hops.map(_.nodeId) == Seq(LNParams.format.keys.ourNodePubKey, a, c, d))
    assert(part2.route.hops.map(_.nodeId) == Seq(LNParams.format.keys.ourNodePubKey, a, b, d))
    assert(cm.opm.data.payments(tag).data.usedFee == 24L.msat)
    assert(part1.cmd.firstAmount == 300012L.msat)
    assert(part2.cmd.firstAmount == 300012L.msat)
  }

  test("Halt on excessive local failures") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1").modify(_.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs).setTo(0) // Payments will fail locally
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer1").modify(_.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs).setTo(0) // Payments will fail locally
    val hcs3 = makeHostedCommits(nodeId = c, alias = "peer1").modify(_.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs).setTo(0) // Payments will fail locally
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.chanBag.put(hcs3)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, routerConf, targetNodeId = s, onionTotal = 600000.msat, actualTotal = 600000.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    var failures = List.empty[OutgoingPaymentSenderData]
    val failedListener: OutgoingPaymentEvents = new OutgoingPaymentEvents {
      override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = failures ::= data
    }

    // Payment is going to be split in two, both of them will fail locally
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    cm.opm process CreateSenderFSM(tag, failedListener)
    cm.opm process send

    synchronized(wait(200))
    assert(failures.size == 1) // We have got exactly one failure event
    assert(cm.opm.data.payments(tag).data.failures.head.asInstanceOf[LocalFailure].status == PaymentFailure.RUN_OUT_OF_RETRY_ATTEMPTS)
    assert(cm.opm.data.payments(tag).state == PaymentStatus.ABORTED)
    assert(cm.opm.data.payments(tag).data.inFlightParts.isEmpty)
  }

  test("Switch channel on first one becoming SLEEPING") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    cm.pf.debugMode = true

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, routerConf, targetNodeId = s, onionTotal = 400000L.msat, actualTotal = 400000L.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    cm.opm process CreateSenderFSM(tag, noopListener)
    cm.opm process send

    synchronized(wait(200))
    // The only channel has been chosen because it is OPEN, but graph is not ready yet
    val wait1 = cm.opm.data.payments(tag).data.parts.values.head.asInstanceOf[WaitForRouteOrInFlight]
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
    synchronized(wait(200L))

    // Payment gets split in two because no remote hop in route can handle a whole and both parts end up with second channel
    val List(part1, part2) = cm.opm.data.payments(tag).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
    assert(part1.cnc.commits.channelId != originalChosenCnc.commits.channelId)
    assert(part2.cnc.commits.channelId != originalChosenCnc.commits.channelId)
    assert(cm.opm.data.payments(tag).data.inFlightParts.size == 2)
    assert(cm.opm.data.payments(tag).data.parts.size == 2)
    assert(part1.amount + part2.amount == send.actualTotal)
  }

  test("Correctly process failed-at-amount") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, routerConf, targetNodeId = s, onionTotal = 600000L.msat, actualTotal = 600000L.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    val desc = ChannelDesc(ShortChannelId(3L), b, d)
    // B -> D channel is now unable to handle the first split, but still usable for second split
    cm.opm.data = cm.opm.data.copy(chanFailedAtAmount = Map(desc -> 200000L.msat))

    cm.opm process CreateSenderFSM(tag, noopListener) // Create since FSM is missing
    cm.opm process CreateSenderFSM(tag, null) // Disregard since FSM will be present

    synchronized(wait(200))
    // First created FSM has been retained
    assert(cm.opm.data.payments(tag).listener == noopListener)
    // Suppose this time we attempt a send when all channels are connected already
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    cm.opm process send
    synchronized(wait(500))

    val parts = cm.opm.data.payments(tag).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
    assert(cm.opm.data.payments(tag).feeLeftover == send.totalFeeReserve - parts.flatMap(_.flight).map(_.route.fee).sum)
    // Initial split was 300k/300k, but one of routes has previously failed at 200k so we need to split further
    assert(Set(150000L.msat, 150000L.msat, 300000L.msat) == parts.map(_.amount).toSet)
    assert(cm.opm.data.payments(tag).data.parts.size == 3)
  }

  test("Correctly process fulfilled payment") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
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
    val send = SendMultiPart(tag, routerConf, targetNodeId = s, onionTotal = 600000L.msat, actualTotal = 600000L.msat, totalFeeReserve = 6000L.msat,
      targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    val desc = ChannelDesc(ShortChannelId(3L), b, d)
    // B -> D channel is now unable to handle the first split, but still usable for second split
    cm.opm.data = cm.opm.data.copy(chanFailedAtAmount = Map(desc -> 200000L.msat))

    var results = List.empty[OutgoingPaymentSenderData]
    val listener: OutgoingPaymentEvents = new OutgoingPaymentEvents {
      override def preimageRevealed(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill): Unit = results ::= data
    }

    cm.opm process CreateSenderFSM(tag, listener) // Create since FSM is missing
    cm.opm process CreateSenderFSM(tag, null) // Disregard since FSM will be present

    synchronized(wait(200))
    // First created FSM has been retained
    assert(cm.opm.data.payments(tag).listener == listener)
    // Suppose this time we attempt a send when all channels are connected already
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    cm.opm process send
    synchronized(wait(500))

    val List(p1, p2, p3) = cm.opm.data.payments(tag).data.inFlightParts.toList
    cm.opm process RemoteFulfill(preimage, UpdateAddHtlc(null, 1, null, hash, null, p1.cmd.packetAndSecrets.packet, null))
    synchronized(wait(200))
    cm.opm process RemoteFulfill(preimage, UpdateAddHtlc(null, 1, null, hash, null, p2.cmd.packetAndSecrets.packet, null))
    cm.opm process RemoteFulfill(preimage, UpdateAddHtlc(null, 1, null, hash, null, p3.cmd.packetAndSecrets.packet, null))
    synchronized(wait(200))
    // All in-flight parts have been cleared and won't interfere with used capacities
    assert(cm.opm.data.payments(tag).data.inFlightParts.isEmpty)
    // Original data contains all successful routes
    assert(results.head.inFlightParts.size == 3)
    // We only got a single revealed message
    assert(results.size == 1)
  }

  test("Handle multiple competing payments") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1L.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)

    import scodec.bits._
    val tag1 = FullPaymentTag(paymentHash = ByteVector32(hex"0200000000000000000000000000000000000000000000000000000000000000"), paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val send1 = SendMultiPart(tag1, routerConf, targetNodeId = s, onionTotal = 300000L.msat, actualTotal = 300000L.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    val tag2 = FullPaymentTag(paymentHash = ByteVector32(hex"0300000000000000000000000000000000000000000000000000000000000000"), paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val send2 = SendMultiPart(tag2, routerConf, targetNodeId = s, onionTotal = 600000L.msat, actualTotal = 600000L.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    val tag3 = FullPaymentTag(paymentHash = ByteVector32(hex"0400000000000000000000000000000000000000000000000000000000000000"), paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val send3 = SendMultiPart(tag3, routerConf, targetNodeId = s, onionTotal = 200000L.msat, actualTotal = 200000L.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    cm.opm process CreateSenderFSM(tag1, noopListener)
    cm.opm process send1

    cm.opm process CreateSenderFSM(tag2, noopListener)
    cm.opm process send2

    cm.opm process CreateSenderFSM(tag3, noopListener)
    cm.opm process send3

    synchronized(wait(500L))

    // First one enjoys full channel capacity
    val ws1 = cm.opm.data.payments(tag1).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
    assert(Set(300000L.msat) == ws1.map(_.amount).toSet)

    // Second one had to be split to get through
    val ws2 = cm.opm.data.payments(tag2).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
    assert(Set(150000L.msat, 150000L.msat, 300000L.msat) == ws2.map(_.amount).toSet)

    // Third one has been knocked out
    assert(cm.opm.data.payments(tag3).state == PaymentStatus.ABORTED)
  }

  test("Fail on local timeout") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
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
    val send = SendMultiPart(tag, routerConf, targetNodeId = s, onionTotal = 600000L.msat, actualTotal = 600000L.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    var results = List.empty[OutgoingPaymentSenderData]
    val listener: OutgoingPaymentEvents = new OutgoingPaymentEvents {
      override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = results ::= data
    }

    cm.opm process CreateSenderFSM(tag, listener)
    cm.opm process send

    synchronized(wait(200))
    val parts1 = cm.opm.data.payments(tag).data.parts.values
    // Our only channel is offline, sender FSM awaits for it to become operational
    assert(parts1.head.asInstanceOf[WaitForChanOnline].amount == send.actualTotal)
    assert(parts1.size == 1)

    cm.opm.data.payments(tag) doProcess OutgoingPaymentMaster.CMDAbort

    synchronized(wait(200))
    assert(cm.opm.data.payments(tag).data.parts.isEmpty)
    assert(cm.opm.data.payments(tag).state == PaymentStatus.ABORTED)
    assert(results.size == 1)
  }

  test("Halt fast on terminal failure") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
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
    val send = SendMultiPart(tag, routerConf, targetNodeId = s, onionTotal = 600000.msat, actualTotal = 600000.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq, assistedEdges = Set(edgeDSFromD))

    var failures = List.empty[OutgoingPaymentSenderData]
    val failedListener: OutgoingPaymentEvents = new OutgoingPaymentEvents {
      override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = failures ::= data
    }

    // Payment is going to be split in two, both of them will fail locally
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    cm.opm process CreateSenderFSM(tag, failedListener)
    cm.opm process send

    synchronized(wait(200))
    assert(failures.size == 1) // We have got exactly one failure event
    assert(cm.opm.data.payments(tag).data.failures.head.asInstanceOf[LocalFailure].status == PaymentFailure.PAYMENT_NOT_SENDABLE)
    assert(cm.opm.data.payments(tag).state == PaymentStatus.ABORTED)
    assert(cm.opm.data.payments(tag).data.inFlightParts.isEmpty)
    LNParams.blockCount.set(0)
  }

  test("Smaller part takes disproportionally larger fee from reserve") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val send = SendMultiPart(tag, routerConf, targetNodeId = d, onionTotal = 600000L.msat, actualTotal = 600000L.msat,
      totalFeeReserve = 6000L.msat, targetExpiry = CltvExpiry(9), allowedChans = cm.all.values.toSeq)

    cm.opm process CreateSenderFSM(tag, noopListener)
    // Suppose this time we attempt a send when all channels are connected already
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    cm.pf process PathFinder.CMDLoadGraph
    cm.pf process makeUpdate(ShortChannelId(3L), b, d, 5950L.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000L.msat)

    cm.opm process send
    synchronized(wait(500))

    val List(part1, part2) = cm.opm.data.payments(tag).data.parts.values.collect { case inFlight: WaitForRouteOrInFlight => inFlight }
    assert(part1.flight.get.route.fee == 8L.msat) // First part takes a very cheap route, but that route can't handle the second part
    assert(part2.flight.get.route.fee == 5984L.msat) // Another route is very expensive, but we can afford it because first part took very little, so we are still within fee bounds for a payment as a whole
    assert(part1.amount == part2.amount)
  }
}
