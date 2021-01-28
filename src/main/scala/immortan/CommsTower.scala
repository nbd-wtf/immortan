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


case class KeyPairAndPubKey(keyPair: KeyPair, them: PublicKey)

object CommsTower {
  type Listeners = Set[ConnectionListener]
  val listeners: mutable.Map[KeyPairAndPubKey, Listeners] = new ConcurrentHashMap[KeyPairAndPubKey, Listeners].asScala withDefaultValue Set.empty
  val workers: mutable.Map[KeyPairAndPubKey, Worker] = new ConcurrentHashMap[KeyPairAndPubKey, Worker].asScala

  final val PROCESSING_DATA = 1
  final val AWAITING_MESSAGES = 2
  final val AWAITING_PONG = 3

  def listen(listeners1: Set[ConnectionListener], pair: KeyPairAndPubKey, ann: NodeAnnouncement, ourInit: Init): Unit = synchronized {
    // Update and either insert a new worker or fire onOperational on new listeners iff worker currently exists and is online
    // First add listeners, then try to add worker because we may already have a connected worker, but no listeners
    listeners(pair) ++= listeners1

    workers.get(pair) map { presentWorker =>
      // A connected worker is already present, inform listener if it's established
      presentWorker.theirInit.foreach(presentWorker handleTheirRemoteInitMessage listeners1)
    } getOrElse {
      // No worker is present, add a new one and try to connect right away
      workers(pair) = new Worker(pair, ann, ourInit, new Bytes(1024), new Socket)
    }
  }

  def forget(pair: KeyPairAndPubKey): Unit = {
    // First remove all listeners, then disconnect
    // this ensures listeners won't try to reconnect

    listeners.remove(pair)
    workers.get(pair).foreach(_.disconnect)
  }

  def sendMany(messages: Traversable[LightningMessage], pair: KeyPairAndPubKey): Unit =
    CommsTower.workers.get(pair).foreach(messages foreach _.handler.process)

  class Worker(val pair: KeyPairAndPubKey, val ann: NodeAnnouncement, ourInit: Init, buffer: Bytes, sock: Socket) { me =>
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
        val areFeaturesOK = Features.areCompatible(ourInit.features, remoteInit.features)
        val areNetworksOK = remoteInit.networks.nonEmpty && remoteInit.networks.intersect(ourInit.networks).isEmpty
        if (areFeaturesOK && areNetworksOK) for (lst <- listeners1) lst.onOperational(me, remoteInit) // They have not disconnected yet
        else disconnect // Their features are not supported but they have not disconnected yet, so we disconnect right away
      }
    }

    def sendPingAwaitPong(length: Int): Unit = {
      val pingPayload: ByteVector = randomBytes(length)
      handler process Ping(length, pingPayload)
      pingState = AWAITING_PONG
    }

    val handler: TransportHandler =
      new TransportHandler(pair.keyPair, ann.nodeId.value) {
        def handleEncryptedOutgoingData(data: ByteVector): Unit =
          try sock.getOutputStream.write(data.toArray) catch {
            case _: Throwable => disconnect
          }

        def handleDecryptedIncomingData(data: ByteVector): Unit = {
          // Prevent pinger from disconnecting or sending pings
          val ourListenerSet = listeners(pair)
          pingState = PROCESSING_DATA

          lightningMessageCodecWithFallback.decode(data.bits).require.value match {
            case message: UnknownMessage => ExtMessageMapping.decode(message) match {
              case message: HostedChannelMessage => for (lst <- ourListenerSet) lst.onHostedMessage(me, message)
              case message: SwapOut => for (lst <- ourListenerSet) lst.onSwapOutMessage(me, message)
              case message: SwapIn => for (lst <- ourListenerSet) lst.onSwapInMessage(me, message)
              case message => for (lst <- ourListenerSet) lst.onMessage(me, message)
            }

            case message: Init => handleTheirRemoteInitMessage(ourListenerSet)(remoteInit = message)
            case message: Ping => handler process Pong(ByteVector fromValidHex "00" * message.pongLength)
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
      listeners(pair).foreach(_ onDisconnect me)
      workers -= pair
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