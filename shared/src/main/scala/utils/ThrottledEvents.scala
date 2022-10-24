package immortan.utils

import scala.concurrent.duration._

class ThrottledEventStream(interval: FiniteDuration) {
  val timer = new java.util.Timer()
  val subscribers = scala.collection.mutable.Set.empty[Function0[Unit]]

  var isRunning = false
  var task = new java.util.TimerTask { def run(): Unit = { isRunning = false } }

  def fire(): Unit = {
    if (!isRunning) {
      isRunning = true
      subscribers.foreach(_())
      timer.schedule(task, interval.toMillis)
    }
  }

  def subscribe(cb: () => Unit): Function0[Unit] = {
    subscribers += cb
    () => subscribers.remove(cb)
  }
}
