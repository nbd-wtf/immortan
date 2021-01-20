package immortan

import fr.acinq.eclair._
import scala.concurrent._
import fr.acinq.eclair.wire._
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import immortan.crypto.Tools.{Bytes, none}
import rx.lang.scala.{Subscription, Observable}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import fr.acinq.eclair.wire.LightningMessageCodecs.lightningMessageCodecWithFallback
import fr.acinq.bitcoin.Crypto.PublicKey
import immortan.crypto.Noise.KeyPair
import fr.acinq.eclair.Features
import scala.collection.mutable
import scodec.bits.ByteVector
import java.net.Socket


case class PublicKeyAndPair(them: PublicKey, keyPair: KeyPair)

object CommsTower {
  type Listeners = Set[ConnectionListener]
  val workers: mutable.Map[PublicKeyAndPair, Worker] = new ConcurrentHashMap[PublicKeyAndPair, Worker].asScala
  val listeners: mutable.Map[PublicKeyAndPair, Listeners] = new ConcurrentHashMap[PublicKeyAndPair, Listeners].asScala.withDefaultValue(Set.empty)

  final val PROCESSING_DATA = 1
  final val AWAITING_MESSAGES = 2
  final val AWAITING_PONG = 3

  def listen(listeners1: Set[ConnectionListener], pkap: PublicKeyAndPair, ann: NodeAnnouncement, ourInit: Init): Unit = synchronized {
    // Update and either insert a new worker or fire onOperational on new listeners iff worker currently exists and is online
    // First add listeners, then try to add worker because we may already have a connected worker, but no listeners
    listeners(pkap) ++= listeners1

    workers.get(pkap) map { presentWorker =>
      // A connected worker is already present, inform listener if it's established
      presentWorker.theirInit.foreach(presentWorker handleTheirRemoteInitMessage listeners1)
    } getOrElse {
      // No worker is present, add a new one and try to connect right away
      workers(pkap) = new Worker(pkap, ann, ourInit, new Bytes(1024), new Socket)
    }
  }

  def forget(pkap: PublicKeyAndPair): Unit = {
    // First remove all listeners, then disconnect
    // this ensures listeners won't try to reconnect

    listeners.remove(pkap)
    workers.get(pkap).foreach(_.disconnect)
  }

  class Worker(val pkap: PublicKeyAndPair, val ann: NodeAnnouncement, ourInit: Init, buffer: Bytes, sock: Socket) { me =>
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor

    var pingState: Int = AWAITING_MESSAGES
    var theirInit: Option[Init] = None
    var pinging: Subscription = _

    def disconnect: Unit = try sock.close catch none

    def handleTheirRemoteInitMessage(listeners1: Set[ConnectionListener] = Set.empty)(remoteInit: Init): Unit = {
      // Use a separate variable for listeners here because a set of listeners provided to this method may be different
      // Account for a case where they disconnect while we are deciding on their features (do nothing in this case)
      theirInit = Some(remoteInit)

      if (!thread.isCompleted) {
        val areSupported = Features.areSupported(remoteInit.features)
        if (areSupported) for (lst <- listeners1) lst.onOperational(me, remoteInit) // They have not disconnected yet
        else disconnect // Their features are not supported and they have not disconnected yet, so we disconnect
      }
    }

    def sendPingAwaitPong(length: Int): Unit = {
      val pingPayload: ByteVector = randomBytes(length)
      handler process Ping(length, pingPayload)
      pingState = AWAITING_PONG
    }

    val handler: TransportHandler =
      new TransportHandler(pkap.keyPair, ann.nodeId.value) {
        def handleEncryptedOutgoingData(data: ByteVector): Unit =
          try sock.getOutputStream.write(data.toArray) catch {
            case _: Throwable => disconnect
          }

        def handleDecryptedIncomingData(data: ByteVector): Unit = {
          // Prevent pinger from disconnecting or sending pings
          val ourListenerSet = listeners(pkap)
          pingState = PROCESSING_DATA

          lightningMessageCodecWithFallback.decode(data.bits).require.value match {
            case message: Init => handleTheirRemoteInitMessage(ourListenerSet)(message)
            case message: Ping => if (message.pongLength > 0) handler process Pong(ByteVector fromValidHex "00" * message.pongLength)
            case message: HostedChannelMessage => for (lst <- ourListenerSet) lst.onHostedMessage(me, message)
            case message: SwapOut => for (lst <- ourListenerSet) lst.onSwapOutMessage(me, message)
            case message: SwapIn => for (lst <- ourListenerSet) lst.onSwapInMessage(me, message)
            case message => for (lst <- ourListenerSet) lst.onMessage(me, message)
          }

          // Allow pinger operations again
          pingState = AWAITING_MESSAGES
        }

        def handleEnterOperationalState: Unit = {
          pinging = Observable.interval(15.seconds).map(_ => secureRandom nextInt 10) subscribe { length =>
            // We disconnect if we are still awaiting Pong since our last sent Ping, meaning peer sent nothing back
            // otherise we send a Ping and enter awaiting Pong unless we are currently processing some incoming message
            if (AWAITING_PONG == pingState) disconnect else if (AWAITING_MESSAGES == pingState) sendPingAwaitPong(length + 1)
          }

          // Send our node parameters
          handler process ourInit
        }
      }

    private[this] val thread = Future {
      // Always use the first address, it's safe it throw here
      sock.connect(ann.addresses.head.socketAddress, 7500)
      handler.init

      while (true) {
        val length = sock.getInputStream.read(buffer, 0, buffer.length)
        if (length < 0) throw new RuntimeException("Connection droppped")
        else handler process ByteVector.view(buffer take length)
      }
    }

    thread onComplete { _ =>
      try pinging.unsubscribe catch none
      listeners(pkap).foreach(_ onDisconnect me)
      workers -= pkap
    }
  }
}

class ConnectionListener {
  def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = none
  def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = none
  def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = none
  def onSwapOutMessage(worker: CommsTower.Worker, msg: SwapOut): Unit = none
  def onSwapInMessage(worker: CommsTower.Worker, msg: SwapIn): Unit = none
  def onDisconnect(worker: CommsTower.Worker): Unit = none
}