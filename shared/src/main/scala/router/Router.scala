package immortan.router

import scodec.bits.ByteVector
import scoin.Crypto.PublicKey
import scoin._
import scoin.ln._

import immortan._
import immortan.FullPaymentTag
import immortan.utils.Statistics
import immortan.router.Graph.GraphStructure._
import immortan.router.Graph.RichWeight
import scodec.codecs.ShortCodec.apply

object ChannelUpdateExt {
  def fromUpdate(update: ChannelUpdate): ChannelUpdateExt = ChannelUpdateExt(
    update,
    Sync.getChecksum(update),
    score = 1L,
    useHeuristics = false
  )
}

case class ChannelUpdateExt(
    update: ChannelUpdate,
    crc32: Long,
    score: Long,
    useHeuristics: Boolean
) {
  def withNewUpdate(cu: ChannelUpdate): ChannelUpdateExt =
    copy(crc32 = Sync.getChecksum(cu), update = cu)
  lazy val capacity: MilliSatoshi = update.htlcMaximumMsat
}

object Router {
  val defAvgHopParams = AvgHopParams(
    CltvExpiryDelta(144),
    feeProportionalMillionths = 500L,
    feeBaseMsat = MilliSatoshi(1000L),
    sampleSize = 1
  )

  case class NodeDirectionDesc(from: PublicKey, to: PublicKey)

  case class ChannelDesc(
      shortChannelId: ShortChannelId,
      from: PublicKey,
      to: PublicKey
  ) {
    def toDirection: NodeDirectionDesc = NodeDirectionDesc(from, to)
  }

  case class RouterConf(
      initRouteMaxLength: Int,
      routeMaxCltv: CltvExpiryDelta,
      maxDirectionFailures: Int = 3,
      maxStrangeNodeFailures: Int = 6,
      maxRemoteAttempts: Int = 6
  )

  case class PublicChannel(
      update1Opt: Option[ChannelUpdateExt],
      update2Opt: Option[ChannelUpdateExt],
      ann: ChannelAnnouncement
  ) {
    def getChannelUpdateSameSideAs(
        cu: ChannelUpdate
    ): Option[ChannelUpdateExt] =
      if (cu.position == 1) update1Opt else update2Opt
  }

  trait Hop {
    def nodeId: PublicKey
    def nextNodeId: PublicKey
    def fee(amount: MilliSatoshi): MilliSatoshi
    def cltvExpiryDelta: CltvExpiryDelta
  }

  case class ChannelHop(edge: GraphEdge) extends Hop {
    override def fee(amount: MilliSatoshi): MilliSatoshi = nodeFee(
      edge.updExt.update.feeBaseMsat,
      edge.updExt.update.feeProportionalMillionths,
      amount
    )
    override val cltvExpiryDelta: CltvExpiryDelta =
      edge.updExt.update.cltvExpiryDelta
    override val nextNodeId: PublicKey = edge.desc.to
    override val nodeId: PublicKey = edge.desc.from

    override def toString: String = {
      val base = edge.updExt.update.feeBaseMsat
      val ppm = edge.updExt.update.feeProportionalMillionths
      val sid = edge.desc.shortChannelId.toString
      s"node: ${nodeId}, base: $base, ppm: $ppm, cltv: ${cltvExpiryDelta.toInt}, sid: $sid, next node: ${nextNodeId}"
    }
  }

  case class NodeHop(
      nodeId: PublicKey,
      nextNodeId: PublicKey,
      cltvExpiryDelta: CltvExpiryDelta,
      fee: MilliSatoshi
  ) extends Hop {
    override def toString: String =
      s"Trampoline, node: ${nodeId}, fee reserve: $fee, cltv reserve: ${cltvExpiryDelta.toInt}, next node: ${nextNodeId}"
    override def fee(amount: MilliSatoshi): MilliSatoshi = fee
  }

  case class RouteParams(
      feeReserve: MilliSatoshi,
      routeMaxLength: Int,
      routeMaxCltv: CltvExpiryDelta
  )

  case class RouteRequest(
      fullTag: FullPaymentTag,
      partId: ByteVector,
      source: PublicKey,
      target: PublicKey,
      amount: MilliSatoshi,
      localEdge: GraphEdge,
      routeParams: RouteParams,
      ignoreNodes: Set[PublicKey] = Set.empty,
      ignoreChannels: Set[ChannelDesc] = Set.empty,
      ignoreDirections: Set[NodeDirectionDesc] = Set.empty
  )

  type RoutedPerHop = (MilliSatoshi, ChannelHop)

  case class Route(hops: Seq[ChannelHop], weight: RichWeight) {
    lazy val fee: MilliSatoshi = weight.costs.head - weight.costs.last

    lazy val routedPerHop: Seq[RoutedPerHop] = weight.costs.tail.zip(hops.tail)

    def getEdgeForNode(nodeId: PublicKey): Option[GraphEdge] =
      routedPerHop.secondItems.collectFirst {
        case chanHop if nodeId == chanHop.nodeId => chanHop.edge
      }

    def asString: String = routedPerHop.secondItems
      .map(_.toString)
      .mkString(
        s"${weight.costs.head}\n->\n",
        "\n->\n",
        s"\n->\n${weight.costs.last}\n---\nroute fee: $fee"
      )

    require(hops.nonEmpty, "Route cannot be empty")
  }

  sealed trait RouteResponse { def fullTag: FullPaymentTag }
  case class NoRouteAvailable(fullTag: FullPaymentTag, partId: ByteVector)
      extends RouteResponse
  case class RouteFound(
      route: Route,
      fullTag: FullPaymentTag,
      partId: ByteVector
  ) extends RouteResponse

  case class Data(
      channels: Map[ShortChannelId, PublicChannel],
      hostedChannels: Map[ShortChannelId, PublicChannel],
      graph: DirectedGraph
  ) {
    // This is a costly computation so keep it lazy and only calculate it once on first request
    lazy val avgHopParams: AvgHopParams = if (channels.nonEmpty) {
      val sample = channels.values.flatMap(pc => pc.update1Opt ++ pc.update2Opt)
      getAvgHopParams(sample.toVector)
    } else defAvgHopParams
  }

  def getDesc(cu: ChannelUpdate, ann: ChannelAnnouncement): ChannelDesc = {
    if (cu.channelFlags.isNode1)
      ChannelDesc(cu.shortChannelId, ann.nodeId1, ann.nodeId2)
    else ChannelDesc(cu.shortChannelId, ann.nodeId2, ann.nodeId1)
  }

  def getAvgHopParams(sample: Seq[ChannelUpdateExt] = Nil): AvgHopParams = {
    val feeBaseMedian =
      Statistics.medianBy(sample, skew = 0.6d)(_.update.feeBaseMsat.toLong)
    val feeProportionalMedian = Statistics.medianBy(sample, skew = 0.75d)(
      _.update.feeProportionalMillionths
    )
    val cltvMedian = CltvExpiryDelta(
      Statistics
        .medianBy(sample)(_.update.cltvExpiryDelta.toInt)
        .toInt max 144
    )
    AvgHopParams(
      cltvMedian,
      feeProportionalMedian max 1,
      MilliSatoshi(feeBaseMedian) max MilliSatoshi(1000L),
      sample.size
    )
  }
}
