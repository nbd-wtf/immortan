package immortan

import fr.acinq.eclair._
import immortan.utils.GraphUtils._
import immortan.utils.SQLiteUtils._
import immortan.utils.ChannelUtils._
import immortan.crypto.{CanBeRepliedTo, Tools}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.router.Router.{NoRouteAvailable, RouteFound}
import fr.acinq.eclair.router.Announcements
import org.scalatest.funsuite.AnyFunSuite


class PathfinderSpec extends AnyFunSuite {
  val (normalStore, hostedStore) = getSQLiteNetworkStores
  fillBasicGraph(normalStore)

  test("Exclude one-sided and ghost channels") {
    val channel1AS: ChannelAnnouncement = makeAnnouncement(5L, a, s) // To be excluded because it's a ghost channel
    val channel2ASOneSideUpdate: ChannelAnnouncement = makeAnnouncement(6L, a, s) // To be excluded because it misses one update

    val update1ASFromA: ChannelUpdate = makeUpdate(ShortChannelId(5L), a, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val update1ASFromS: ChannelUpdate = makeUpdate(ShortChannelId(5L), s, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)

    val update2ASFromSOneSide: ChannelUpdate = makeUpdate(ShortChannelId(6L), s, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)

    // Ghost channel (peer does not have it)
    normalStore.addChannelAnnouncement(channel1AS)
    normalStore.addChannelUpdateByPosition(update1ASFromA)
    normalStore.addChannelUpdateByPosition(update1ASFromS)

    // One-sided channel
    normalStore.addChannelAnnouncement(channel2ASOneSideUpdate)
    normalStore.addChannelUpdateByPosition(update2ASFromSOneSide)

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
    pf process Tuple2(sender, routeRequest) // Will get rejected reply as message parameter

    synchronized(wait(500L))
    assert(responses == PathFinder.NotifyOperational :: PathFinder.NotifyRejected :: Nil)
  }

  test("Find a route on cold start") {
    var obtainedRoute: RouteFound = null

    val pf = makePathFinder(normalStore, hostedStore)

    val fromKey = randomKey.publicKey
    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = fromKey, toPeer = a)
    val routeRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromKey, fakeLocalEdge)

    val sender: CanBeRepliedTo = new CanBeRepliedTo {
      override def process(reply: Any): Unit = reply match {
        case PathFinder.NotifyOperational => pf process Tuple2(this, routeRequest) // Send route request once notified (as listener)
        case found: RouteFound => obtainedRoute = found // Store obtained route (as message parameter)
      }
    }

    pf.listeners += sender
    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))
    assert(obtainedRoute.route.hops.map(_.nodeId) == fromKey :: a :: c :: Nil)
  }

  test("Find a route using assited channels") {
    var response: Any = null

    val pf = makePathFinder(normalStore, hostedStore)

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response = reply
    }

    val fromKey = randomKey.publicKey
    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = fromKey, toPeer = a)
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val routeRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromKey, fakeLocalEdge).copy(target = s)

    // Assisted channel is now reachable
    pf process edgeDSFromD
    pf process PathFinder.CMDLoadGraph
    pf process Tuple2(sender, routeRequest)
    synchronized(wait(500L))
    assert(response.asInstanceOf[RouteFound].route.hops.map(_.nextNodeId) == a :: c :: d :: s :: Nil)

    // Assisted channel has been updated
    val updateDSFromD = makeEdge(ShortChannelId(6L), d, s, 4.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    pf process updateDSFromD
    pf process Tuple2(sender, routeRequest)
    synchronized(wait(500L))
    assert(response.asInstanceOf[RouteFound].route.hops.map(_.nextNodeId) == a :: c :: d :: s :: Nil)
    assert(response.asInstanceOf[RouteFound].route.routedPerChannelHop.last._2.edge.updExt.update.feeBaseMsat == 4.msat)

    // Public channel has been updated, CLTV got worse so another channel has been selected
    val updateACFromA1: ChannelUpdate = makeUpdate(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(154), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    pf process updateACFromA1
    pf process Tuple2(sender, routeRequest)
    synchronized(wait(500L))
    assert(response.asInstanceOf[RouteFound].route.hops.map(_.nextNodeId) == a :: b :: d :: s :: Nil)

    // Another public channel has been updated, a better one got disabled so the one with worse fee is selected again
    val disabled = Announcements.makeChannelFlags(isNode1 = Announcements.isNode1(a, b), enable = false)
    val updateABFromA1 = makeUpdate(ShortChannelId(1L), a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(14), minHtlc = 10L.msat, maxHtlc = 500000.msat).copy(channelFlags = disabled)
    pf process updateABFromA1
    pf process Tuple2(sender, routeRequest)
    synchronized(wait(500L))
    assert(response.asInstanceOf[RouteFound].route.hops.map(_.nextNodeId) == a :: c :: d :: s :: Nil)

    // The only assisted channel got disabled, payee is now unreachable
    val disabled1 = Announcements.makeChannelFlags(isNode1 = Announcements.isNode1(d, s), enable = false)
    val updateDSFromD1 = makeUpdate(ShortChannelId(6L), d, s, 2.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat).copy(channelFlags = disabled1)
    pf process updateDSFromD1
    pf process Tuple2(sender, routeRequest)
    synchronized(wait(500L))
    assert(response.isInstanceOf[NoRouteAvailable])
  }
}
