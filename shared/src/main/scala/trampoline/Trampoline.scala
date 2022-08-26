package immortan.trampoline

import scoin._
import scoin.Crypto.PublicKey
import scoin.ln._

import Helpers._

object Helpers {
  def proportionalFee(
      paymentAmount: MilliSatoshi,
      proportionalFee: Long
  ): MilliSatoshi = (paymentAmount * proportionalFee) / 1000000

  def nodeFee(
      baseFee: MilliSatoshi,
      proportionalRatio: Long,
      paymentAmount: MilliSatoshi
  ): MilliSatoshi = baseFee + proportionalFee(paymentAmount, proportionalRatio)

  // proportional^(exponent = 1) + ln(proportional)^(logExponent = 0) is linear
  // proportional^(exponent = 0.82) + ln(proportional)^(logExponent = 2.2) gives moderate discounts
  // proportional^(exponent = 0.79) + ln(proportional)^(logExponent = 2.1) gives substantial discounts for large amounts
  // proportional^(exponent = 0.76) + ln(proportional)^(logExponent = 2.0) gives extremely large discounts for large amounts
  // proportional^(exponent = 0) + ln(proportional)^(logExponent = 0) gives base + 2 msat, independent of payment amount
  def trampolineFee(
      proportional: Long,
      exponent: Double,
      logExponent: Double
  ): MilliSatoshi = {
    val nonLinearFeeMsat = math.pow(proportional, exponent) + math.pow(
      math.log(proportional),
      logExponent
    )
    MilliSatoshi(nonLinearFeeMsat.ceil.toLong)
  }
}

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

object TrampolineStatus {
  type NodeIdTrampolineParamsRoute = List[NodeIdTrampolineParams]
}

trait TrampolineStatus extends LightningMessage

case object TrampolineUndesired extends TrampolineStatus

case class TrampolineStatusInit(
    routes: List[TrampolineStatus.NodeIdTrampolineParamsRoute],
    peerParams: TrampolineOn
) extends TrampolineStatus

case class TrampolineStatusUpdate(
    newRoutes: List[TrampolineStatus.NodeIdTrampolineParamsRoute],
    updatedParams: Map[PublicKey, TrampolineOn],
    updatedPeerParams: Option[TrampolineOn],
    removed: Set[PublicKey] = Set.empty
) extends TrampolineStatus

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
