package immortan.electrum

import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Try, Random, Success, Failure}
import org.json4s._
import org.json4s.native.JsonMethods
import scoin.{Block, BlockHeader, ByteVector32}
import scoin.ln.NodeAddress

import immortan.LNParams
import immortan.electrum.{
  CurrentBlockCount,
  ElectrumReady,
  ElectrumDisconnected,
  ElectrumClientStatus
}
import immortan.electrum.ElectrumClient.{
  SSL,
  ScriptHashSubscription,
  HeaderSubscriptionResponse,
  ScriptHashSubscriptionResponse
}
import immortan.electrum.ElectrumClientPool._

import LNParams.ec

class ElectrumClientPool(
    blockCount: AtomicLong,
    chainHash: ByteVector32,
    useOnion: Boolean = false,
    customAddress: Option[NodeAddress] = None
) { self =>
  val addresses =
    scala.collection.mutable.Map.empty[ElectrumClient, InetSocketAddress]
  var usedAddresses = Set.empty[InetSocketAddress]

  def blockHeight: Int = this.blockCount.get().toInt

  val scriptHashSubscriptions =
    scala.collection.mutable.Map
      .empty[ByteVector32, scala.collection.mutable.Map[
        String,
        ScriptHashSubscriptionResponse => Future[Unit]
      ]]
  val headerSubscriptions =
    scala.collection.mutable.Map
      .empty[String, HeaderSubscriptionResponse => Unit]

  private val awaitForLatestTip = Promise[HeaderSubscriptionResponse]()
  var latestTip: Future[HeaderSubscriptionResponse] = awaitForLatestTip.future

  def killClient(client: ElectrumClient, reason: String): Unit = {
    if (addresses.contains(client)) {
      System.err.println(
        s"[info][pool] disconnecting from client ${client.address}: $reason"
      )

      addresses -= client

      if (addresses.isEmpty) {
        System.err.println(s"[info][pool] no active connections left")
        EventStream.publish(ElectrumDisconnected)
      }

      // connect to a new one after a while
      val t = new java.util.Timer()
      val task = new java.util.TimerTask {
        def run() = {
          connect()
        }
      }
      t.schedule(task, 3000L)
    }

    // shutdown this one
    client.shutdown()
  }

  lazy val serverAddresses: List[ElectrumServerAddress] = customAddress match {
    case Some(address) =>
      List(
      )
    case None => {
      val addresses = Random.shuffle(loadFromChainHash(chainHash).toList)
      if (useOnion) addresses
      else
        addresses.filterNot(address =>
          address.address.getHostName().endsWith(".onion")
        )
    }
  }

  def initConnect(): Unit = customAddress match {
    case Some(_) => connect()
    case None =>
      val connections =
        Math.min(LNParams.maxChainConnectionsCount, serverAddresses.size)
      (0 until connections).foreach(_ => self.connect())
  }

  def connect(): Unit = {
    val pickedAddress = customAddress match {
      case Some(address) =>
        Some(
          ElectrumServerAddress(
            new InetSocketAddress(address.host, address.port),
            SSL.DECIDE
          )
        )
      case None =>
        pickAddress(serverAddresses, usedAddresses)
          .map { esa =>
            usedAddresses = usedAddresses + esa.address
            esa
          }
    }

    pickedAddress.foreach { esa =>
      val client = new ElectrumClient(
        self,
        esa,
        client =>
          // upon connecting to a new client, tell it to subscribe to all script hashes
          scriptHashSubscriptions.keys.foreach { sh =>
            client.request[ScriptHashSubscriptionResponse](
              ScriptHashSubscription(sh)
            )
          }
      )
      addresses += (client -> esa.address)
    }
  }

  def subscribeToHeaders(listenerId: String)(
      cb: HeaderSubscriptionResponse => Unit
  ): Future[HeaderSubscriptionResponse] = {
    headerSubscriptions += (listenerId -> cb)
    latestTip
  }

  def subscribeToScriptHash(
      listenerId: String,
      sh: ByteVector32
  )(cb: ScriptHashSubscriptionResponse => Unit): Unit = {
    val debouncedCallback = debounce(cb, 3.seconds)

    scriptHashSubscriptions.updateWith(sh) {
      case None => {
        // no one has subscribed to this scripthash yet, start
        addresses.keys.foreach {
          _.request[ScriptHashSubscriptionResponse](ScriptHashSubscription(sh))
        }
        Some(scala.collection.mutable.Map(listenerId -> debouncedCallback))
      }
      case Some(subs) =>
        Some(subs.concat(List(listenerId -> debouncedCallback)))
    }
  }

  def request[R <: ElectrumClient.Response](
      r: ElectrumClient.Request
  ): Future[R] = requestMany[R](r, 1).map(_.head)

  def requestMany[R <: ElectrumClient.Response](
      r: ElectrumClient.Request,
      clientsToUse: Int
  ): Future[List[R]] =
    Future.sequence[R, List, List[R]](
      Random
        .shuffle(addresses.keys.toList)
        .take(clientsToUse)
        .map { client =>
          client.request[R](r).transformWith {
            case Success(resp) => Future(resp)
            case Failure(err) => {
              killClient(client, s"request $r has failed with error $err")
              request[R](r)
            }
          }
        }
    )

  private var lastHeaderResponseEmitted: Option[HeaderSubscriptionResponse] =
    None
  def onHeader(resp: HeaderSubscriptionResponse): Unit = {
    val HeaderSubscriptionResponse(client, height, tip) = resp
    System.err.println(
      s"[debug][pool] got header $height from ${client.address}"
    )

    updateBlockCount(height)

    // if we didn't have any tips before, now we have one
    if (!awaitForLatestTip.isCompleted) {
      awaitForLatestTip.success(resp)
      EventStream.publish(ElectrumReady(height, tip))
    }

    latestTip = Future(resp)

    if (lastHeaderResponseEmitted != Some(resp)) {
      headerSubscriptions.values.foreach { _(resp) }
      lastHeaderResponseEmitted = Some(resp)
    }
  }

  def onScriptHash(resp: ScriptHashSubscriptionResponse): Unit =
    scriptHashSubscriptions
      .get(resp.scriptHash)
      .foreach(_.values.foreach(_(resp)))

  private def updateBlockCount(blockCount: Long): Unit = {
    // when synchronizing we don't want to advertise previous blocks
    if (this.blockCount.get() < blockCount) {
      // System.err.println(s"[debug][pool] current blockchain height=$blockCount")
      EventStream.publish(CurrentBlockCount(blockCount))
      this.blockCount.set(blockCount)
    }
  }
}

