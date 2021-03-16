package immortan

import fr.acinq.eclair._
import immortan.utils.GraphUtils._
import com.softwaremill.quicklens._
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.RouteCalculation
import org.scalatest.funsuite.AnyFunSuite


class GraphSpec extends AnyFunSuite {
  test("Calculate the best possible route") {
    val routeRequest: RouteRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromNode = a, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(ShortChannelId(1L), a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        makeEdge(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        makeEdge(ShortChannelId(3L), b, d, 40.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) :: // Expensive
        makeEdge(ShortChannelId(4L), c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 600000.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)

    assert(route.hops.map(_.nodeId) == a :: c :: Nil)
    assert(route.hops.map(_.nextNodeId) == c :: d :: Nil)
    assert(route.weight.costs == 100002.msat :: 100000.msat :: Nil)

    val routeRequest1 = routeRequest.copy(ignoreChannels = Set(ChannelDesc(ShortChannelId(2L), a, c))) // Can't use cheap route
    val RouteFound(route1, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest1)

    assert(route1.hops.map(_.nodeId) == a :: b :: Nil)
    assert(route1.hops.map(_.nextNodeId) == b :: d :: Nil)
    assert(route1.weight.costs == 100041.msat :: 100000.msat :: Nil)

    val routeRequest2 = routeRequest.copy(ignoreNodes = Set(c))
    val RouteFound(route2, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest2) // Can't route through cheap node

    assert(route2.hops.map(_.nodeId) == a :: b :: Nil)
    assert(route2.hops.map(_.nextNodeId) == b :: d :: Nil)
    assert(route2.weight.costs == 100041.msat :: 100000.msat :: Nil)

    val routeRequest3 = makeRouteRequest(400000.msat, getParams(routerConf, 400000.msat, offChainFeeRatio / 2000), fromNode = a, fromLocalEdge = null) // 2 msat max fee reserve
    val NoRouteAvailable(_, _) = RouteCalculation.handleRouteRequest(graph, routeRequest3) // Can't handle fees

    val NoRouteAvailable(_, _) = RouteCalculation.handleRouteRequest(graph, routeRequest.copy(amount = 500000L.msat)) // Can't handle amount

    val NoRouteAvailable(_, _) = RouteCalculation.handleRouteRequest(graph, routeRequest.copy(amount = 50L.msat)) // Amount is too small
  }

  test("Always prefer a direct path") {
    val routeRequest: RouteRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromNode = a, fromLocalEdge = null).copy(target = b)

    val graph = DirectedGraph (
      makeEdge(ShortChannelId(1L), a, b, 100.msat, 100, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) :: // Expensive, but direct route does not charge
        makeEdge(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        makeEdge(ShortChannelId(3L), c, b, 2.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.size == 1 && route.hops.head.nodeId == a && route.hops.head.nextNodeId == b)
  }

  test("CLTV delta affects result") {
    val routeRequest: RouteRequest = makeRouteRequest(500000000L.msat, getParams(routerConf, 500000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph: DirectedGraph = DirectedGraph (
      makeEdge(ShortChannelId(1L), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(2L), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(3L), a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(70), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) :: // Used despite higher fee because of much lower cltv
        makeEdge(ShortChannelId(4L), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(5L), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 6000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
  }

  test("Capacity affects result") {
    val routeRequest: RouteRequest = makeRouteRequest(500000000L.msat, getParams(routerConf, 500000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(ShortChannelId(1L), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(2L), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(3L), a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 500000000000L.msat) :: // Used despite higher fee because of much larger channel size
        makeEdge(ShortChannelId(4L), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(5L), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 6000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
  }

  test("Score affects result") {
    val routeRequest: RouteRequest = makeRouteRequest(500000000L.msat, getParams(routerConf, 500000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(ShortChannelId(1L), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(2L), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(3L), a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat, score = 260) :: // Used despite higher fee because of much better score
        makeEdge(ShortChannelId(4L), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(5L), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 6000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
  }

  test("Age affects result") {
    val routeRequest: RouteRequest = makeRouteRequest(500000000L.msat, getParams(routerConf, 500000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(ShortChannelId("600000x1x1"), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId("600000x1x1"), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId("510000x1x1"), a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) :: // Used despite higher fee because it's very old
        makeEdge(ShortChannelId("600000x1x1"), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId("600000x1x1"), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 6000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
  }

  test("Hosted channel parameters do not matter") {
    val routeRequest: RouteRequest = makeRouteRequest(500000000L.msat, getParams(routerConf, 500000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(ShortChannelId(1L), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(2L), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(3L), a, c, 100.msat, 10, cltvDelta = CltvExpiryDelta(9), minHtlc = 10000.msat, maxHtlc = 500000000000L.msat).modify(_.updExt.useHeuristics).setTo(false) :: // Disregard better params
        makeEdge(ShortChannelId(4L), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId(5L), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 6000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: b :: Nil)
  }
}
