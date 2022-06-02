package immortan.fsm

import java.util.concurrent.Executors

import fr.acinq.eclair.Features
import fr.acinq.eclair.wire.{Init, SwapIn, SwapInRequest, SwapInResponse}
import immortan.crypto.StateMachine
import immortan.crypto.Tools._
import immortan.fsm.SwapInAddressHandler._
import immortan.utils.Rx
import immortan._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object SwapInAddressHandler {
  final val CMDCancel = "address-cmd-cancel"
  case class NoSwapInSupport(worker: CommsTower.Worker)
  case class YesSwapInSupport(worker: CommsTower.Worker, msg: SwapIn)
  case class SwapInResponseExt(msg: SwapInResponse, remoteInfo: RemoteNodeInfo)

  sealed trait State
  case object Initial extends State
  case object WaitingFirstResponse extends State
  case object WaitingRestOfResponses extends State
  case object Finalized extends State

  type SwapInResponseOpt = Option[SwapInResponseExt]
  case class AddressData(
      results: Map[RemoteNodeInfo, SwapInResponseOpt],
      cmdStart: CMDStart
  )
  case class CMDStart(capableCncs: Set[ChanAndCommits] = Set.empty)
}

abstract class SwapInAddressHandler
    extends StateMachine[AddressData, SwapInAddressHandler.State] {
  me =>
  implicit val context: ExecutionContextExecutor =
    ExecutionContext fromExecutor Executors.newSingleThreadExecutor

  def initialState = SwapInAddressHandler.Initial

  def process(changeMessage: Any): Unit =
    scala.concurrent.Future(me doProcess changeMessage)

  // It is assumed that this FSM is established with a peer which has an HC with us
  // This FSM has a hardcoded timeout which will eventually remove its connection listeners
  // OTOH this FSM should survive reconnects so there is no local disconnect logic here

  lazy private val swapInListener = new ConnectionListener {
    override def onOperational(
        worker: CommsTower.Worker,
        theirInit: Init
    ): Unit = {
      val isOk = Features.canUseFeature(
        LNParams.ourInit.features,
        theirInit.features,
        Features.ChainSwap
      )
      if (isOk) worker.handler process SwapInRequest
      else me process NoSwapInSupport(worker)
    }

    override def onSwapInMessage(worker: CommsTower.Worker, msg: SwapIn): Unit =
      me process YesSwapInSupport(worker, msg)
  }

  def onFound(offers: List[SwapInResponseExt] = Nil): Unit
  def onNoProviderSwapInSupport(): Unit
  def onTimeoutAndNoResponse(): Unit

  def doProcess(change: Any): Unit = (change, state) match {
    case (
          NoSwapInSupport(worker),
          SwapInAddressHandler.WaitingFirstResponse |
          SwapInAddressHandler.WaitingRestOfResponses
        ) =>
      become(data.copy(results = data.results - worker.info), state)
      doSearch(force = false)

    case (
          YesSwapInSupport(worker, msg: SwapInResponse),
          SwapInAddressHandler.WaitingFirstResponse
        ) =>
      val results1 = data.results.updated(
        worker.info,
        SwapInResponseExt(msg, worker.info).asSome
      )
      become(
        data.copy(results = results1),
        SwapInAddressHandler.WaitingRestOfResponses
      ) // Start waiting for the rest of responses
      Rx.ioQueue
        .delay(5.seconds)
        .foreach(_ =>
          me doSearch true
        ) // Decrease timeout for the rest of responses
      doSearch(force = false)

    case (
          YesSwapInSupport(worker, msg: SwapInResponse),
          SwapInAddressHandler.WaitingRestOfResponses
        ) =>
      val results1 = data.results.updated(
        worker.info,
        SwapInResponseExt(msg, worker.info).asSome
      )
      become(data.copy(results = results1), state)
      doSearch(force = false)

    case (
          CMDCancel,
          SwapInAddressHandler.WaitingFirstResponse |
          SwapInAddressHandler.WaitingRestOfResponses
        ) =>
      // Do not disconnect from remote peer because we have a channel with them, but remove this exact SwapIn listener
      for (cnc <- data.cmdStart.capableCncs)
        CommsTower.rmListenerNative(cnc.commits.remoteInfo, swapInListener)
      become(data, SwapInAddressHandler.Finalized)

    case (cmd: CMDStart, SwapInAddressHandler.Initial) =>
      become(
        AddressData(
          results = cmd.capableCncs.map(_.commits.remoteInfo -> None).toMap,
          cmd
        ),
        SwapInAddressHandler.WaitingFirstResponse
      )
      for (cnc <- cmd.capableCncs)
        CommsTower.listenNative(Set(swapInListener), cnc.commits.remoteInfo)
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
      onNoProviderSwapInSupport()
      me process CMDCancel
    } else if (responses.nonEmpty && force) {
      // We have partial responses, but timed out
      onFound(offers = responses)
      me process CMDCancel
    } else if (force) {
      // Timed out with no responses
      onTimeoutAndNoResponse()
      me process CMDCancel
    }
  }
}
