package immortan

import fr.acinq.eclair.channel.{ChannelData, HasNormalCommitments}
import fr.acinq.eclair.wire.LightningMessage

object NormalChannel {
  def make(initListeners: Set[ChannelListener], d: HasNormalCommitments, bag: ChannelBag): NormalChannel = new NormalChannel {
    def SEND(msg: LightningMessage *): Unit = for (work <- CommsTower.workers get d.commitments.announce.nodeSpecificPkap) msg foreach work.handler.process
    def STORE(d1: ChannelData): ChannelData = bag.put(d.commitments.channelId, d1)
    listeners = initListeners
    doProcess(d)
  }
}

abstract class NormalChannel extends Channel { me =>
  def doProcess(change: Any): Unit = ???
}
