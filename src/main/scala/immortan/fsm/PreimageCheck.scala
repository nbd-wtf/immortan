package immortan.fsm

import java.util.concurrent.Executors

import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.Helpers.HashToPreimage
import fr.acinq.eclair.wire.{
  HostedChannelMessage,
  Init,
  QueryPreimages,
  ReplyPreimages
}
import immortan.crypto.StateMachine
import immortan.crypto.Tools.randomKeyPair
import immortan.utils.Rx
import immortan.{
  CommsTower,
  ConnectionListener,
  KeyPairAndPubKey,
  RemoteNodeInfo
}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object PreimageCheck {
  sealed trait State
  case class Initial() extends State()
  case class Operational() extends State()
  case class Finalized() extends State()

  final val CMDCancel = "preimage-check-cmd-cancel"
  case class PeerDisconnected(worker: CommsTower.Worker)
  case class PeerResponse(msg: HostedChannelMessage, worker: CommsTower.Worker)
  case class CMDStart(
      hashes: Set[ByteVector32],
      hosts: Set[RemoteNodeInfo] = Set.empty
  )

  case class CheckData(
      pairs: Map[RemoteNodeInfo, KeyPairAndPubKey],
      pending: Set[RemoteNodeInfo],
      hashes: Set[ByteVector32],
      hashToPreimage: HashToPreimage = Map.empty
  )
}

abstract class PreimageCheck
    extends StateMachine[PreimageCheck.CheckData, PreimageCheck.State] {
  me =>
  implicit val context: ExecutionContextExecutor =
    ExecutionContext fromExecutor Executors.newSingleThreadExecutor

  def initialState = PreimageCheck.Initial()

  def randomPair(info: RemoteNodeInfo): (RemoteNodeInfo, KeyPairAndPubKey) =
    info -> KeyPairAndPubKey(randomKeyPair, info.nodeId)

  def process(changeMessage: Any): Unit =
    scala.concurrent.Future(me doProcess changeMessage)

  def onComplete(preimages: HashToPreimage): Unit

  private lazy val listener = new ConnectionListener {
    override def onDisconnect(worker: CommsTower.Worker): Unit =
      me process PreimageCheck.PeerDisconnected(worker)
    override def onOperational(
        worker: CommsTower.Worker,
        theirInit: Init
    ): Unit = worker.handler process QueryPreimages(data.hashes.toList)
    override def onHostedMessage(
        worker: CommsTower.Worker,
        msg: HostedChannelMessage
    ): Unit = me process PreimageCheck.PeerResponse(msg, worker)
  }

  def doProcess(change: Any): Unit = (change, state) match {
    case (msg: PreimageCheck.PeerDisconnected, _: PreimageCheck.Operational) =>
      // Keep trying to reconnect with delays until final timeout
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process msg.worker)
      CommsTower forget msg.worker.pair

    case (worker: CommsTower.Worker, _: PreimageCheck.Operational) =>
      val newPair @ (info, pair) = randomPair(worker.info)
      CommsTower.listen(listeners1 = Set(listener), pair, info)
      become(
        data.copy(pairs = data.pairs + newPair),
        PreimageCheck.Operational()
      )

    case (
          PreimageCheck.PeerResponse(msg: ReplyPreimages, worker),
          _: PreimageCheck.Operational
        ) =>
      // One of remote nodes replies, check if we have all preimages of interest collected
      become(
        merge(data, msg).copy(pending = data.pending - worker.info),
        PreimageCheck.Operational()
      )
      doCheck(force = false)

    case (PreimageCheck.CMDCancel, _: PreimageCheck.Operational) =>
      // User has manually cancelled a check, disconnect all peers
      for (pair <- data.pairs.values) CommsTower forget pair
      become(data, PreimageCheck.Finalized())

    case (PreimageCheck.CMDStart(hashes, hosts), _: PreimageCheck.Initial) =>
      become(
        PreimageCheck.CheckData(hosts.map(randomPair).toMap, hosts, hashes),
        PreimageCheck.Operational()
      )
      for (Tuple2(info, pair) <- data.pairs)
        CommsTower.listen(Set(listener), pair, info)
      Rx.ioQueue.delay(30.seconds).foreach(_ => me doCheck true)

    case _ =>
  }

  def doCheck(force: Boolean): Unit = {
    // IMPORTANT: of all peer replies filter our preimages of interest
    val collected =
      data.hashToPreimage.view
        .filterKeys(data.hashes.contains)
        .toMap

    if (collected.size == data.hashes.size) {
      // We have collected all of our preimages
      me doProcess PreimageCheck.CMDCancel
      onComplete(collected)
    } else if (data.pending.isEmpty) {
      // Finish with whatever we have collected
      me doProcess PreimageCheck.CMDCancel
      onComplete(collected)
    } else if (force) {
      // Finish with whatever we have collected
      me doProcess PreimageCheck.CMDCancel
      onComplete(collected)
    }
  }

  def merge(
      data1: PreimageCheck.CheckData,
      msg: ReplyPreimages
  ): PreimageCheck.CheckData = {
    val hashToPreimage1 = data1.hashToPreimage ++ msg.preimages
      .map(Crypto sha256 _)
      .zip(msg.preimages)
    data1.copy(hashToPreimage = hashToPreimage1)
  }
}
