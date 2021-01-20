/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{GraphStructure, RichWeight}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import scodec.bits.ByteVector


case class ChannelUpdateExt(update: ChannelUpdate, crc32: Long, score: Long, useHeuristics: Boolean) {
  def withNewUpdate(cu: ChannelUpdate): ChannelUpdateExt = copy(crc32 = Sync.getChecksum(cu), update = cu)
}

object Router {
  case class RouterConf(searchMaxFeeBase: MilliSatoshi, searchMaxFeePct: Double, firstPassMaxRouteLength: Int,
                        firstPassMaxCltv: CltvExpiryDelta, mppMinPartAmount: MilliSatoshi, maxChannelFailures: Int,
                        maxStrangeNodeFailures: Int, maxRemoteAttempts: Int)

  case class ChannelDesc(shortChannelId: ShortChannelId, from: PublicKey, to: PublicKey)

  case class PublicChannel(update1Opt: Option[ChannelUpdateExt], update2Opt: Option[ChannelUpdateExt], ann: ChannelAnnouncement) {
    def getChannelUpdateSameSideAs(cu: ChannelUpdate): Option[ChannelUpdateExt] = if (cu.position == ChannelUpdate.POSITION1NODE) update1Opt else update2Opt
  }

  case class AssistedChannel(extraHop: ExtraHop, nextNodeId: PublicKey)

  case class RouteParams(maxFeeBase: MilliSatoshi, maxFeePct: Double, routeMaxLength: Int, routeMaxCltv: CltvExpiryDelta) {
    def getMaxFee(amount: MilliSatoshi): MilliSatoshi = maxFeeBase.max(amount * maxFeePct)
  }

  case class RouteRequest(paymentHash: ByteVector32, partId: ByteVector, source: PublicKey,
                          target: PublicKey, amount: MilliSatoshi, localEdge: GraphEdge, routeParams: RouteParams,
                          ignoreNodes: Set[PublicKey] = Set.empty, ignoreChannels: Set[ChannelDesc] = Set.empty) {

    // Once temporary channel error occurs we remember that channel as failed-at-amount, it can later be retried again for a new and smaller shard amount
    // However, there needs to be a delta between failed-at-amount and smaller shard amount, otherwise undesired retries like 1003sat/1002sat/... can happen
    lazy val leeway: MilliSatoshi = amount / 8
  }

  type PaymentDescCapacity = (MilliSatoshi, GraphStructure.DescAndCapacity)

  case class Route(weight: RichWeight, hops: Seq[GraphEdge] = Nil) {
    require(hops.nonEmpty, "Route cannot be empty")

    lazy val fee: MilliSatoshi = weight.costs.head - weight.costs.last

    lazy val amountPerDescAndCap: Seq[PaymentDescCapacity] = weight.costs.tail zip hops.tail.map(_.toDescAndCapacity) // We don't care about first route and amount since it belongs to local channel

    def getEdgeForNode(nodeId: PublicKey): Option[GraphEdge] = hops.find(_.desc.from == nodeId) // This method retrieves the edge that we used when we built the route
  }

  sealed trait RouteResponse { def paymentHash: ByteVector32 }

  case class RouteFound(paymentHash: ByteVector32, partId: ByteVector, route: Route) extends RouteResponse

  case class NoRouteAvailable(paymentHash: ByteVector32, partId: ByteVector) extends RouteResponse

  case class Data(channels: Map[ShortChannelId, PublicChannel], hostedChannels: Map[ShortChannelId, PublicChannel], extraEdges: Map[ShortChannelId, GraphEdge], graph: DirectedGraph)

  def getDesc(cu: ChannelUpdate, ann: ChannelAnnouncement): ChannelDesc =
    if (Announcements isNode1 cu.channelFlags) ChannelDesc(cu.shortChannelId, ann.nodeId1, ann.nodeId2)
    else ChannelDesc(cu.shortChannelId, ann.nodeId2, ann.nodeId1)
}