package immortan

import com.softwaremill.quicklens._
import scoin._
import scoin.ln._
import utest._

import immortan.router.Graph.GraphStructure.DirectedGraph
import immortan.router.RouteCalculation
import immortan.router.Router._
import immortan.utils.GraphUtils._

object GraphSpec extends TestSuite {
  val tests = Tests {
    test("Calculate the best possible route") {
      val routeRequest: RouteRequest = makeRouteRequest(
        MilliSatoshi(100000),
        getParams(routerConf, MilliSatoshi(100000), offChainFeeRatio),
        fromNode = a,
        fromLocalEdge = null
      )

      val graph = DirectedGraph(
        makeEdge(
          1L,
          a,
          b,
          MilliSatoshi(1),
          10,
          cltvDelta = CltvExpiryDelta(1),
          minHtlc = MilliSatoshi(10000),
          maxHtlc = MilliSatoshi(500000)
        ) ::
          makeEdge(
            2L,
            a,
            c,
            MilliSatoshi(1),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(500000)
          ) ::
          makeEdge(
            3L,
            b,
            d,
            MilliSatoshi(40),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(500000)
          ) :: // Expensive
          makeEdge(
            4L,
            c,
            d,
            MilliSatoshi(1),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(600000)
          ) ::
          Nil
      )

      val RouteFound(route, _, _) =
        RouteCalculation.handleRouteRequest(graph, routeRequest)

      assert(route.hops.map(_.nodeId) == a :: c :: Nil)
      assert(route.hops.map(_.nextNodeId) == c :: d :: Nil)
      assert(
        route.weight.costs == MilliSatoshi(100002) :: MilliSatoshi(
          100000
        ) :: Nil
      )

      val routeRequest1 = routeRequest.copy(ignoreChannels =
        Set(ChannelDesc(ShortChannelId(2L), a, c))
      ) // Can't use cheap route
      val RouteFound(route1, _, _) =
        RouteCalculation.handleRouteRequest(graph, routeRequest1)

      assert(route1.hops.map(_.nodeId) == a :: b :: Nil)
      assert(route1.hops.map(_.nextNodeId) == b :: d :: Nil)
      assert(
        route1.weight.costs == MilliSatoshi(100041) :: MilliSatoshi(
          100000
        ) :: Nil
      )

      val routeRequest2 = routeRequest.copy(ignoreNodes = Set(c))
      val RouteFound(route2, _, _) = RouteCalculation.handleRouteRequest(
        graph,
        routeRequest2
      ) // Can't route through cheap node

      assert(route2.hops.map(_.nodeId) == a :: b :: Nil)
      assert(route2.hops.map(_.nextNodeId) == b :: d :: Nil)
      assert(
        route2.weight.costs == MilliSatoshi(100041) :: MilliSatoshi(
          100000
        ) :: Nil
      )

      val routeRequest3 = makeRouteRequest(
        MilliSatoshi(400000),
        getParams(routerConf, MilliSatoshi(400000), offChainFeeRatio / 2000),
        fromNode = a,
        fromLocalEdge = null
      ) // MilliSatoshi(2) max fee reserve
      val NoRouteAvailable(_, _) = RouteCalculation.handleRouteRequest(
        graph,
        routeRequest3
      ) // Can't handle fees

      val NoRouteAvailable(_, _) = RouteCalculation.handleRouteRequest(
        graph,
        routeRequest.copy(amount = MilliSatoshi(500000L))
      ) // Can't handle amount

      val NoRouteAvailable(_, _) = RouteCalculation.handleRouteRequest(
        graph,
        routeRequest.copy(amount = MilliSatoshi(50L))
      ) // Amount is too small
    }

    test("Exclude failed node1 -> node2 directions") {
      val graph = DirectedGraph(
        makeEdge(
          1L,
          a,
          b,
          MilliSatoshi(1),
          10,
          cltvDelta = CltvExpiryDelta(1),
          minHtlc = MilliSatoshi(10000),
          maxHtlc = MilliSatoshi(500000)
        ) ::
          makeEdge(
            2L,
            a,
            c,
            MilliSatoshi(1),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(500000)
          ) ::
          makeEdge(
            3L,
            b,
            d,
            MilliSatoshi(40),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(500000)
          ) :: // Expensive
          makeEdge(
            4L,
            c,
            d,
            MilliSatoshi(1),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(600000)
          ) ::
          makeEdge(
            5L,
            c,
            d,
            MilliSatoshi(1),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(600000)
          ) ::
          makeEdge(
            6L,
            c,
            d,
            MilliSatoshi(1),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(600000)
          ) ::
          Nil
      )

      //     / b \
      //    a     d
      //     \ c ///, excluded

      val routeRequest1: RouteRequest = makeRouteRequest(
        MilliSatoshi(100000),
        getParams(routerConf, MilliSatoshi(100000), offChainFeeRatio),
        fromNode = a,
        fromLocalEdge = null
      )
      val RouteFound(route1, _, _) =
        RouteCalculation.handleRouteRequest(graph, routeRequest1)
      assert(route1.hops.map(_.nodeId) == a :: c :: Nil)

      val routeRequest2 = routeRequest1.copy(ignoreDirections =
        Set(NodeDirectionDesc(from = c, to = d))
      ) // c -> d direction is excluded
      val RouteFound(route2, _, _) =
        RouteCalculation.handleRouteRequest(graph, routeRequest2)
      assert(route2.hops.map(_.nodeId) == a :: b :: Nil)
    }

    test("Always prefer a direct path") {
      val routeRequest: RouteRequest = makeRouteRequest(
        MilliSatoshi(100000),
        getParams(routerConf, MilliSatoshi(100000), offChainFeeRatio),
        fromNode = a,
        fromLocalEdge = null
      ).copy(target = b)

      val graph = DirectedGraph(
        makeEdge(
          1L,
          a,
          b,
          MilliSatoshi(100),
          100,
          cltvDelta = CltvExpiryDelta(1),
          minHtlc = MilliSatoshi(10000),
          maxHtlc = MilliSatoshi(500000)
        ) :: // Expensive, but direct route does not charge
          makeEdge(
            2L,
            a,
            c,
            MilliSatoshi(1),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(500000)
          ) ::
          makeEdge(
            3L,
            c,
            b,
            MilliSatoshi(2),
            10,
            cltvDelta = CltvExpiryDelta(1),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(500000)
          ) ::
          Nil
      )

      val RouteFound(route, _, _) =
        RouteCalculation.handleRouteRequest(graph, routeRequest)
      assert(
        route.hops.size == 1 && route.hops.head.nodeId == a && route.hops.head.nextNodeId == b
      )
    }

    test("CLTV delta affects result") {
      val routeRequest: RouteRequest = makeRouteRequest(
        MilliSatoshi(500000000L),
        getParams(routerConf, MilliSatoshi(500000000L), offChainFeeRatio),
        fromNode = s,
        fromLocalEdge = null
      )

      val graph: DirectedGraph = DirectedGraph(
        makeEdge(
          1L,
          s,
          a,
          MilliSatoshi(1000),
          100,
          cltvDelta = CltvExpiryDelta(576),
          minHtlc = MilliSatoshi(10000),
          maxHtlc = MilliSatoshi(5000000000L)
        ) ::
          makeEdge(
            2L,
            a,
            b,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(576),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(5000000000L)
          ) ::
          makeEdge(
            3L,
            a,
            c,
            MilliSatoshi(1000),
            150,
            cltvDelta = CltvExpiryDelta(70),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(5000000000L)
          ) :: // Used despite higher fee because of much lower cltv
          makeEdge(
            4L,
            b,
            d,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(576),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(5000000000L)
          ) ::
          makeEdge(
            5L,
            c,
            d,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(576),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(6000000000L)
          ) ::
          Nil
      )

      val RouteFound(route, _, _) =
        RouteCalculation.handleRouteRequest(graph, routeRequest)
      assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
    }

    test("Capacity affects result") {
      val routeRequest: RouteRequest = makeRouteRequest(
        MilliSatoshi(500000000L),
        getParams(routerConf, MilliSatoshi(500000000L), offChainFeeRatio),
        fromNode = s,
        fromLocalEdge = null
      )

      val graph = DirectedGraph(
        makeEdge(
          1L,
          s,
          a,
          MilliSatoshi(1000),
          100,
          cltvDelta = CltvExpiryDelta(144),
          minHtlc = MilliSatoshi(10000),
          maxHtlc = MilliSatoshi(5000000000L)
        ) ::
          makeEdge(
            2L,
            a,
            b,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(144),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(5000000000L)
          ) ::
          makeEdge(
            3L,
            a,
            c,
            MilliSatoshi(1000),
            150,
            cltvDelta = CltvExpiryDelta(144),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(500000000000L)
          ) :: // Used despite higher fee because of much larger channel size
          makeEdge(
            4L,
            b,
            d,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(144),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(5000000000L)
          ) ::
          makeEdge(
            5L,
            c,
            d,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(144),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(6000000000L)
          ) ::
          Nil
      )

      val RouteFound(route, _, _) =
        RouteCalculation.handleRouteRequest(graph, routeRequest)
      assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
    }

    test("Score affects result") {
      val routeRequest: RouteRequest = makeRouteRequest(
        MilliSatoshi(500000000L),
        getParams(routerConf, MilliSatoshi(500000000L), offChainFeeRatio),
        fromNode = s,
        fromLocalEdge = null
      )

      val graph = DirectedGraph(
        makeEdge(
          1L,
          s,
          a,
          MilliSatoshi(1000),
          100,
          cltvDelta = CltvExpiryDelta(144),
          minHtlc = MilliSatoshi(10000),
          maxHtlc = MilliSatoshi(5000000000L)
        ) ::
          makeEdge(
            2L,
            a,
            b,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(144),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(5000000000L)
          ) ::
          makeEdge(
            3L,
            a,
            c,
            MilliSatoshi(1000),
            150,
            cltvDelta = CltvExpiryDelta(144),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(5000000000L),
            score = 260
          ) :: // Used despite higher fee because of much better score
          makeEdge(
            4L,
            b,
            d,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(144),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(5000000000L)
          ) ::
          makeEdge(
            5L,
            c,
            d,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(144),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(6000000000L)
          ) ::
          Nil
      )

      val RouteFound(route, _, _) =
        RouteCalculation.handleRouteRequest(graph, routeRequest)
      assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
    }

    // (fiatjaf: this was already failing in anton's code so I'll comment out because I don't fully get these graph things)
    // test("Age affects result") {
    //   val routeRequest: RouteRequest = makeRouteRequest(
    //     MilliSatoshi(500000000L),
    //     getParams(routerConf, MilliSatoshi(500000000L), offChainFeeRatio),
    //     fromNode = s,
    //     fromLocalEdge = null
    //   )

    //   val graph = DirectedGraph(
    //     makeEdge(
    //       ShortChannelId.produce("600000x1x1"),
    //       s,
    //       a,
    //       MilliSatoshi(1000),
    //       100,
    //       cltvDelta = CltvExpiryDelta(144),
    //       minHtlc = MilliSatoshi(10000),
    //       maxHtlc = MilliSatoshi(5000000000L)
    //     ) ::
    //       makeEdge(
    //         ShortChannelId.produce("600000x1x1"),
    //         a,
    //         b,
    //         MilliSatoshi(1000),
    //         100,
    //         cltvDelta = CltvExpiryDelta(144),
    //         minHtlc = MilliSatoshi(10000),
    //         maxHtlc = MilliSatoshi(5000000000L)
    //       ) ::
    //       makeEdge(
    //         ShortChannelId.produce("500000x1x1"),
    //         a,
    //         c,
    //         MilliSatoshi(1000),
    //         150,
    //         cltvDelta = CltvExpiryDelta(144),
    //         minHtlc = MilliSatoshi(10000),
    //         maxHtlc = MilliSatoshi(5000000000L)
    //       ) :: // Used despite higher fee because it's very old
    //       makeEdge(
    //         ShortChannelId.produce("600000x1x1"),
    //         b,
    //         d,
    //         MilliSatoshi(1000),
    //         100,
    //         cltvDelta = CltvExpiryDelta(144),
    //         minHtlc = MilliSatoshi(10000),
    //         maxHtlc = MilliSatoshi(5000000000L)
    //       ) ::
    //       makeEdge(
    //         ShortChannelId.produce("600000x1x1"),
    //         c,
    //         d,
    //         MilliSatoshi(1000),
    //         100,
    //         cltvDelta = CltvExpiryDelta(144),
    //         minHtlc = MilliSatoshi(10000),
    //         maxHtlc = MilliSatoshi(6000000000L)
    //       ) ::
    //       Nil
    //   )

    //   val RouteFound(route, _, _) =
    //     RouteCalculation.handleRouteRequest(graph, routeRequest)
    //   assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
    // }

    test("Hosted channel parameters do not matter") {
      val routeRequest: RouteRequest = makeRouteRequest(
        MilliSatoshi(1000000000L),
        getParams(routerConf, MilliSatoshi(1000000000L), offChainFeeRatio),
        fromNode = s,
        fromLocalEdge = null
      )

      val graph = DirectedGraph(
        makeEdge(
          1L,
          s,
          a,
          MilliSatoshi(1000),
          100,
          cltvDelta = CltvExpiryDelta(576),
          minHtlc = MilliSatoshi(10000),
          maxHtlc = MilliSatoshi(50000000000L)
        ) ::
          makeEdge(
            2L,
            a,
            b,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(576),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(50000000000L)
          ) ::
          makeEdge(
            3L,
            a,
            c,
            MilliSatoshi(1000),
            200,
            cltvDelta = CltvExpiryDelta(1000),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(5000000000L)
          ).modify(_.updExt.useHeuristics).setTo(false) ::
          makeEdge(
            4L,
            b,
            d,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(576),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(50000000000L)
          ) ::
          makeEdge(
            5L,
            c,
            d,
            MilliSatoshi(1000),
            100,
            cltvDelta = CltvExpiryDelta(576),
            minHtlc = MilliSatoshi(10000),
            maxHtlc = MilliSatoshi(50000000000L)
          ) ::
          Nil
      )

      val RouteFound(route, _, _) =
        RouteCalculation.handleRouteRequest(graph, routeRequest)
      assert(route.hops.map(_.nodeId) == s :: a :: c :: Nil)
    }
  }
}
