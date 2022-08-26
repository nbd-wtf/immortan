package immortan

import java.net.Socket
import java.util.concurrent.{ConcurrentHashMap, Executors}
import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import rx.lang.scala.{Observable, Subscription}
import scodec.bits.ByteVector
import scodec.Attempt
import scoin.hc.HostedChannelMessage
import scoin._
import scoin.Crypto.PublicKey
import scoin.ln._
import scoin.ln.LightningMessageCodecs.lightningMessageCodec
import scoin.hc.HostedChannelCodecs.hostedMessageCodec

import immortan._
import immortan.crypto.Noise.KeyPair
import immortan.crypto.Tools.none

case class KeyPairAndPubKey(keyPair: KeyPair, them: PublicKey)

object CommsTower {
  type Listeners = Set[ConnectionListener]

  val workers: mutable.Map[KeyPairAndPubKey, Worker] =
    new ConcurrentHashMap[KeyPairAndPubKey, Worker].asScala

  val listeners: mutable.Map[KeyPairAndPubKey, Listeners] =
    new ConcurrentHashMap[
      KeyPairAndPubKey,
      Listeners
    ].asScala withDefaultValue Set.empty

  def listen(
      newListeners: Set[ConnectionListener],
      pair: KeyPairAndPubKey,
      info: RemoteNodeInfo
  ): Unit = synchronized {
    // Update and either
    //   - insert a new worker; or
    //   - fire onOperational on NEW listeners if worker currently exists and online
    // First add listeners, then
    //   try to add worker because we may already have a connected worker, but no listeners
    listeners(pair) ++= newListeners

    workers.get(pair) match {
      case Some(worker) =>
        for (init <- worker.theirInit)
          worker.handleTheirRemoteInitMessage(newListeners, init)
      case None =>
        workers(pair) = new Worker(
          pair,
          info,
          new Array[Byte](1024),
          LNParams.connectionProvider.getSocket
        )
    }
  }

  def sendMany(
      messages: Iterable[LightningMessage],
      pair: KeyPairAndPubKey,
      channelKind: ChannelKind
  ): Unit =
    CommsTower.workers
      .get(pair)
      .foreach(worker => messages.foreach(worker.handler.process(_)))

  // Add or remove listeners to a connection where our nodeId is stable,
  //   not a randomly generated one (one which makes us seen as a constant peer by remote)
  def listenNative(
      newListeners: Set[ConnectionListener],
      remoteInfo: RemoteNodeInfo
  ): Unit = listen(newListeners, remoteInfo.nodeSpecificPair, remoteInfo)
  def rmListenerNative(
      info: RemoteNodeInfo,
      listener: ConnectionListener
  ): Unit = listeners(info.nodeSpecificPair) -= listener
  def disconnectNative(info: RemoteNodeInfo): Unit =
    workers.get(info.nodeSpecificPair).foreach(_.disconnect())

  def forget(keyPairAndPubKey: KeyPairAndPubKey): Unit = {
    // Important: first remove all listeners, then disconnect
    val worker = workers.get(keyPairAndPubKey)
    listeners.remove(keyPairAndPubKey)
    worker.foreach(_.disconnect())
  }

  class Worker(
      val pair: KeyPairAndPubKey,
      val info: RemoteNodeInfo,
      buffer: Array[Byte],
      val sock: Socket
  ) { self =>
    implicit val context: ExecutionContextExecutor =
      ExecutionContext fromExecutor Executors.newSingleThreadExecutor

    var lastMessage: Long = System.currentTimeMillis
    var theirInit: Option[Init] = Option.empty
    var pinging: Subscription = _

    val handler: TransportHandler =
      new TransportHandler(pair.keyPair, info.nodeId.value) {
        def handleEncryptedOutgoingData(data: ByteVector): Unit =
          try sock.getOutputStream.write(data.toArray)
          catch {
            case _: Throwable => disconnect()
          }

        def handleDecryptedIncomingData(data: ByteVector): Unit = {
          // Prevent pinger from disconnecting or sending pings
          val ourListeners: Listeners = listeners(pair)
          lastMessage = System.currentTimeMillis

          lightningMessageCodec.decode(data.bits) match {
            case Attempt.Successful(result) =>
              // this means this is a normal channel message since it is not using nonstandard types
              result.value match {
                case message: Init =>
                  handleTheirRemoteInitMessage(
                    ourListeners,
                    remoteInit = message
                  )
                case message: Ping =>
                  handler.process(
                    Pong(ByteVector.fromValidHex("00" * message.pongLength))
                  )
                case message =>
                  ourListeners.foreach(_.onMessage(self, message))
              }
            case Attempt.Failure(_) =>
              // in this case this is probably a hosted channel message
              hostedMessageCodec.decode(data.bits) match {
                case Attempt.Successful(result) =>
                  result.value match {
                    case message: HostedChannelMessage =>
                      ourListeners.foreach(_.onHostedMessage(self, message))
                    case message: LightningMessage =>
                      ourListeners.foreach(_.onMessage(self, message))
                  }

                case Attempt.Failure(_) => // ignore, bad message
              }
          }
        }

        def handleEnterOperationalState(): Unit = {
          pinging = Observable.interval(10.seconds) subscribe { _ =>
            if (lastMessage < System.currentTimeMillis - 45 * 1000L)
              disconnect()
            else if (lastMessage < System.currentTimeMillis - 20 * 1000L)
              sendPing()
          }

          // Send our node parameters
          handler.process(LNParams.ourInit)
        }
      }

    private[this] val thread = Future {
      sock.connect(info.address.socketAddress, 7500)
      handler.init()

      while (true) {
        val length = sock.getInputStream.read(buffer, 0, buffer.length)
        if (length < 0) throw new RuntimeException("Connection droppped")
        else handler.process(ByteVector.view(buffer.take(length)))
      }
    }

    thread.onComplete { _ =>
      // Will also run after forget
      try pinging.unsubscribe()
      catch none
      listeners(pair).foreach(_.onDisconnect(self))
      // Once disconnected, worker gets removed
      workers -= pair
    }

    def disconnect(): Unit = try sock.close
    catch none

    def handleTheirRemoteInitMessage(
        toListeners: Set[ConnectionListener],
        remoteInit: Init
    ): Unit = {
      // Use a separate variable for listeners here because a set of listeners provided to this method may be different
      // Account for a case where they disconnect while we are deciding on their features (do nothing in this case)
      // Important: always store their init once obtained, it may be used upstream (see sync listener)
      theirInit = Some(remoteInit)

      if (!thread.isCompleted) {
        val areFeaturesOK =
          Features.areCompatible(LNParams.ourInit.features, remoteInit.features)
        if (areFeaturesOK)
          for (lst <- toListeners) lst.onOperational(self, remoteInit)
        else disconnect()
      }
    }

    def sendPing(): Unit = {
      val payloadLength = (Crypto.randomBytes(1).toInt(signed = true) % 5) + 1
      val data = Crypto.randomBytes(length = payloadLength)
      handler.process(Ping(payloadLength, data))
    }

    def requestRemoteForceClose(channelId: ByteVector32): Unit = {
      handler.process(
        ChannelReestablish(
          channelId,
          0L,
          0L,
          randomKey,
          randomKey.publicKey
        )
      )
      handler.process(Fail(channelId, "please publish your local commitment"))
    }
  }
}

trait ConnectionListener {
  def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = none
  def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = none
  def onHostedMessage(
      worker: CommsTower.Worker,
      msg: HostedChannelMessage
  ): Unit = none
  def onDisconnect(worker: CommsTower.Worker): Unit = none
}
