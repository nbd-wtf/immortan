package immortan.fsm

import immortan.crypto.Tools._
import scala.concurrent.duration._
import immortan.fsm.AccountExistenceCheck._
import fr.acinq.eclair.wire.{HostedChannelMessage, Init, InitHostedChannel, InvokeHostedChannel, LastCrossSignedState, NodeAnnouncement}
import immortan.{CommsTower, ConnectionListener, NodeAnnouncementExt, StorageFormat}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import java.util.concurrent.Executors
import fr.acinq.bitcoin.ByteVector32
import immortan.crypto.StateMachine
import immortan.utils.Rx


object AccountExistenceCheck {
  val OPERATIONAL = "existance-state-operational"
  val FINALIZED = "existance-state-finalized"
  val CMDCancel = "existance-cmd-cancel"

  case class CMDStart(exts: Set[NodeAnnouncementExt] = Set.empty)
  case class PeerResponse(msg: HostedChannelMessage, worker: CommsTower.Worker)
  case class PeerDisconnected(worker: CommsTower.Worker)

  case class CheckData(hosts: Map[NodeAnnouncement, NodeAnnouncementExt], // Need this for node specific keys
                       results: Map[NodeAnnouncement, Boolean], // False is unknown, True is channel exists
                       reconnectsLeft: Int)
}

abstract class AccountExistenceCheck(format: StorageFormat, chainHash: ByteVector32, init: Init) extends StateMachine[CheckData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  lazy private val accountCheckListener = new ConnectionListener {
    override def onDisconnect(worker: CommsTower.Worker): Unit = me process PeerDisconnected(worker)
    override def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = me process PeerResponse(msg, worker)

    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      val peerSpecificSecret = format.keys.refundPubKey(theirNodeId = worker.ann.nodeId)
      val peerSpecificRefundPubKey = format.attachedChannelSecret(theirNodeId = worker.ann.nodeId)
      worker.handler process InvokeHostedChannel(chainHash, peerSpecificSecret, peerSpecificRefundPubKey)
    }
  }

  def onTimeout: Unit
  def onNoAccountFound: Unit
  def onPresentAccount: Unit

  def doProcess(change: Any): Unit = (change, state) match {
    case (PeerDisconnected(worker), OPERATIONAL) if data.reconnectsLeft > 0 =>
      become(data.copy(reconnectsLeft = data.reconnectsLeft - 1), OPERATIONAL)
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process worker)

    case (_: PeerDisconnected, OPERATIONAL) =>
      // We've run out of reconnect attempts
      doSearch(force = true)

    case (worker: CommsTower.Worker, OPERATIONAL) =>
      // We get previously scheduled worker and use its peer data to reconnect again
      CommsTower.listen(Set(accountCheckListener), worker.pkap, worker.ann, init)

    case (PeerResponse(_: InitHostedChannel, worker), OPERATIONAL) =>
      // Remote node offers to create a new channel, no "account" there
      become(data.copy(results = data.results - worker.ann), OPERATIONAL)
      doSearch(force = false)

    case (PeerResponse(remoteLCSS: LastCrossSignedState, worker), OPERATIONAL) =>
      // Remote node replies with a state, check our signature to make sure it's valid
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(data.hosts(worker.ann).nodeSpecificPubKey)
      val results1 = if (isLocalSigOk) data.results.updated(worker.ann, true) else data.results - worker.ann
      become(data.copy(results = results1), OPERATIONAL)
      doSearch(force = false)

    case (CMDCancel, OPERATIONAL) =>
      // User has manually cancelled a check, disconnect all peers
      data.hosts.values.foreach(CommsTower forget _.nodeSpecificPkap)
      become(data, FINALIZED)

    case (CMDStart(outstandingProviderExts), null) =>
      val remainingHosts = toMapBy[NodeAnnouncement, NodeAnnouncementExt](outstandingProviderExts, _.na)
      become(CheckData(remainingHosts, remainingHosts.mapValues(_ => false), remainingHosts.size * 4), OPERATIONAL)
      for (ext <- outstandingProviderExts) CommsTower.listen(Set(accountCheckListener), ext.nodeSpecificPkap, ext.na, init)
      Rx.ioQueue.delay(30.seconds).foreach(_ => me doSearch true)
  }

  private def doSearch(force: Boolean): Unit =
    if (data.results.values.toSet contains true) {
      // At least one remote peer has confirmed a channel
      // we do not wait for the rest and proceed right away
      me doProcess CMDCancel
      onPresentAccount
    } else if (data.results.isEmpty) {
      // All peers replied, none has a channel
      me doProcess CMDCancel
      onNoAccountFound
    } else if (force) {
      me doProcess CMDCancel
      onTimeout
    }
}