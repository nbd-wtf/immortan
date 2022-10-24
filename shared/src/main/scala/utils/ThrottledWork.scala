package immortan.utils

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Future
import scala.util.{Success, Failure}
import scala.collection.mutable.Queue

import immortan.none
import immortan.LNParams.ec

abstract class ThrottledWork[T, V] {
  private var workQueue: Queue[T] = Queue.empty
  private var currentWork: Option[Long] = None
  private var ignored = scala.collection.mutable.Set.empty[Long]
  private val nextIdx = new AtomicLong(0L)

  def error(error: Throwable): Unit = none
  def work(input: T): Future[V]
  def process(data: T, res: V): Unit

  private def runOnError(err: Throwable): Unit = {
    // First nullify sunscription, then process error
    currentWork = None
    error(err)
  }

  private def runOnNext(data: T, res: V): Unit = {
    // First nullify sunscription, then process callback
    currentWork = None
    process(data, res)
  }

  def addWork(data: T): Unit =
    if (currentWork.isEmpty) {
      // Previous work has already been finished by now or has never started at all
      // schedule a new one and then look if more work is added once this one is done

      // keep track of the work being done here
      val n = nextIdx.getAndIncrement()
      currentWork = Some(n)

      // actually start the work here
      work(data).onComplete { result =>
        if (ignored.contains(n)) {
          // if this work was canceled at some point in the future we ignore the result completely
          ignored.remove(n) // cleanup this
        } else {
          // otherwise we proceed
          result match {
            case Success(res) =>
              if (!workQueue.isEmpty)
                addWork(workQueue.dequeue())
              runOnNext(data, res)
            case Failure(err) => runOnError(err)
          }
        }
      }
    } else {
      // Current work has not finished yet
      // schedule new work once this is done
      workQueue.enqueue(data)
    }

  def replaceWork(data: T): Unit = currentWork match {
    case None =>
      // Previous work has already finished or was interrupted or has never been started
      addWork(data)
    case Some(_) =>
      // Current work has not finished yet
      // disconnect subscription and replace
      ignoreCurrentWork()
      replaceWork(data)
  }

  def ignoreCurrentWork(): Unit = {
    currentWork.foreach { n => ignored.add(n) }
  }
}
