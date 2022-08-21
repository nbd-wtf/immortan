package immortan.channel

import scoin.ln.{Feature, FeatureScope}
import scoin.ln.Features.{ResizeableHostedChannels, StaticRemoteKey}
import scoin.ln.transactions.Transactions.{
  CommitmentFormat,
  DefaultCommitmentFormat
}

case class ChannelFeatures(
    activated: Set[Feature with FeatureScope] = Set.empty
) {
  def hasFeature(feature: Feature with FeatureScope): Boolean =
    activated.contains(feature)

  lazy val paysDirectlyToWallet: Boolean = hasFeature(StaticRemoteKey)
  lazy val hostedResizeable: Boolean = hasFeature(ResizeableHostedChannels)
  lazy val commitmentFormat: CommitmentFormat = DefaultCommitmentFormat
}

object ChannelFeatures {
  def apply(features: Feature with FeatureScope*): ChannelFeatures =
    ChannelFeatures(features.toSet)
}
