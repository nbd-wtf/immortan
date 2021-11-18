package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.router.Router.{ChannelDesc, NoRouteAvailable, RouteFound}
import fr.acinq.eclair.wire._
import immortan.PathFinder.ExpectedRouteToPayee
import immortan.crypto.{CanBeRepliedTo, Tools}
import immortan.utils.ChannelUtils._
import immortan.utils.GraphUtils._
import immortan.utils.SQLiteUtils._
import immortan.utils.TestUtils._
import org.scalatest.funsuite.AnyFunSuite


class PathfinderSpec extends AnyFunSuite {
  val (normalStore, hostedStore) = getSQLiteNetworkStores
  fillBasicGraph(normalStore)

  test("Compute expected fees") {
    val amountToSend = 100000000L.msat
    val finalExpectedHop = AvgHopParams(CltvExpiryDelta(100), 100, MilliSatoshi(1000L), sampleSize = 1)
    val intermediaryExpectedHop = AvgHopParams(CltvExpiryDelta(200), 200, MilliSatoshi(1000L), sampleSize = 100)
    val payeeBeforeTrampoline = ExpectedRouteToPayee(hops = intermediaryExpectedHop :: intermediaryExpectedHop :: finalExpectedHop :: Nil).totalWithFee(amountToSend)

    val trampolineFee = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 1000000000L.msat, 1000, exponent = 0.79, logExponent = 2.1, CltvExpiryDelta(100))
    val payeeWithTrampoline = ExpectedRouteToPayee(hops = trampolineFee :: intermediaryExpectedHop :: intermediaryExpectedHop :: finalExpectedHop :: Nil)
    assert(trampolineFee.relayFee(payeeBeforeTrampoline) == payeeWithTrampoline.totalWithFee(amountToSend) - payeeBeforeTrampoline)

