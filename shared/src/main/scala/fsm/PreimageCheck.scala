package immortan.fsm

import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scoin.{ByteVector32, Crypto}
import scoin.ln.Init
import scoin.hc.{HostedChannelMessage, QueryPreimages, ReplyPreimages}

import immortan.{
  after,
  randomKeyPair,
  StateMachine,
  CommsTower,
  ConnectionListener,
  KeyPairAndPubKey,
  RemoteNodeInfo
}
import immortan.channel.Helpers.HashToPreimage

object PreimageCheck {
  sealed trait State
  case object Initial extends State
  case object Operational extends State
  case object Finalized extends State

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
  implicit val context: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor)

  def initialState = PreimageCheck.Initial

  def randomPair(info: RemoteNodeInfo): (RemoteNodeInfo, KeyPairAndPubKey) =
    info -> KeyPairAndPubKey(randomKeyPair, info.nodeId)

  def process(changeMessage: Any): Unit =
    scala.concurrent.Future(doProcess(changeMessage))

  def onComplete(preimages: HashToPreimage): Unit

  private lazy val listener = new ConnectionListener {
    override def onDisconnect(worker: CommsTower.Worker): Unit =
      process(PreimageCheck.PeerDisconnected(worker))
    override def onOperational(
        worker: CommsTower.Worker,
        theirInit: Init
    ): Unit = worker.handler process QueryPreimages(data.hashes.toList)
    override def onHostedMessage(
        worker: CommsTower.Worker,
        msg: HostedChannelMessage
    ): Unit = process(PreimageCheck.PeerResponse(msg, worker))
  }

  def doProcess(change: Any): Unit = (change, state) match {
    case (msg: PreimageCheck.PeerDisconnected, PreimageCheck.Operational) =>
      // Keep trying to reconnect with delays until final timeout
      after(3.seconds) {
        process(msg.worker)
      }
      CommsTower.forget(msg.worker.pair)

    case (worker: CommsTower.Worker, PreimageCheck.Operational) =>
      val newPair @ (info, pair) = randomPair(worker.info)
      CommsTower.listen(Set(listener), pair, info)
      become(
        data.copy(pairs = data.pairs + newPair),
        PreimageCheck.Operational
      )

    case (
          PreimageCheck.PeerResponse(msg: ReplyPreimages, worker),
          PreimageCheck.Operational
        ) =>
      // One of remote nodes replies, check if we have all preimages of interest collected
      become(
        merge(data, msg).copy(pending = data.pending - worker.info),
        PreimageCheck.Operational
      )
      doCheck(force = false)

    case (PreimageCheck.CMDCancel, PreimageCheck.Operational) =>
      // User has manually cancelled a check, disconnect all peers
      for (pair <- data.pairs.values) CommsTower forget pair
      become(data, PreimageCheck.Finalized)

    case (PreimageCheck.CMDStart(hashes, hosts), PreimageCheck.Initial) =>
      become(
        PreimageCheck.CheckData(hosts.map(randomPair).toMap, hosts, hashes),
        PreimageCheck.Operational
      )
      for ((info, pair) <- data.pairs)
        CommsTower.listen(Set(listener), pair, info)

      after(30.seconds) {
        doCheck(true)
      }

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
      doProcess(PreimageCheck.CMDCancel)
      onComplete(collected)
    } else if (data.pending.isEmpty) {
      // Finish with whatever we have collected
      doProcess(PreimageCheck.CMDCancel)
      onComplete(collected)
    } else if (force) {
      // Finish with whatever we have collected
      doProcess(PreimageCheck.CMDCancel)
      onComplete(collected)
    }
  }

  def merge(
      currentData: PreimageCheck.CheckData,
      msg: ReplyPreimages
  ): PreimageCheck.CheckData = {
    currentData.copy(hashToPreimage =
      currentData.hashToPreimage ++ msg.preimages
        .map(Crypto.sha256(_))
        .zip(msg.preimages)
    )
  }
}
