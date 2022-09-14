package fr.acinq.eclair.blockchain.electrum

import scala.collection.mutable.HashSet
import fr.acinq.eclair.blockchain.electrum.ElectrumEvent

object EventStream {
  var listeners = HashSet.empty[PartialFunction[ElectrumEvent, Unit]]
  def subscribe(fn: PartialFunction[ElectrumEvent, Unit]) = {
    listeners.add(fn)
  }
  def publish(msg: ElectrumEvent): Unit =
    scala.concurrent.ExecutionContext.global.execute(() => {
      listeners.foreach { l =>
        if (l.isDefinedAt(msg))
          l(msg)
      }
    })
}
