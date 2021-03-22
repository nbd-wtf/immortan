package immortan.fsm

import immortan.{ChannelHosted, ChannelListener, ChannelMaster, CommsTower, ConnectionListener, HostedCommits, RemoteNodeInfo, StorageFormat, WaitRemoteHostedReply}
import fr.acinq.eclair.wire.{HasChannelId, HasTemporaryChannelId, HostedChannelMessage, Init, LightningMessage, LightningMessageCodecs}
import fr.acinq.eclair.channel.{CMD_SOCKET_ONLINE, PersistentChannelData}
import immortan.Channel.{OPEN, SUSPENDED, WAIT_FOR_ACCEPT}
import immortan.ChannelListener.{Malfunction, Transition}
import fr.acinq.bitcoin.ByteVector32
import scodec.bits.ByteVector
import immortan.crypto.Tools


// Important: this must be initiated when chain tip is actually known
abstract class HCOpenHandler(info: RemoteNodeInfo, format: StorageFormat, cm: ChannelMaster) {
  val channelId: ByteVector32 = Tools.hostedChanId(info.nodeSpecificPubKey.value, info.nodeId.value)
  val peerSpecificSecret: ByteVector32 = format.attachedChannelSecret(theirNodeId = info.nodeId)
  val peerSpecificRefundPubKey: ByteVector = format.keys.refundPubKey(theirNodeId = info.nodeId)

  val freshChannel: ChannelHosted = new ChannelHosted {
    def SEND(msgs: LightningMessage*): Unit = CommsTower.sendMany(msgs.map(LightningMessageCodecs.prepareNormal), info.nodeSpecificPair)
    def STORE(hostedData: PersistentChannelData): PersistentChannelData = cm.chanBag.put(hostedData)
  }

  def onPeerDisconnect(worker: CommsTower.Worker): Unit
  def onEstablished(channel: ChannelHosted): Unit
  def onFailure(err: Throwable): Unit

  private val makeChanListener = new ConnectionListener with ChannelListener {
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = freshChannel process CMD_SOCKET_ONLINE
    override def onHostedMessage(worker: CommsTower.Worker, message: HostedChannelMessage): Unit = freshChannel process message

    override def onMessage(worker: CommsTower.Worker, message: LightningMessage): Unit = message match {
      case msg: HasTemporaryChannelId if msg.temporaryChannelId == channelId => freshChannel process message
      case msg: HasChannelId if msg.channelId == channelId => freshChannel process message
      case _ => // Do nothing to avoid conflicts
    }

    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      // Peer has disconnected during HC opening process
      onPeerDisconnect(worker)
      rmTempListener
    }

    override def onBecome: PartialFunction[Transition, Unit] = {
      case (_, _, commits: HostedCommits, WAIT_FOR_ACCEPT, OPEN | SUSPENDED) =>
        // It is up to HC to store itself and communicate successful opening
        cm.implantChannel(commits, freshChannel)
        onEstablished(freshChannel)
        rmTempListener
    }

    override def onException: PartialFunction[Malfunction, Unit] = {
      // Something went wrong while trying to establish a channel

      case (_, error) =>
        onFailure(error)
        rmTempListener
    }
  }

  freshChannel.listeners = Set(makeChanListener)
  freshChannel doProcess WaitRemoteHostedReply(info, peerSpecificRefundPubKey, peerSpecificSecret) // Prepare empty HC with appropriate data
  CommsTower.listen(Set(makeChanListener, cm.sockBrandingBridge), info.nodeSpecificPair, info) // Connect or fire listeners if connected already
  def rmTempListener: Unit = CommsTower.listeners(info.nodeSpecificPair) -= makeChanListener
}
