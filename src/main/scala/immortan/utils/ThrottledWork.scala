package immortan.utils


abstract class ThrottledWork[T, V] {
  private var lastWork: Option[T] = None
  private var subscription: Option[rx.lang.scala.Subscription] = None
  def finishedOrNeverStarted: Boolean = subscription.isEmpty
  def work(input: T): rx.lang.scala.Observable[V]
  def process(data: T, res: V): Unit
  def error(error: Throwable): Unit

  private def doProcess(data: T, res: V): Unit = {
    // First nullify sunscription, the process callback
    subscription = None
    process(data, res)
  }

  def addWork(data: T): Unit = if (subscription.isEmpty) {
    // Previous work has already finished or has never started, schedule a new one and then look if more work is added once this one is done
    val newSubscription = work(data).doOnSubscribe { lastWork = None }.doAfterTerminate { lastWork foreach addWork }.subscribe(res => doProcess(data, res), error)
    subscription = Some(newSubscription)
  } else {
    // Current work has not finished yet
    // schedule new work once this is done
    lastWork = Some(data)
  }

  def replaceWork(data: T): Unit = if (subscription.isEmpty) {
    // Previous work has already finished or was interrupted or has never benn started
    val newSubscription = work(data).subscribe(res => doProcess(data, res), error)
    subscription = Some(newSubscription)
  } else {
    // Current work has not finished yet
    // disconnect subscription and replace
    for (s <- subscription) s.unsubscribe
    subscription = None
    replaceWork(data)
  }
}