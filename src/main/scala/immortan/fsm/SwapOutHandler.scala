package immortan.fsm

import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import scala.concurrent.duration._
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import immortan.{ChanAndCommits, CommsTower, ConnectionListener}
import rx.lang.scala.Subscription
import immortan.utils.Rx


abstract class SwapOutHandler(cnc: ChanAndCommits, amount: Satoshi, btcAddress: String, blockTarget: Int, feerateKey: ByteVector32) { me =>
  def finish: Unit = runAnd(shutdownTimer.unsubscribe)(CommsTower.listeners(cnc.commits.remoteInfo.nodeSpecificPair) -= swapOutListener)
  CommsTower.listen(listeners1 = Set(swapOutListener), cnc.commits.remoteInfo.nodeSpecificPair, cnc.commits.remoteInfo)
  val shutdownTimer: Subscription = Rx.ioQueue.delay(30.seconds).doOnCompleted(finish).subscribe(_ => onTimeout)

  lazy private val swapOutListener = new ConnectionListener {
    // Disconnect logic is already handled in ChannelMaster base listener
    // We don't check if SwapOut is supported here, it has already been done
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      val swapOutRequest = SwapOutTransactionRequest(amount, btcAddress, blockTarget, feerateKey)
      worker.handler process swapOutRequest
    }

    override def onSwapOutMessage(worker: CommsTower.Worker, msg: SwapOut): Unit = msg match {
      case SwapOutTransactionDenied(btcAddr, SwapOutTransactionDenied.UNKNOWN_CHAIN_FEERATES) if btcAddr == btcAddress => runAnd(finish)(onPeerCanNotHandle)
      case SwapOutTransactionDenied(btcAddr, SwapOutTransactionDenied.CAN_NOT_HANDLE_AMOUNT) if btcAddr == btcAddress => runAnd(finish)(onPeerCanNotHandle)
      case message: SwapOutTransactionResponse if message.btcAddress == btcAddress => runAnd(finish)(me onResponse message)
      case message: SwapOutTransactionDenied if message.btcAddress == btcAddress => runAnd(finish)(onInvalidRequest)
      case _ => // Do nothing, it's unrelated
    }
  }

  def onResponse(message: SwapOutTransactionResponse): Unit
  def onPeerCanNotHandle: Unit
  def onInvalidRequest: Unit
  def onTimeout: Unit
}
