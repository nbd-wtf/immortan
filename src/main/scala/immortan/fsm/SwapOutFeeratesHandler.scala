package immortan.fsm

import immortan.crypto.Tools._
import scala.concurrent.duration._
import immortan.crypto.StateMachine
import immortan.fsm.SwapOutFeeratesHandler._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import fr.acinq.eclair.wire.{Init, SwapOut, SwapOutFeerates, SwapOutRequest}
import immortan.{ChanAndCommits, CommsTower, ConnectionListener, RemoteNodeInfo}
import java.util.concurrent.Executors
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.Features
import immortan.utils.Rx


object SwapOutFeeratesHandler {
  val WAITING_FIRST_RESPONSE = "feerates-state-waiting-first-response"
  val WAITING_REST_OF_RESPONSES = "feerates-state-waiting-rest-of-responses"
  val FINALIZED = "feerates-state-finalized"
  val CMDCancel = "feerates-cmd-cancel"

  case class NoSwapOutSupport(worker: CommsTower.Worker)
  case class YesSwapOutSupport(worker: CommsTower.Worker, msg: SwapOut)
  case class SwapOutResponseExt(msg: SwapOutFeerates, remoteInfo: RemoteNodeInfo)

  type SwapOutResponseOpt = Option[SwapOutResponseExt]
  case class FeeratesData(results: Map[RemoteNodeInfo, SwapOutResponseOpt], cmdStart: CMDStart)
  case class CMDStart(capableCncs: Set[ChanAndCommits] = Set.empty)
  final val minChainFee = Satoshi(253)
}

abstract class SwapOutFeeratesHandler(ourInit: Init) extends StateMachine[FeeratesData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  lazy private val swapOutListener = new ConnectionListener {
    // Disconnect logic is already handled in ChannelMaster base listener
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      val swapSupported = Features.canUseFeature(ourInit.features, theirInit.features, Features.ChainSwap)
      if (swapSupported) worker.handler process SwapOutRequest else me process NoSwapOutSupport(worker)
    }

    override def onSwapOutMessage(worker: CommsTower.Worker, msg: SwapOut): Unit =
      me process YesSwapOutSupport(worker, msg)
  }

  def onFound(offers: List[SwapOutResponseExt] = Nil): Unit
  def onNoProviderSwapOutSupport: Unit
  def onTimeoutAndNoResponse: Unit

  def doProcess(change: Any): Unit = (change, state) match {
    case (NoSwapOutSupport(worker), WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES) =>
      become(data.copy(results = data.results - worker.info), state)
      doSearch(force = false)

    case (YesSwapOutSupport(worker, msg: SwapOutFeerates), WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES)
      // Provider has sent feerates which are too low, tx won't likely ever confirm
      if msg.feerates.feerates.forall(params => minChainFee > params.fee) =>
      become(data.copy(results = data.results - worker.info), state)
      doSearch(force = false)

    case (YesSwapOutSupport(worker, msg: SwapOutFeerates), WAITING_FIRST_RESPONSE) =>
      val results1 = data.results.updated(worker.info, SwapOutResponseExt(msg, worker.info).toSome)
      become(data.copy(results = results1), WAITING_REST_OF_RESPONSES) // Start waiting for the rest of responses
      Rx.ioQueue.delay(5.seconds).foreach(_ => me doSearch true) // Decrease timeout for the rest of responses
      doSearch(force = false)

    case (YesSwapOutSupport(worker, msg: SwapOutFeerates), WAITING_REST_OF_RESPONSES) =>
      val results1 = data.results.updated(worker.info, SwapOutResponseExt(msg, worker.info).toSome)
      become(data.copy(results = results1), state)
      doSearch(force = false)

    case (CMDCancel, WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES) =>
      // Do not disconnect from remote peer because we have a channel with them, but remove this exact SwapIn listener
      for (cnc <- data.cmdStart.capableCncs) CommsTower.listeners(cnc.commits.remoteInfo.nodeSpecificPair) -= swapOutListener
      become(data, FINALIZED)

    case (cmd: CMDStart, null) =>
      become(freshData = FeeratesData(results = cmd.capableCncs.map(_.commits.remoteInfo -> None).toMap, cmd), WAITING_FIRST_RESPONSE)
      for (cnc <- cmd.capableCncs) CommsTower.listen(Set(swapOutListener), cnc.commits.remoteInfo.nodeSpecificPair, cnc.commits.remoteInfo, ourInit)
      Rx.ioQueue.delay(30.seconds).foreach(_ => me doSearch true)

    case _ =>
  }

  private def doSearch(force: Boolean): Unit = {
    // Remove yet unknown responses, unsupporting peers have been removed earlier
    val responses: List[SwapOutResponseExt] = data.results.values.flatten.toList

    if (responses.size == data.results.size) {
      // All responses have been collected
      onFound(offers = responses)
      me process CMDCancel
    } else if (data.results.isEmpty) {
      // No provider supports this right now
      onNoProviderSwapOutSupport
      me process CMDCancel
    } else if (responses.nonEmpty && force) {
      // We have partial responses, but timed out
      onFound(offers = responses)
      me process CMDCancel
    } else if (force) {
      // Timed out with no responses
      onTimeoutAndNoResponse
      me process CMDCancel
    }
  }
}