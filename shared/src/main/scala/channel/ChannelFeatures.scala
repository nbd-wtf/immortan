package immortan.channel

import scoin.ln.Feature
import scoin.ln.Features.StaticRemoteKey

import immortan.channel.Transactions.{CommitmentFormat, DefaultCommitmentFormat}

case class ChannelFeatures(
    activated: Set[Feature] = Set.empty
) {
  def hasFeature(feature: Feature): Boolean =
    activated.contains(feature)

  lazy val paysDirectlyToWallet: Boolean = hasFeature(StaticRemoteKey)
  lazy val commitmentFormat: CommitmentFormat = DefaultCommitmentFormat
}

object ChannelFeatures {
  def apply(features: Feature*): ChannelFeatures =
    ChannelFeatures(features.toSet)
}
