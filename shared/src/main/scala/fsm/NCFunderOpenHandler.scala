package immortan.fsm

import scoin.{ByteVector32, MilliSatoshi, Satoshi, FeeratePerKw, randomBytes32}
import scoin.ln._
import scoin.ln.Features.StaticRemoteKey

import immortan._
import immortan.channel._
import immortan.Channel
import immortan.ChannelListener.{Malfunction, Transition}

abstract class NCFunderOpenHandler(
    info: RemoteNodeInfo,
    fundingAmount: Satoshi,
    fundingFeeratePerKw: FeeratePerKw,
    cm: ChannelMaster
) {
  def onChanPersisted(
      data: DATA_WAIT_FOR_FUNDING_CONFIRMED,
      channel: ChannelNormal
  ): Unit
  def onAwaitFunding(data: DATA_WAIT_FOR_FUNDING_INTERNAL): Unit
  def onFailure(err: Throwable): Unit

  private val tempChannelId: ByteVector32 = randomBytes32()
  private var assignedChanId = Option.empty[ByteVector32]

  val freshChannel: ChannelNormal = new ChannelNormal(cm.chanBag) {
    def SEND(messages: LightningMessage*): Unit =
      CommsTower.sendMany(messages, info.nodeSpecificPair, NormalChannelKind)
    def STORE(normalData: PersistentChannelData): PersistentChannelData =
      cm.chanBag.put(normalData)
  }

  val makeChanListener: ConnectionListener with ChannelListener =
    new ConnectionListener with ChannelListener {
      override def onDisconnect(worker: CommsTower.Worker): Unit =
        CommsTower.rmListenerNative(info, this)

      override def onMessage(
          worker: CommsTower.Worker,
          message: LightningMessage
      ): Unit = message match {
        case msg: HasTemporaryChannelId
            if msg.temporaryChannelId == tempChannelId =>
          freshChannel process msg
        case msg: HasChannelId if assignedChanId.contains(msg.channelId) =>
          freshChannel process msg
        case msg: HasChannelId if msg.channelId == tempChannelId =>
          freshChannel process msg
        case _ =>
      }

      override def onOperational(
          worker: CommsTower.Worker,
          theirInit: Init
      ): Unit = {
        val localFunderParams =
          LNParams.makeChannelParams(isFunder = true, fundingAmount)
        val channelFeatures = ChannelFeatures(StaticRemoteKey)

        val initialFeeratePerKw =
          LNParams.feeRates.info.onChainFeeConf.feeEstimator.getFeeratePerKw(
            LNParams.feeRates.info.onChainFeeConf.feeTargets.commitmentBlockTarget
          )
        val cmd = INPUT_INIT_FUNDER(
          info.safeAlias,
          tempChannelId,
          fundingAmount,
          MilliSatoshi(0L),
          fundingFeeratePerKw,
          initialFeeratePerKw,
          localFunderParams,
          theirInit,
          OpenChannel.ChannelFlags(announceChannel = false),
          channelFeatures
        )
        freshChannel process cmd
      }

      override def onBecome: PartialFunction[Transition, Unit] = {
        case (
              _,
              _: DATA_WAIT_FOR_ACCEPT_CHANNEL,
              data: DATA_WAIT_FOR_FUNDING_INTERNAL,
              Channel.WaitForAccept,
              Channel.WaitForAccept
            ) =>
          // At this point wallet should produce a real funding tx and send it to channel
          onAwaitFunding(data)

        case (
              _,
              _: DATA_WAIT_FOR_FUNDING_INTERNAL,
              data: DATA_WAIT_FOR_FUNDING_SIGNED,
              Channel.WaitForAccept,
              Channel.WaitForAccept
            ) =>
          // Once funding tx becomes known peer will start sending messages using a real channel ID, not a temp one
          assignedChanId = Some(data.channelId)

        case (
              _,
              _,
              data: DATA_WAIT_FOR_FUNDING_CONFIRMED,
              Channel.WaitForAccept,
              Channel.WaitFundingDone
            ) =>
          // On disconnect we remove this listener from CommsTower, but retain it as channel listener
          // this ensures successful implanting if disconnect happens while funding is being published
          CommsTower.rmListenerNative(info, this)
          onChanPersisted(data, freshChannel)
      }

      override def onException: PartialFunction[Malfunction, Unit] = {
        // Something went wrong while trying to establish a new channel

        case (openingPhaseError, _, _) =>
          CommsTower.rmListenerNative(info, this)
          onFailure(openingPhaseError)
      }
    }

  freshChannel.listeners = Set(makeChanListener)
  CommsTower.listenNative(Set(makeChanListener), info)
}
