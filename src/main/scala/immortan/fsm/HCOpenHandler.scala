package immortan.fsm

import immortan._
import fr.acinq.eclair.wire.{LightningMessageCodecs, HostedChannelMessage, Init, LightningMessage}
import fr.acinq.eclair.channel.{CMD_SOCKET_ONLINE, PersistentChannelData}
import immortan.Channel.{OPEN, SUSPENDED, WAIT_FOR_ACCEPT}
import immortan.ChannelListener.{Malfunction, Transition}
import fr.acinq.bitcoin.ByteVector32
import scodec.bits.ByteVector


// Important: this must be initiated when chain tip is actually known
abstract class HCOpenHandler(info: RemoteNodeInfo, ourInit: Init, format: StorageFormat, cm: ChannelMaster) {
  val peerSpecificSecret: ByteVector32 = format.attachedChannelSecret(theirNodeId = info.nodeId)
  val peerSpecificRefundPubKey: ByteVector = format.keys.refundPubKey(theirNodeId = info.nodeId)

  val freshChannel: ChannelHosted = new ChannelHosted {
    def SEND(msgs: LightningMessage*): Unit = CommsTower.sendMany(msgs.map(LightningMessageCodecs.prepareNormal), info.nodeSpecificPair)
    def STORE(hostedData: PersistentChannelData): PersistentChannelData = cm.chanBag.put(hostedData)
  }

  def onFailure(channel: ChannelHosted, err: Throwable): Unit
  def onPeerDisconnect(worker: CommsTower.Worker): Unit
  def onEstablished(channel: ChannelHosted): Unit

  private val makeChanListener = new ConnectionListener with ChannelListener {
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = freshChannel process CMD_SOCKET_ONLINE
    override def onHostedMessage(worker: CommsTower.Worker, message: HostedChannelMessage): Unit = freshChannel process message
    override def onMessage(worker: CommsTower.Worker, message: LightningMessage): Unit = freshChannel process message
    override def onDisconnect(worker: CommsTower.Worker): Unit = onPeerDisconnect(worker)

    override def onBecome: PartialFunction[Transition, Unit] = {
      case (_, _, commits: HostedCommits, WAIT_FOR_ACCEPT, OPEN | SUSPENDED) =>
        CommsTower.listeners(info.nodeSpecificPair) -= this // Stop sending messages from this connection listener
        cm.all += Tuple2(commits.channelId, freshChannel) // Put this channel to vector of established channels
        freshChannel.listeners = Set(cm) // Add standard channel listeners to new established channel
        cm.initConnect // Add standard connection listeners for this peer

        // Inform user about new channel
        onEstablished(freshChannel)
    }

    override def onException: PartialFunction[Malfunction, Unit] = {
      // Something went wrong while trying to establish a channel
      case (_, err) => onFailure(freshChannel, err)
    }
  }

  freshChannel.listeners = Set(makeChanListener)
  freshChannel doProcess WaitRemoteHostedReply(info, peerSpecificRefundPubKey, peerSpecificSecret)
  CommsTower.listen(Set(makeChanListener, cm.sockBrandingBridge), info.nodeSpecificPair, info, ourInit)
}
