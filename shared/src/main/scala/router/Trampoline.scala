package immortan.router

import scodec.codecs._

import scoin._
import scoin.Crypto.PublicKey
import scoin.CommonCodecs._
import scoin.ln._

sealed trait HasRelayFee {
  def relayFee(amount: MilliSatoshi): MilliSatoshi
  def cltvExpiryDelta: CltvExpiryDelta
}

case class TrampolineOn(
    minMsat: MilliSatoshi,
    maxMsat: MilliSatoshi,
    feeProportionalMillionths: Long,
    exponent: Double,
    logExponent: Double,
    cltvExpiryDelta: CltvExpiryDelta
) extends HasRelayFee {
  def relayFee(amount: MilliSatoshi): MilliSatoshi = trampolineFee(
    proportionalFee(amount, feeProportionalMillionths).toLong,
    exponent,
    logExponent
  )
}

object TrampolineOn {
  val codec = (
    ("minMsat" | millisatoshi) ::
      ("maxMsat" | millisatoshi) ::
      ("feeProportionalMillionths" | uint32) ::
      ("exponent" | double) ::
      ("logExponent" | double) ::
      ("cltvExpiryDelta" | cltvExpiryDelta)
  ).as[TrampolineOn]
}

case class AvgHopParams(
    cltvExpiryDelta: CltvExpiryDelta,
    feeProportionalMillionths: Long,
    feeBaseMsat: MilliSatoshi,
    sampleSize: Long
) extends HasRelayFee {
  def relayFee(amount: MilliSatoshi): MilliSatoshi =
    nodeFee(feeBaseMsat, feeProportionalMillionths, amount)
}

case class NodeIdTrampolineParams(nodeId: PublicKey, trampolineOn: TrampolineOn)
    extends HasRelayFee {
  def relayFee(amount: MilliSatoshi): MilliSatoshi =
    trampolineOn.relayFee(amount)
  def cltvExpiryDelta: CltvExpiryDelta = trampolineOn.cltvExpiryDelta

  def withRefreshedParams(
      update: TrampolineStatusUpdate
  ): NodeIdTrampolineParams = {
    val trampolineOn1 = update.updatedParams.getOrElse(nodeId, trampolineOn)
    copy(trampolineOn = trampolineOn1)
  }
}

object NodeIdTrampolineParams {
  val codec = (
    ("nodeId" | publickey) ::
      ("trampolineOn" | TrampolineOn.codec)
  ).as[NodeIdTrampolineParams]

  val routeCodec = {
    val innerList = listOfN(uint16, NodeIdTrampolineParams.codec)
    listOfN(valueCodec = innerList, countCodec = uint16)
  }
}

object TrampolineStatus {
  type NodeIdTrampolineParamsRoute = List[NodeIdTrampolineParams]
}

trait TrampolineStatus extends LightningMessage

case object TrampolineUndesired extends TrampolineStatus

case class TrampolineStatusInit(
    routes: List[TrampolineStatus.NodeIdTrampolineParamsRoute],
    peerParams: TrampolineOn
) extends TrampolineStatus

object TrampolineStatusInit {
  val codec = (
    ("routes" | NodeIdTrampolineParams.routeCodec) ::
      ("peerParams" | TrampolineOn.codec)
  ).as[TrampolineStatusInit]
}

case class TrampolineStatusUpdate(
    newRoutes: List[TrampolineStatus.NodeIdTrampolineParamsRoute],
    updatedParams: Map[PublicKey, TrampolineOn],
    updatedPeerParams: Option[TrampolineOn],
    removed: Set[PublicKey] = Set.empty
) extends TrampolineStatus

object TrampolineStatusUpdate {
  val codec = (
    ("newRoutes" | NodeIdTrampolineParams.routeCodec) ::
      ("updatedParams" | mapCodec(publickey, TrampolineOn.codec)) ::
      ("updatedPeerParams" | optional(bool, TrampolineOn.codec)) ::
      ("removed" | setCodec(publickey))
  ).as[TrampolineStatusUpdate]
}

case class TrampolineRoutingState(
    routes: Set[TrampolineStatus.NodeIdTrampolineParamsRoute] = Set.empty,
    peerParams: NodeIdTrampolineParams
) {
  lazy val completeRoutes: Set[TrampolineStatus.NodeIdTrampolineParamsRoute] =
    routes.map(peerParams :: _)

  def merge(
      peerId: PublicKey,
      that: TrampolineStatusUpdate
  ): TrampolineRoutingState = {
    def isHopRemoved(hop: NodeIdTrampolineParams): Boolean =
      that.removed.contains(hop.nodeId)
    val peerParams1 =
      for (trampolineOn <- that.updatedPeerParams)
        yield NodeIdTrampolineParams(peerId, trampolineOn)
    val routes1 = (routes ++ that.newRoutes)
      .filterNot(_ exists isHopRemoved)
      .filter(_.nonEmpty)
      .filter(_.size < 3)
      .take(5)
    val routes2 =
      for (route <- routes1) yield route.map(_ withRefreshedParams that)
    copy(routes = routes2, peerParams = peerParams1 getOrElse peerParams)
  }
}

case class TrampolineRoutingStates(
    states: Map[PublicKey, TrampolineRoutingState] = Map.empty
) {
  def init(
      peerId: PublicKey,
      init: TrampolineStatusInit
  ): TrampolineRoutingStates = {
    val peerParams = NodeIdTrampolineParams(nodeId = peerId, init.peerParams)
    val state = TrampolineRoutingState(init.routes.toSet, peerParams)
    val states1 = states.updated(peerId, state)
    copy(states = states1)
  }

  def merge(
      peerId: PublicKey,
      that: TrampolineStatusUpdate
  ): TrampolineRoutingStates = {
    val state1 = states(peerId).merge(peerId, that)
    val states1 = states.updated(peerId, state1)
    copy(states = states1)
  }
}
