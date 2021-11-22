package immortan

import com.softwaremill.quicklens._
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.eclair.router.Router._
import immortan.utils.GraphUtils._
import org.scalatest.funsuite.AnyFunSuite


class GraphSpec extends AnyFunSuite {
  test("Calculate the best possible route") {
    val routeRequest: RouteRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromNode = a, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(1L, a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        makeEdge(2L, a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        makeEdge(3L, b, d, 40.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) :: // Expensive
        makeEdge(4L, c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 600000.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)

    assert(route.hops.map(_.nodeId) == a :: c :: Nil)
    assert(route.hops.map(_.nextNodeId) == c :: d :: Nil)
    assert(route.weight.costs == 100002.msat :: 100000.msat :: Nil)

    val routeRequest1 = routeRequest.copy(ignoreChannels = Set(ChannelDesc(2L, a, c))) // Can't use cheap route
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

  test("Exclude failed node1 -> node2 directions") {
    val graph = DirectedGraph (
      makeEdge(1L, a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        makeEdge(2L, a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        makeEdge(3L, b, d, 40.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) :: // Expensive
        makeEdge(4L, c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 600000.msat) ::
        makeEdge(5L, c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 600000.msat) ::
        makeEdge(6L, c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 600000.msat) ::
        Nil
    )

    //     / b \
    //    a     d
    //     \ c ///, excluded

    val routeRequest1: RouteRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromNode = a, fromLocalEdge = null)
    val RouteFound(route1, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest1)
    assert(route1.hops.map(_.nodeId) == a :: c :: Nil)

    val routeRequest2 = routeRequest1.copy(ignoreDirections = Set(NodeDirectionDesc(from = c, to = d))) // c -> d direction is excluded
    val RouteFound(route2, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest2)
    assert(route2.hops.map(_.nodeId) == a :: b :: Nil)
  }

  test("Always prefer a direct path") {
    val routeRequest: RouteRequest = makeRouteRequest(100000.msat, getParams(routerConf, 100000.msat, offChainFeeRatio), fromNode = a, fromLocalEdge = null).copy(target = b)

    val graph = DirectedGraph (
      makeEdge(1L, a, b, 100.msat, 100, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) :: // Expensive, but direct route does not charge
        makeEdge(2L, a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        makeEdge(3L, c, b, 2.msat, 10, cltvDelta = CltvExpiryDelta(1), minHtlc = 10000.msat, maxHtlc = 500000.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.size == 1 && route.hops.head.nodeId == a && route.hops.head.nextNodeId == b)
  }

  test("CLTV delta affects result") {
    val routeRequest: RouteRequest = makeRouteRequest(500000000L.msat, getParams(routerConf, 500000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph: DirectedGraph = DirectedGraph (
      makeEdge(1L, s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(2L, a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(3L, a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(70), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) :: // Used despite higher fee because of much lower cltv
        makeEdge(4L, b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(5L, c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 6000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
  }

  test("Capacity affects result") {
    val routeRequest: RouteRequest = makeRouteRequest(500000000L.msat, getParams(routerConf, 500000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(1L, s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(2L, a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(3L, a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 500000000000L.msat) :: // Used despite higher fee because of much larger channel size
        makeEdge(4L, b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(5L, c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 6000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
  }

  test("Score affects result") {
    val routeRequest: RouteRequest = makeRouteRequest(500000000L.msat, getParams(routerConf, 500000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(1L, s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(2L, a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(3L, a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat, score = 260) :: // Used despite higher fee because of much better score
        makeEdge(4L, b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(5L, c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 6000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
  }

  test("Age affects result") {
    val routeRequest: RouteRequest = makeRouteRequest(500000000L.msat, getParams(routerConf, 500000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(ShortChannelId.produce("600000x1x1"), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId.produce("600000x1x1"), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId.produce("500000x1x1"), a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) :: // Used despite higher fee because it's very old
        makeEdge(ShortChannelId.produce("600000x1x1"), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat) ::
        makeEdge(ShortChannelId.produce("600000x1x1"), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), minHtlc = 10000.msat, maxHtlc = 6000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
  }

  test("Hosted channel parameters do not matter") {
    val routeRequest: RouteRequest = makeRouteRequest(1000000000L.msat, getParams(routerConf, 1000000000L.msat, offChainFeeRatio), fromNode = s, fromLocalEdge = null)

    val graph = DirectedGraph (
      makeEdge(1L, s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 50000000000L.msat) ::
        makeEdge(2L, a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 50000000000L.msat) ::
        makeEdge(3L, a, c, 1000.msat, 200, cltvDelta = CltvExpiryDelta(1000), minHtlc = 10000.msat, maxHtlc = 5000000000L.msat).modify(_.updExt.useHeuristics).setTo(false) ::
        makeEdge(4L, b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 50000000000L.msat) ::
        makeEdge(5L, c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), minHtlc = 10000.msat, maxHtlc = 50000000000L.msat) ::
        Nil
    )

    val RouteFound(route, _, _) = RouteCalculation.handleRouteRequest(graph, routeRequest)
    assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
  }
}