object ElectrumClientPool {
  case class ElectrumServerAddress(address: InetSocketAddress, ssl: SSL)
  def loadFromChainHash(chainHash: ByteVector32): Set[ElectrumServerAddress] =
    readServerAddresses(
      classOf[
        ElectrumServerAddress
      ] getResourceAsStream ("/electrum/servers_" +
        (chainHash match {
          case Block.LivenetGenesisBlock.hash => "mainnet.json"
          case Block.SignetGenesisBlock.hash  => "signet.json"
          case Block.TestnetGenesisBlock.hash => "testnet.json"
          case Block.RegtestGenesisBlock.hash => "regtest.json"
          case _ =>
            throw new RuntimeException(
              "missing electrum servers for given chain"
            )
        }))
    )

  def readServerAddresses(stream: InputStream): Set[ElectrumServerAddress] =
    try {
      val JObject(values) = JsonMethods.parse(stream): @unchecked

      for ((name, fields) <- values.toSet) yield {
        val port = Try((fields \ "s").asInstanceOf[JString].s.toInt).toOption
          .getOrElse(0)
        val address = InetSocketAddress.createUnresolved(name, port)
        ElectrumServerAddress(address, SSL.LOOSE)
      }
    } finally {
      stream.close
    }

  def pickAddress(
      serverAddresses: List[ElectrumServerAddress],
      usedAddresses: Set[InetSocketAddress] = Set.empty
  ): Option[ElectrumServerAddress] =
    serverAddresses
      .filterNot(serverAddress => usedAddresses contains serverAddress.address)
      .toSeq
      .headOption

  type TipAndHeader = (Int, BlockHeader)
}
