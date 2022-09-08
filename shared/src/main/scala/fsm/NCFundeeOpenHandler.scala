package immortan.fsm

import scoin.ln._
import scoin.ln.Features.StaticRemoteKey

import immortan._
import immortan.Channel
import immortan.ChannelListener.{Malfunction, Transition}
import immortan.channel._

abstract class NCFundeeOpenHandler(
    info: RemoteNodeInfo,
    theirOpen: OpenChannel,
    cm: ChannelMaster
) {
  // Important: this must be initiated when chain tip is actually known
  def onEstablished(cs: Commitments, channel: ChannelNormal): Unit
  def onFailure(err: Throwable): Unit

  private val freshChannel = new ChannelNormal(cm.chanBag) {
    def SEND(messages: LightningMessage*): Unit =
      CommsTower.sendMany(messages, info.nodeSpecificPair, NormalChannelKind)
    def STORE(normalData: PersistentChannelData): PersistentChannelData =
      cm.chanBag.put(normalData)
  }

  private val makeChanListener = new ConnectionListener with ChannelListener {
    override def onDisconnect(worker: CommsTower.Worker): Unit =
      CommsTower.rmListenerNative(info, this)

    override def onMessage(
        worker: CommsTower.Worker,
        message: LightningMessage
    ): Unit = message match {
      case msg: HasTemporaryChannelId
          if msg.temporaryChannelId == theirOpen.temporaryChannelId =>
        freshChannel process msg
      case msg: HasChannelId if msg.channelId == theirOpen.temporaryChannelId =>
        freshChannel process msg
      case _ => // Do nothing to avoid conflicts
    }

    override def onOperational(
        worker: CommsTower.Worker,
        theirInit: Init
    ): Unit = {
      val localParams =
        LNParams.makeChannelParams(isFunder = false, theirOpen.fundingSatoshis)
      freshChannel process INPUT_INIT_FUNDEE(
        info.safeAlias,
        localParams,
        theirInit,
        ChannelFeatures(StaticRemoteKey),
        theirOpen
      )
    }

    override def onBecome: PartialFunction[Transition, Unit] = {
      case (
            _,
            _,
            data: DATA_WAIT_FOR_FUNDING_CONFIRMED,
            Channel.WaitForAccept,
            Channel.WaitFundingDone
          ) =>
        // It is up to NC to store itself and communicate successful opening
        onEstablished(data.commitments, freshChannel)
        CommsTower.rmListenerNative(info, this)
    }

    override def onException: PartialFunction[Malfunction, Unit] = {
      // Something went wrong while trying to establish a channel

      case (openingPhaseError, _, _) =>
        CommsTower.rmListenerNative(info, this)
        onFailure(openingPhaseError)
    }
  }

  freshChannel.listeners = Set(makeChanListener)
  CommsTower.listenNative(Set(makeChanListener), info)
}
