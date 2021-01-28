package immortan.fsm

import immortan.crypto.Tools._
import scala.concurrent.duration._
import immortan.fsm.SwapInAddressHandler._
import immortan.{ChanAndCommits, CommsTower, ConnectionListener}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import fr.acinq.eclair.wire.{Init, NodeAnnouncement, SwapIn, SwapInRequest, SwapInResponse}
import java.util.concurrent.Executors
import immortan.crypto.StateMachine
import fr.acinq.eclair.Features
import immortan.utils.Rx


object SwapInAddressHandler {
  val WAITING_FIRST_RESPONSE = "address-state-waiting-first-response"
  val WAITING_REST_OF_RESPONSES = "address-state-waiting-rest-of-responses"
  val FINALIZED = "address-state-finalized"
  val CMDCancel = "address-cmd-cancel"

  case class NoSwapInSupport(worker: CommsTower.Worker)
  case class YesSwapInSupport(worker: CommsTower.Worker, msg: SwapIn)
  case class SwapInResponseExt(msg: SwapInResponse, ann: NodeAnnouncement)

  type SwapInResponseOpt = Option[SwapInResponseExt]
  case class AddressData(results: Map[NodeAnnouncement, SwapInResponseOpt], cmdStart: CMDStart)
  case class CMDStart(capableCncs: Set[ChanAndCommits] = Set.empty)
}

abstract class SwapInAddressHandler(ourInit: Init) extends StateMachine[AddressData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  lazy private val swapInListener = new ConnectionListener {
    // Disconnect logic is already handled in ChannelMaster base listener
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      val swapSupported = Features.canUseFeature(ourInit.features, theirInit.features, Features.ChainSwap)
      if (swapSupported) worker.handler process SwapInRequest else me process NoSwapInSupport(worker)
    }

    override def onSwapInMessage(worker: CommsTower.Worker, msg: SwapIn): Unit =
      me process YesSwapInSupport(worker, msg)
  }

  def onFound(offers: List[SwapInResponseExt] = Nil): Unit
  def onNoProviderSwapInSupport: Unit
  def onTimeoutAndNoResponse: Unit

  def doProcess(change: Any): Unit = (change, state) match {
    case (NoSwapInSupport(worker), WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES) =>
      become(data.copy(results = data.results - worker.ann), state)
      doSearch(force = false)

    case (YesSwapInSupport(worker, msg: SwapInResponse), WAITING_FIRST_RESPONSE) =>
      val results1 = data.results.updated(worker.ann, SwapInResponseExt(msg, worker.ann).toSome)
      become(data.copy(results = results1), WAITING_REST_OF_RESPONSES) // Start waiting for the rest of responses
      Rx.ioQueue.delay(5.seconds).foreach(_ => me doSearch true) // Decrease timeout for the rest of responses
      doSearch(force = false)

    case (YesSwapInSupport(worker, msg: SwapInResponse), WAITING_REST_OF_RESPONSES) =>
      val results1 = data.results.updated(worker.ann, SwapInResponseExt(msg, worker.ann).toSome)
      become(data.copy(results = results1), state)
      doSearch(force = false)

    case (CMDCancel, WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES) =>
      // Do not disconnect from remote peer because we have a channel with them, but remove this exact SwapIn listener
      for (cnc <- data.cmdStart.capableCncs) CommsTower.listeners(cnc.commits.announce.nodeSpecificPkap) -= swapInListener
      become(data, FINALIZED)

    case (cmd: CMDStart, null) =>
      become(freshData = AddressData(results = cmd.capableCncs.map(_.commits.announce.na -> None).toMap, cmd), WAITING_FIRST_RESPONSE)
      for (cnc <- cmd.capableCncs) CommsTower.listen(Set(swapInListener), cnc.commits.announce.nodeSpecificPkap, cnc.commits.announce.na, ourInit)
      Rx.ioQueue.delay(30.seconds).foreach(_ => me doSearch true)

    case _ =>
  }

  private def doSearch(force: Boolean): Unit = {
    // Remove yet unknown responses, unsupporting peers have been removed earlier
    val responses: List[SwapInResponseExt] = data.results.values.flatten.toList

    if (responses.size == data.results.size) {
      // All responses have been collected
      onFound(offers = responses)
      me process CMDCancel
    } else if (data.results.isEmpty) {
      // No provider supports this right now
      onNoProviderSwapInSupport
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