package immortan

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._

package object electrum {
  class DebouncedFunctionCanceled
      extends Exception("debounced function canceled")

  def debounce[A, B](
      fn: Function[A, B],
      duration: FiniteDuration
  ): Function[A, Future[B]] = {
    var timer = new java.util.Timer()
    var task = new java.util.TimerTask { def run(): Unit = {} }
    var promise = Promise[B]()

    def debounced(arg: A): Future[B] = {
      // every time the debounced function is called

      // clear the timeout that might have existed from before
      task.cancel()

      // fail the promise that might be pending from before
      //   a failed promise just means this call was canceled and replaced
      //   by a more a recent one
      if (!promise.isCompleted) promise.failure(new DebouncedFunctionCanceled)

      // create a new promise and a new timer
      promise = Promise[B]()
      task = new java.util.TimerTask {
        // actually run the function when the timer ends
        def run(): Unit = {
          val res = fn(arg)
          if (!promise.isCompleted) promise.success(res)
        }
      }
      timer.schedule(task, duration.toMillis)

      // if this was the last time this function was called in rapid succession
      //   the last promise will be fulfilled
      promise.future
    }

    debounced
  }
}
