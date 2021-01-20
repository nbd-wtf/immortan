package immortan.utils

import scala.concurrent.duration._
import rx.lang.scala.schedulers.IOScheduler
import rx.lang.scala.Observable


object Rx {
  def callNextOnFail[T, V](run: T => Observable[V], onRunOut: Observable[V], cs: Seq[T] = Nil): Observable[V] = {
    def proceedWithNext(failure: Throwable): Observable[V] = callNextOnFail(run, onRunOut, cs.tail)
    if (cs.isEmpty) onRunOut else run(cs.head) onErrorResumeNext proceedWithNext
  }

  def callAllAtOnce[T, V](run: T => Observable[V], onFailure: Throwable => V, cs: Seq[T] = Nil): Observable[V] =
    Observable.from(cs).flatMap(item => run(item) onErrorReturn onFailure)

  def initDelay[T](next: Observable[T], startMillis: Long, timeoutMillis: Long): Observable[T] = {
    val adjustedTimeout = startMillis + timeoutMillis - System.currentTimeMillis
    val delayLeft = if (adjustedTimeout < 5L) 5L else adjustedTimeout
    Observable.just(null).delay(delayLeft.millis).flatMap(_ => next)
  }

  def retry[T](obs: Observable[T], pick: (Throwable, Int) => Duration, times: Range): Observable[T] =
    obs.retryWhen(_.zipWith(Observable from times)(pick) flatMap Observable.timer)

  def repeat[T](obs: Observable[T], pick: (Unit, Int) => Duration, times: Range): Observable[T] =
    obs.repeatWhen(_.zipWith(Observable from times)(pick) flatMap Observable.timer)

  def ioQueue: Observable[Null] = Observable.just(null).subscribeOn(IOScheduler.apply)

  def pickInc(errorOrUnit: Any, next: Int): Duration = next.seconds
}
