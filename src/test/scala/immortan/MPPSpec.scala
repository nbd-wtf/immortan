package immortan

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.router.Router.RouterConf
import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.eclair.wire.{FullPaymentTag, GenericTlv, OnionTlv, PaymentTagTlv}
import immortan.crypto.Tools.none
import immortan.fsm.{CreateSenderFSM, LocalFailure, OutgoingPaymentEvents, OutgoingPaymentSenderData, PaymentFailure, SendMultiPart, WaitForChanOnline, WaitForRouteOrInFlight}
import immortan.sqlite._
import immortan.utils.GraphUtils._
import immortan.utils.SQLiteUtils._
import immortan.utils.ChannelUtils._
import org.scalatest.funsuite.AnyFunSuite

// TODO: timeout
// TODO: multiple competing payments
// TODO: smaller part taking disproportionally larger fee out of reserve

class MPPSpec extends AnyFunSuite {
  test("Split between direct and non-direct channel") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val (normalStore, _, cm) = makeChannelMasterWithBasicGraph

    // Add a US -> C -> A channel

    val channelCAAnn = makeAnnouncement(5L, c, a)
    val updateCAFromC = makeUpdate(ShortChannelId(5L), c, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 100000000L.msat)
    val updateCAFromA = makeUpdate(ShortChannelId(5L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 100000000L.msat)

    normalStore.addChannelAnnouncement(channelCAAnn)
    normalStore.addChannelUpdateByPosition(updateCAFromC)
    normalStore.addChannelUpdateByPosition(updateCAFromA)

    // Add direct channels with A and C

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1") // Direct channel, but can't handle a whole amount
    cm.chanBag.put(hcs1)
    val hcs2 = makeHostedCommits(nodeId = c, alias = "peer2") // Indirect channel to be used for the rest of the amount
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

    val List(part1, part2) = cm.opm.data.payments(tag).data.inFlightParts

    assert(part1.route.hops.size == 1) // US -> A
    assert(part1.route.fee == 0L.msat)
    assert(part2.route.hops.size == 2) // US -> C -> A
    assert(part2.route.fee == 920L.msat)
  }

  test("Split after no route found on first attempt") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.pf.debugMode = true

    val sendable1 = cm.opm.getSendable(cm.all.values, maxFee = 1000000L.msat).values.head
    assert(sendable1 == 99000000.msat)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, routerConf.copy(mppMinPartAmount = MilliSatoshi(30000L)), targetNodeId = s, onionTotal = 600000.msat, actualTotal = 600000.msat,
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
    assert(cm.pf.data.extraEdges.size == 1)

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

  test("Fail on excessive local failures") {
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
    val send = SendMultiPart(tag, routerConf.copy(mppMinPartAmount = MilliSatoshi(30000L)), targetNodeId = s, onionTotal = 600000.msat, actualTotal = 600000.msat,
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
  }

  test("Fail fast on terminal failure") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    LNParams.blockCount.set(Int.MaxValue) // This makes all payments unsendable

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer1")
    val hcs3 = makeHostedCommits(nodeId = c, alias = "peer1")
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.chanBag.put(hcs3)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val tag = FullPaymentTag(paymentHash = ByteVector32.One, paymentSecret = ByteVector32.One, tag = PaymentTagTlv.LOCALLY_SENT)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = Long.MaxValue.msat)
    val send = SendMultiPart(tag, routerConf.copy(mppMinPartAmount = MilliSatoshi(30000L)), targetNodeId = s, onionTotal = 600000.msat, actualTotal = 600000.msat,
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
  }
}
