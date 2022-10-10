package immortan.electrum

import scala.collection.mutable.HashSet

object EventStream {
  var listeners = HashSet.empty[PartialFunction[Any, Unit]]
  def subscribe(fn: PartialFunction[Any, Unit]) = {
    listeners.add(fn)
  }
  def publish(msg: Any): Unit = {
    scala.concurrent.ExecutionContext.global.execute(() => {
      listeners.foreach { l =>
        if (l.isDefinedAt(msg))
          l(msg)
      }
    })
  }
}
