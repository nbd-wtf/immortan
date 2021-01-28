package immortan

import fr.acinq.eclair.channel.{HasNormalCommitments, PersistentChannelData}
import fr.acinq.eclair.wire.LightningMessage


object NormalChannel {
  def make(initListeners: Set[ChannelListener], normalData: HasNormalCommitments, bag: ChannelBag): NormalChannel = new NormalChannel {
    def SEND(messages: LightningMessage *): Unit = CommsTower.sendMany(messages, normalData.commitments.announce.nodeSpecificPair)
    def STORE(normalData1: PersistentChannelData): PersistentChannelData = bag.put(normalData1)
    listeners = initListeners
    doProcess(normalData)
  }
}

abstract class NormalChannel extends Channel { me =>
  def doProcess(change: Any): Unit = ???
}
