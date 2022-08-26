import scoin.Crypto

package object immortan {
  def randomKey: Crypto.PrivateKey = Crypto.PrivateKey(Crypto.randomBytes(32))

  sealed trait ChannelKind
  case object IrrelevantChannelKind extends ChannelKind
  case object HostedChannelKind extends ChannelKind
  case object NormalChannelKind extends ChannelKind
}
