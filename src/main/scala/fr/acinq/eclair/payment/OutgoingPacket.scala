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

package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, randomKey}
import scodec.bits.ByteVector
import scodec.Attempt

/**
  * Created by t-bast on 08/10/2019.
  */

/** Helpers to create outgoing payment packets. */
object OutgoingPacket {

  /**
    * Build an encrypted onion packet from onion payloads and node public keys.
    */
  def buildOnion[T <: Onion.PacketType](packetType: Sphinx.OnionRoutingPacket[T])(nodes: Seq[PublicKey], payloads: Seq[Onion.PerHopPayload], associatedData: ByteVector32): Sphinx.PacketAndSecrets = {
    require(nodes.size == payloads.size)
    val sessionKey = randomKey
    val payloadsBin: Seq[ByteVector] = payloads
      .map {
        case p: Onion.FinalPayload => OnionCodecs.finalPerHopPayloadCodec.encode(p)
        case p: Onion.ChannelRelayPayload => OnionCodecs.channelRelayPerHopPayloadCodec.encode(p)
        case p: Onion.NodeRelayPayload => OnionCodecs.nodeRelayPerHopPayloadCodec.encode(p)
      }
      .map {
        case Attempt.Successful(bitVector) => bitVector.bytes
        case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
      }
    packetType.create(sessionKey, nodes, payloadsBin, associatedData)
  }

  /**
    * Build the onion payloads for each hop.
    *
    * @param hops         the hops as computed by the router + extra routes from payment request
    * @param finalPayload payload data for the final node (amount, expiry, etc)
    * @return a (firstAmount, firstExpiry, payloads) tuple where:
    *         - firstAmount is the amount for the first htlc in the route
    *         - firstExpiry is the cltv expiry for the first htlc in the route
    *         - a sequence of payloads that will be used to build the onion
    */
  def buildPayloads(hops: Seq[GraphEdge], finalPayload: Onion.FinalPayload): (MilliSatoshi, CltvExpiry, Seq[Onion.PerHopPayload]) = {
    hops.reverse.foldLeft((finalPayload.amount, finalPayload.expiry, Seq[Onion.PerHopPayload](finalPayload))) {
      case ((amount, expiry, payloads), hop) =>
        val payload = Onion.createNodeRelayPayload(amount, expiry, hop.desc.from)
        (amount + hop.fee(amount), expiry + hop.updExt.update.cltvExpiryDelta, payload +: payloads)
    }
  }

  /**
    * Build an encrypted onion packet with the given final payload.
    *
    * @param hops         the hops as computed by the router + extra routes from payment request, including ourselves in the first hop
    * @param finalPayload payload data for the final node (amount, expiry, etc)
    * @return a (firstAmount, firstExpiry, onion) tuple where:
    *         - firstAmount is the amount for the first htlc in the route
    *         - firstExpiry is the cltv expiry for the first htlc in the route
    *         - the onion to include in the HTLC
    */
  def buildPacket[T <: Onion.PacketType](packetType: Sphinx.OnionRoutingPacket[T])(paymentHash: ByteVector32, hops: Seq[GraphEdge], finalPayload: Onion.FinalPayload): (MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets) = {
    val (firstAmount, firstExpiry, payloads) = buildPayloads(hops.drop(1), finalPayload)
    val nodes = hops.map(_.desc.to)
    // BOLT 2 requires that associatedData == paymentHash
    val onion = buildOnion(packetType)(nodes, payloads, paymentHash)
    (firstAmount, firstExpiry, onion)
  }
}
