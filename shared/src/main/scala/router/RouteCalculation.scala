package immortan.router

import scala.collection.LazyZip3._
import scala.concurrent.duration._
import scoin.Crypto.PublicKey
import scoin._
import scoin.ln._
import scoin.ln.Bolt11Invoice.ExtraHop
import immortan.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import immortan.router.Router._
import immortan.LNParams

object RouteCalculation {
  def handleRouteRequest(
      graph: DirectedGraph,
      r: RouteRequest
  ): RouteResponse = {
    findRouteInternal(
      graph,
      r.source,
      r.target,
      r.amount,
      r.ignoreChannels,
      r.ignoreNodes,
      r.ignoreDirections,
      r.routeParams
    ) match {
      case Some(searchResult) => {
        RouteFound(
          Route(searchResult.path.map(ChannelHop(_)), searchResult.weight),
          r.fullTag,
          r.partId
        )
      }
      case _ => {
        NoRouteAvailable(r.fullTag, r.partId)
      }
    }
  }

  def makeExtraEdges(
      assistedRoutes: Seq[Seq[ExtraHop]],
      target: PublicKey
  ): Set[GraphEdge] = {
    val converter = routeToEdges(_: List[ExtraHop], target)
    assistedRoutes.flatMap(converter).toSet
  }

  def routeToEdges(
      extraHops: Seq[ExtraHop],
      targetNodeId: PublicKey
  ): Graph.GraphStructure.GraphEdges = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    val protoDescs =
      extraHops
        .map(_.shortChannelId)
        .lazyZip(extraHops.map(_.nodeId))
        .lazyZip(extraHops.map(_.nodeId).drop(1) :+ targetNodeId)

    protoDescs.toList
      .map(ChannelDesc.tupled)
      .zip(extraHops map toFakeUpdate)
      .map(GraphEdge.tupled)
  }

  def toFakeUpdate(extraHop: ExtraHop): ChannelUpdateExt = {
    // Lets assume this fake channel's capacity is 1000 BTC, it will be corrected by failed-at-amount mechanism
    val update = ChannelUpdate(
      signature = ByteVector64.Zeroes,
      chainHash = ByteVector32.Zeroes,
      extraHop.shortChannelId,
      System.currentTimeMillis.milliseconds.toSeconds,
      // messageFlags = 1,
      channelFlags = ChannelUpdate.ChannelFlags(false, false),
      extraHop.cltvExpiryDelta,
      LNParams.minPayment,
      extraHop.feeBase,
      extraHop.feeProportionalMillionths,
      MilliSatoshi(1000000000000000L)
    )

    ChannelUpdateExt.fromUpdate(update)
  }

  private def findRouteInternal(
      graph: DirectedGraph,
      localNodeId: PublicKey,
      targetNodeId: PublicKey,
      amount: MilliSatoshi,
      ignoredEdges: Set[ChannelDesc],
      ignoredVertices: Set[PublicKey],
      ignoreDirections: Set[NodeDirectionDesc],
      rp: RouteParams
  ): Option[Graph.WeightedPath] = {
    Graph.bestPath(
      graph,
      sourceNode = localNodeId,
      targetNodeId,
      amount,
      ignoredEdges,
      ignoredVertices,
      ignoreDirections,
      weight =>
        weight.costs.head - amount < rp.feeReserve && weight.cltv <= rp.routeMaxCltv && weight.length <= rp.routeMaxLength
    )
  }
}
