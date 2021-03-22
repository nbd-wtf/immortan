package immortan.fsm

import fr.acinq.eclair.wire._
import scala.concurrent.duration._
import immortan.{ChanAndCommits, CommsTower, ConnectionListener}
import fr.acinq.eclair.payment.PaymentRequest
import immortan.crypto.Tools.runAnd
import rx.lang.scala.Subscription
import immortan.utils.Rx


abstract class SwapInHandler(cnc: ChanAndCommits, paymentRequest: PaymentRequest, id: Long) { me =>
  def finish: Unit = runAnd(shutdownTimer.unsubscribe)(CommsTower.listeners(cnc.commits.remoteInfo.nodeSpecificPair) -= swapInListener)
  CommsTower.listen(listeners1 = Set(swapInListener), cnc.commits.remoteInfo.nodeSpecificPair, cnc.commits.remoteInfo)
  val shutdownTimer: Subscription = Rx.ioQueue.delay(30.seconds).doOnCompleted(finish).subscribe(_ => onTimeout)

  lazy private val swapInListener = new ConnectionListener {
    // Disconnect logic is already handled in ChannelMaster base listener
    // We don't check if SwapOut is supported here, it has already been done
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      val swapInRequest = SwapInPaymentRequest(PaymentRequest.write(paymentRequest), id)
      worker.handler process swapInRequest
    }

    override def onSwapInMessage(worker: CommsTower.Worker, msg: SwapIn): Unit = msg match {
      case message: SwapInState if message.processing.exists(_.id == id) => runAnd(finish)(onProcessing)
      case message: SwapInPaymentDenied if message.id == id => runAnd(finish)(me onDenied message)
      case _ => // Do nothing, it's unrelated
    }
  }

  def onDenied(msg: SwapInPaymentDenied): Unit
  def onProcessing: Unit
  def onTimeout: Unit
}
