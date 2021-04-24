package immortan.fsm

import scala.concurrent.duration._
import fr.acinq.eclair.wire.{HostedChannelMessage, Init, QueryPreimages, ReplyPreimages}
import immortan.{CommsTower, ConnectionListener, KeyPairAndPubKey, RemoteNodeInfo}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import immortan.fsm.PreimageCheck.{FINALIZED, OPERATIONAL}
import immortan.crypto.Tools.randomKeyPair
import java.util.concurrent.Executors
import fr.acinq.bitcoin.ByteVector32
import immortan.crypto.StateMachine
import immortan.utils.Rx


object PreimageCheck {
  final val CMDCancel = "preimage-check-cmd-cancel"
  final val OPERATIONAL = "preimage-check-operational"
  final val FINALIZED = "preimage-check-finalized"

  case class PeerResponse(msg: HostedChannelMessage)
  case class PeerDisconnected(worker: CommsTower.Worker)
  case class CheckData(pairs: Map[RemoteNodeInfo, KeyPairAndPubKey], hashes: Set[ByteVector32], preimages: Map[ByteVector32, ByteVector32] = Map.empty)
  case class CMDStart(hosts: Set[RemoteNodeInfo], hashes: Set[ByteVector32] = Set.empty)
}

abstract class PreimageCheck extends StateMachine[PreimageCheck.CheckData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def randomPair(info: RemoteNodeInfo): (RemoteNodeInfo, KeyPairAndPubKey) = info -> KeyPairAndPubKey(randomKeyPair, info.nodeId)
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  def onTimeout(preimages: Map[ByteVector32, ByteVector32] = Map.empty): Unit
  def onComplete(preimages: Map[ByteVector32, ByteVector32] = Map.empty): Unit

  private lazy val listener = new ConnectionListener {
    override def onDisconnect(worker: CommsTower.Worker): Unit = me process PreimageCheck.PeerDisconnected(worker)
    override def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = me process PreimageCheck.PeerResponse(msg)
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = worker.handler process QueryPreimages(data.hashes.toList)
  }

  def doProcess(change: Any): Unit = (change, state) match {
    case (msg: PreimageCheck.PeerDisconnected, OPERATIONAL) =>
      // Keep trying to reconnect with delays until final timeout
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process msg.worker)
      become(data.copy(pairs = data.pairs - msg.worker.info), OPERATIONAL)

    case (worker: CommsTower.Worker, OPERATIONAL) =>
      val newPair @ (info, pair) = randomPair(worker.info)
      CommsTower.listen(listeners1 = Set(listener), pair, info)
      become(data.copy(pairs = data.pairs + newPair), OPERATIONAL)

    case (PreimageCheck.PeerResponse(msg: ReplyPreimages), OPERATIONAL) =>
      // Remote node replies, check if we have all preimages of interest
      val preimages1 = data.preimages ++ msg.hashes.zip(msg.preimages)
      become(data.copy(preimages = preimages1), OPERATIONAL)
      doCheck(force = false)

    case (PreimageCheck.CMDCancel, OPERATIONAL) =>
      // User has manually cancelled a check, disconnect all peers
      for (pair <- data.pairs.values) CommsTower forget pair
      become(data.copy(pairs = Map.empty), FINALIZED)

    case (PreimageCheck.CMDStart(hosts, hashes), null) =>
      become(PreimageCheck.CheckData(hosts.map(randomPair).toMap, hashes, preimages = Map.empty), OPERATIONAL)
      for (Tuple2(info, pair) <- data.pairs) CommsTower.listen(listeners1 = Set(listener), pair, info)
      Rx.ioQueue.delay(30.seconds).foreach(_ => me doCheck true)

    case _ =>
  }

  def doCheck(force: Boolean): Unit = {
    // Of all peer replies filter our preimages of interest
    val collected = data.preimages.filterKeys(data.hashes.contains)

    if (collected.size == data.hashes.size) {
      // We have collected all our preimages
      me doProcess PreimageCheck.CMDCancel
      onComplete(collected)
    } else if (force) {
      // Finish with whatever results we have
      me doProcess PreimageCheck.CMDCancel
      onComplete(collected)
    }
  }
}
