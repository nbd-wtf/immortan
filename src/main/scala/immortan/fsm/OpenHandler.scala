package immortan.fsm

import immortan._
import fr.acinq.eclair.channel.{CMD_CHAIN_TIP_KNOWN, CMD_SOCKET_ONLINE, PersistentChannelData}
import fr.acinq.eclair.wire.{HostedChannelMessage, Init, LightningMessage}
import immortan.Channel.{OPEN, SUSPENDED, WAIT_FOR_ACCEPT}
import immortan.ChannelListener.{Malfunction, Transition}
import fr.acinq.bitcoin.ByteVector32
import scodec.bits.ByteVector


abstract class OpenHandler(ext: NodeAnnouncementExt, ourInit: Init, format: StorageFormat, cm: ChannelMaster) {
  val peerSpecificSecret: ByteVector32 = format.attachedChannelSecret(theirNodeId = ext.na.nodeId)
  val peerSpecificRefundPubKey: ByteVector = format.keys.refundPubKey(theirNodeId = ext.na.nodeId)

  val freshChannel: HostedChannel = new HostedChannel {
    def SEND(messages: LightningMessage *): Unit = CommsTower.sendMany(messages, ext.nodeSpecificPkap)
    def STORE(hostedData: PersistentChannelData): PersistentChannelData = cm.chanBag.put(hostedData)
  }

  def onFailure(channel: HostedChannel, err: Throwable): Unit
  def onPeerDisconnect(worker: CommsTower.Worker): Unit
  def onEstablished(channel: HostedChannel): Unit

  private val makeChanListener = new ConnectionListener with ChannelListener {
    override def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = freshChannel process msg
    override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = freshChannel process msg
    override def onDisconnect(worker: CommsTower.Worker): Unit = onPeerDisconnect(worker)

    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      freshChannel process CMD_CHAIN_TIP_KNOWN
      freshChannel process CMD_SOCKET_ONLINE
    }

    override def onBecome: PartialFunction[Transition, Unit] = {
      case (_, _, _: HostedCommits, WAIT_FOR_ACCEPT, OPEN | SUSPENDED) =>
        freshChannel.listeners = cm.channelListeners // Add standard channel listeners to new established channel
        CommsTower.listeners(ext.nodeSpecificPkap) -= this // Stop sending messages from this connection listener
        cm.all :+= freshChannel // Put this channel to vector of established channels
        cm.initConnect // Add standard connection listeners for this peer

        // Inform user about new channel
        onEstablished(freshChannel)
    }

    override def onException: PartialFunction[Malfunction, Unit] = {
      // Something went wrong while trying to establish a channel
      case (_, err) => onFailure(freshChannel, err)
    }
  }

  freshChannel.listeners += makeChanListener
  CommsTower.listen(Set(makeChanListener, cm.sockBrandingBridge), ext.nodeSpecificPkap, ext.na, ourInit)
  freshChannel doProcess WaitRemoteHostedReply(ext, peerSpecificRefundPubKey, peerSpecificSecret)
}