    val peerEdgeFee = ExtraHop(randomKey.publicKey, shortChannelId = 1L, feeBase = MilliSatoshi(1000L), feeProportionalMillionths = 500L, CltvExpiryDelta(200))
    val payeeWithPeerEdge = ExpectedRouteToPayee(hops = peerEdgeFee :: trampolineFee :: intermediaryExpectedHop :: intermediaryExpectedHop :: finalExpectedHop :: Nil)
    val payeeWithoutTrampoline = ExpectedRouteToPayee(hops = peerEdgeFee :: intermediaryExpectedHop :: intermediaryExpectedHop :: intermediaryExpectedHop :: finalExpectedHop :: Nil)
    assert(payeeWithPeerEdge.totalWithFee(amountToSend) < payeeWithoutTrampoline.totalWithFee(amountToSend))
    assert(payeeWithPeerEdge.totalWithFee(amountToSend) - amountToSend == 113125L.msat)
  }

  test("Exclude one-sided and ghost channels") {
    val channel1AS: ChannelAnnouncement = makeAnnouncement(5L, a, s) // To be excluded because it's a ghost channel
    val channel2ASOneSideUpdate: ChannelAnnouncement = makeAnnouncement(6L, a, s) // To be excluded because it misses one update

    val update1ASFromA: ChannelUpdate = makeUpdate(5L, a, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val update1ASFromS: ChannelUpdate = makeUpdate(5L, s, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)

    val update2ASFromSOneSide: ChannelUpdate = makeUpdate(6L, s, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)

    val addChannelAnnouncementNewSqlPQ = normalStore.db.makePreparedQuery(normalStore.announceTable.newSql)
    val addChannelUpdateByPositionNewSqlPQ = normalStore.db.makePreparedQuery(normalStore.updateTable.newSql)
    val addChannelUpdateByPositionUpdSqlPQ = normalStore.db.makePreparedQuery(normalStore.updateTable.updSQL)

    // Ghost channel (peer does not have it)
    normalStore.addChannelAnnouncement(channel1AS, addChannelAnnouncementNewSqlPQ)
    normalStore.addChannelUpdateByPosition(update1ASFromA, addChannelUpdateByPositionNewSqlPQ, addChannelUpdateByPositionUpdSqlPQ)
    normalStore.addChannelUpdateByPosition(update1ASFromS)

    // One-sided channel
    normalStore.addChannelAnnouncement(channel2ASOneSideUpdate, addChannelAnnouncementNewSqlPQ)
    normalStore.addChannelUpdateByPosition(update2ASFromSOneSide)

    addChannelAnnouncementNewSqlPQ.close
    addChannelUpdateByPositionNewSqlPQ.close
    addChannelUpdateByPositionUpdSqlPQ.close

    val oneSideShortIds = normalStore.listChannelsWithOneUpdate
    normalStore.removeGhostChannels(Set(update1ASFromA.shortChannelId), oneSideShortIds)

    val routingMap = normalStore.getRoutingData
    assert(normalStore.listExcludedChannels.contains(6L)) // One-sided channel got banned, ghost channel has been removed
    assert(!normalStore.listChannelAnnouncements.map(_.shortChannelId).toList.contains(update2ASFromSOneSide.shortChannelId))
    assert(!normalStore.listChannelUpdates.map(_.update.shortChannelId).toList.contains(update2ASFromSOneSide.shortChannelId))
    assert(!routingMap.keySet.contains(update1ASFromA.shortChannelId))
    assert(routingMap.size == 4)
  }

  test("Reject, load graph, notify once operational") {
    var responses: List[Any] = Nil

    val pf = makePathFinder(normalStore, hostedStore)

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = responses ::= reply
    }

    pf.listeners += sender // Will get operational notification as a listener

    val fromKey = randomKey.publicKey
    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = fromKey, toPeer = a)
    val routeRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromKey, fakeLocalEdge)
    pf process PathFinder.FindRoute(sender, routeRequest) // Will get rejected reply as message parameter
    WAIT_UNTIL_TRUE(responses.head.isInstanceOf[RouteFound])
    WAIT_UNTIL_TRUE(responses.last == PathFinder.NotifyOperational)
  }

  test("Get expected route to payee") {
    var responses: List[Any] = Nil

    val pf = makePathFinder(normalStore, hostedStore)

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = responses ::= reply
    }

    pf.listeners += sender // Will get operational notification as a listener

    val edgeBSFromB = makeEdge(7L, b, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(100), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val edgeDSFromD = makeEdge(8L, d, s, 2.msat, 20, cltvDelta = CltvExpiryDelta(300), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val edgeCSFromC = makeEdge(9L, c, s, 30.msat, 300, cltvDelta = CltvExpiryDelta(300), minHtlc = 10L.msat, maxHtlc = 500000.msat)

    pf process edgeBSFromB
    pf process edgeDSFromD
    pf process edgeCSFromC
    pf process edgeCSFromC // Disregarded

    pf process PathFinder.GetExpectedRouteToPayee(sender, payee = a, interHops = 2)
    WAIT_UNTIL_TRUE(responses.head.asInstanceOf[ExpectedRouteToPayee].hops.head.asInstanceOf[AvgHopParams].cltvExpiryDelta.underlying == 144) // Private channel CLTV is disregarded
    WAIT_UNTIL_TRUE(responses.head.asInstanceOf[ExpectedRouteToPayee].hops(2).asInstanceOf[AvgHopParams].cltvExpiryDelta.underlying == 144) // An upper mode of two channels has been provided
    WAIT_UNTIL_TRUE(responses.head.asInstanceOf[ExpectedRouteToPayee].hops(2).asInstanceOf[AvgHopParams].sampleSize == 2)
    WAIT_UNTIL_TRUE(responses.last == PathFinder.NotifyOperational)

    responses = Nil
    pf process PathFinder.GetExpectedRouteToPayee(sender, payee = s, interHops = 3)
    WAIT_UNTIL_TRUE(responses.head.asInstanceOf[ExpectedRouteToPayee].hops.head.asInstanceOf[AvgHopParams].cltvExpiryDelta.underlying == 144) // Private channel CLTV is disregarded
    WAIT_UNTIL_TRUE(responses.head.asInstanceOf[ExpectedRouteToPayee].hops.last.asInstanceOf[AvgHopParams].cltvExpiryDelta.underlying == 300) // An mode of three channels has been provided
    WAIT_UNTIL_TRUE(responses.head.asInstanceOf[ExpectedRouteToPayee].hops.last.asInstanceOf[AvgHopParams].feeProportionalMillionths == 244) // A mean + 1 SD has been provided
    WAIT_UNTIL_TRUE(responses.head.asInstanceOf[ExpectedRouteToPayee].hops.last.asInstanceOf[AvgHopParams].sampleSize == 3) // Only private channels are taken into account
  }

  test("Find a route on cold start") {
    var obtainedRoute: RouteFound = null

    val pf = makePathFinder(normalStore, hostedStore)

    val fromKey = randomKey.publicKey
    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = fromKey, toPeer = a)
    val routeRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromKey, fakeLocalEdge)

    val sender: CanBeRepliedTo = new CanBeRepliedTo {
      override def process(reply: Any): Unit = reply match {
        case PathFinder.NotifyOperational => pf process PathFinder.FindRoute(this, routeRequest) // Send route request once notified (as listener)
        case found: RouteFound => obtainedRoute = found // Store obtained route (as message parameter)
      }
    }

    pf.listeners += sender
    pf process PathFinder.CMDLoadGraph
    WAIT_UNTIL_TRUE(obtainedRoute.route.hops.map(_.nodeId) == fromKey :: a :: c :: Nil)
  }

  test("Find a route using assited channels") {
    var response: Any = null

    val pf = makePathFinder(normalStore, hostedStore)

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response = reply
    }

    val fromKey = randomKey.publicKey
    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = fromKey, toPeer = a)
    val edgeDSFromD = makeEdge(6L, d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val routeRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromKey, fakeLocalEdge).copy(target = s)

    // Assisted channel is now reachable
    pf process edgeDSFromD
    pf process PathFinder.CMDLoadGraph
    pf process PathFinder.FindRoute(sender, routeRequest)
    WAIT_UNTIL_TRUE(response.asInstanceOf[RouteFound].route.hops.map(_.nextNodeId) == a :: c :: d :: s :: Nil)

    // Assisted channel has been updated
    val updateDSFromD = makeEdge(6L, d, s, 4.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    pf process updateDSFromD
    pf process PathFinder.FindRoute(sender, routeRequest)
    WAIT_UNTIL_TRUE(response.asInstanceOf[RouteFound].route.hops.map(_.nextNodeId) == a :: c :: d :: s :: Nil)
    WAIT_UNTIL_TRUE(response.asInstanceOf[RouteFound].route.routedPerChannelHop.last._2.edge.updExt.update.feeBaseMsat == 4.msat)

    // Public channel has been updated, CLTV got worse so another channel has been selected
    val updateACFromA1: ChannelUpdate = makeUpdate(2L, a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(154), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    pf process updateACFromA1
    pf process PathFinder.FindRoute(sender, routeRequest)
    WAIT_UNTIL_TRUE(response.asInstanceOf[RouteFound].route.hops.map(_.nextNodeId) == a :: b :: d :: s :: Nil)

    // Another public channel has been updated, a better one got disabled so the one with worse fee is selected again
    val disabled = Announcements.makeChannelFlags(isNode1 = Announcements.isNode1(a, b), enable = false)
    val updateABFromA1 = makeUpdate(1L, a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(14), minHtlc = 10L.msat, maxHtlc = 500000.msat).copy(channelFlags = disabled)
    pf process updateABFromA1
    // Disabled channel is updated and still present in graph, but outgoing FSM instructs pathfinder to omit it
    pf process PathFinder.FindRoute(sender, routeRequest.copy(ignoreChannels = Set(ChannelDesc(updateABFromA1.shortChannelId, a, b))))
    WAIT_UNTIL_TRUE(response.asInstanceOf[RouteFound].route.hops.map(_.nextNodeId) == a :: c :: d :: s :: Nil)

    // The only assisted channel got disabled, payee is now unreachable
    val disabled1 = Announcements.makeChannelFlags(isNode1 = Announcements.isNode1(d, s), enable = false)
    val updateDSFromD1 = makeUpdate(6L, d, s, 2.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat).copy(channelFlags = disabled1)
    pf process updateDSFromD1
    // Disabled channel is updated and still present in graph, but outgoing FSM instructs pathfinder to omit it
    pf process PathFinder.FindRoute(sender, routeRequest.copy(ignoreChannels = Set(ChannelDesc(updateDSFromD1.shortChannelId, d, s))))
    WAIT_UNTIL_TRUE(response.isInstanceOf[NoRouteAvailable])
  }
}
