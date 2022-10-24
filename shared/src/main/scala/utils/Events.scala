package immortan.utils

import scala.concurrent.duration._

class EventStream[T]() {
  val subscribers = scala.collection.mutable.Set.empty[Function[T, Unit]]

  def fire(value: T): Unit = {
    subscribers.foreach(_(value))
  }

  def subscribe(cb: T => Unit): Function0[Unit] = {
    subscribers += cb
    () => subscribers.remove(cb)
  }
}
