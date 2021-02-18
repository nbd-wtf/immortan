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

package fr.acinq.eclair

import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import scodec.bits.{BitVector, ByteVector}

/**
 * Created by PM on 13/02/2017.
 */

sealed trait FeatureSupport

object FeatureSupport {
  case object Mandatory extends FeatureSupport { override def toString: String = "mandatory" }
  case object Optional extends FeatureSupport { override def toString: String = "optional" }
}

trait Feature {
  def mandatory: Int
  def optional: Int = mandatory + 1

  def isCritical: Boolean = false
  def isDesired: Boolean = false

  def name: String

  def supportBit(support: FeatureSupport): Int = support match {
    case FeatureSupport.Mandatory => mandatory
    case FeatureSupport.Optional => optional
  }
}

case class ActivatedFeature(feature: Feature, support: FeatureSupport)

case class UnknownFeature(bitIndex: Int)

case class Features(activated: Set[ActivatedFeature], unknown: Set[UnknownFeature] = Set.empty) {
  def hasFeature(feature: Feature, support: Option[FeatureSupport] = None): Boolean = support match {
    case Some(s) => activated.contains(ActivatedFeature(feature, s))
    case None => hasFeature(feature, Some(Optional)) || hasFeature(feature, Some(Mandatory))
  }

  def areSupported(remoteFeatures: Features): Boolean = {
    // we allow unknown odd features (it's ok to be odd)
    val unknownFeaturesOk = remoteFeatures.unknown.forall(_.bitIndex % 2 == 1)
    // we verify that we activated every mandatory feature they require
    val knownFeaturesOk = remoteFeatures.activated.forall {
      case ActivatedFeature(_, Optional) => true
      case ActivatedFeature(feature, Mandatory) => hasFeature(feature)
    }
    unknownFeaturesOk && knownFeaturesOk
  }

  def toByteVector: ByteVector = {
    val activatedFeatureBytes = toByteVectorFromIndex(activated.map { case ActivatedFeature(f, s) => f.supportBit(s) })
    val unknownFeatureBytes = toByteVectorFromIndex(unknown.map(_.bitIndex))
    val maxSize = activatedFeatureBytes.size.max(unknownFeatureBytes.size)
    activatedFeatureBytes.padLeft(maxSize) | unknownFeatureBytes.padLeft(maxSize)
  }

  private def toByteVectorFromIndex(indexes: Set[Int]): ByteVector = {
    if (indexes.isEmpty) return ByteVector.empty
    // When converting from BitVector to ByteVector, scodec pads right instead of left, so we make sure we pad to bytes *before* setting feature bits.
    var buf = BitVector.fill(indexes.max + 1)(high = false).bytes.bits
    indexes.foreach { i =>
      buf = buf.set(i)
    }
    buf.reverse.bytes
  }
}

object Features {

  def empty: Features = Features(Set.empty[ActivatedFeature])

  def apply(features: Set[ActivatedFeature]): Features = Features(activated = features)

  def apply(bytes: ByteVector): Features = apply(bytes.bits)

  def apply(bits: BitVector): Features = {
    val all = bits.toIndexedSeq.reverse.zipWithIndex.collect {
      case (true, idx) if knownFeatures.exists(_.optional == idx) => Right(ActivatedFeature(knownFeatures.find(_.optional == idx).get, Optional))
      case (true, idx) if knownFeatures.exists(_.mandatory == idx) => Right(ActivatedFeature(knownFeatures.find(_.mandatory == idx).get, Mandatory))
      case (true, idx) => Left(UnknownFeature(idx))
    }
    Features(
      activated = all.collect { case Right(af) => af }.toSet,
      unknown = all.collect { case Left(inf) => inf }.toSet
    )
  }

  case object OptionDataLossProtect extends Feature {
    override def isCritical: Boolean = true
    val name = "Basic data loss protect"
    val mandatory = 0
  }

  case object InitialRoutingSync extends Feature {
    val name = "initial_routing_sync"
    val mandatory = 2
  }

  case object ChannelRangeQueries extends Feature {
    val name = "gossip_queries"
    val mandatory = 6
  }

  case object VariableLengthOnion extends Feature {
    override def isCritical: Boolean = true
    val name = "Modern onion format"
    val mandatory = 8
  }

  case object ChannelRangeQueriesExtended extends Feature {
    override def isDesired: Boolean = true
    val name = "Extended gossip queries"
    val mandatory = 10
  }

  case object StaticRemoteKey extends Feature {
    override def isCritical: Boolean = true
    val name = "Direct wallet refund"
    val mandatory = 12
  }

  case object PaymentSecret extends Feature {
    val name = "payment_secret"
    val mandatory = 14
  }

  case object BasicMultiPartPayment extends Feature {
    override def isCritical: Boolean = true
    val name = "Multipart payments"
    val mandatory = 16
  }

  case object Wumbo extends Feature {
    override def isDesired: Boolean = true
    val name = "Large channels"
    val mandatory = 18
  }

  case object AnchorOutputs extends Feature {
    override def isCritical: Boolean = true
    val name = "Anchor channels"
    val mandatory = 20
  }

  case object TrampolinePayment extends Feature {
    val name = "trampoline_payment"
    val mandatory = 50
  }

  case object ChainSwap extends Feature {
    override def isDesired: Boolean = true
    val name = "Chain swaps"
    val mandatory = 32770
  }

  case object HostedChannels extends Feature {
    val name = "Hosted channels"
    val mandatory = 32772
  }

  case object PrivateRouting extends Feature {
    override def isDesired: Boolean = true
    val name = "Private channel routing"
    val mandatory = 32774
  }

  val visibleFeatures: Set[Feature] = Set(
    ChannelRangeQueriesExtended,
    BasicMultiPartPayment,
    OptionDataLossProtect,
    VariableLengthOnion,
    StaticRemoteKey,
    PrivateRouting,
    HostedChannels,
    AnchorOutputs,
    ChainSwap,
    Wumbo
  )

  val knownFeatures: Set[Feature] = Set(ChannelRangeQueries, InitialRoutingSync, TrampolinePayment) ++ visibleFeatures

  // Features may depend on other features, as specified in Bolt 9.
  private val featuresDependency = Map(
    ChannelRangeQueriesExtended -> (ChannelRangeQueries :: Nil),
    PaymentSecret -> (VariableLengthOnion :: Nil),
    BasicMultiPartPayment -> (PaymentSecret :: Nil),
    AnchorOutputs -> (StaticRemoteKey :: Nil),
    TrampolinePayment -> (PaymentSecret :: Nil)
  )

  case class FeatureException(message: String) extends IllegalArgumentException(message)

  def validateFeatureGraph(features: Features): Option[FeatureException] = featuresDependency.collectFirst {
    case (feature, dependencies) if features.hasFeature(feature) && dependencies.exists(d => !features.hasFeature(d)) =>
      FeatureException(s"$feature is set but is missing a dependency (${dependencies.filter(d => !features.hasFeature(d)).mkString(" and ")})")
  }

  /** Returns true if both feature sets are compatible. */
  def areCompatible(ours: Features, theirs: Features): Boolean = ours.areSupported(theirs) && theirs.areSupported(ours)

  /** returns true if both have at least optional support */
  def canUseFeature(localFeatures: Features, remoteFeatures: Features, feature: Feature): Boolean = {
    localFeatures.hasFeature(feature) && remoteFeatures.hasFeature(feature)
  }
}