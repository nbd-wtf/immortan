package fr.acinq.eclair.blockchain.electrum

import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Try, Random, Success, Failure}

import fr.acinq.bitcoin.{Block, BlockHeader, ByteVector32}
import fr.acinq.eclair.wire.NodeAddress
import fr.acinq.eclair.blockchain.electrum.{
  CurrentBlockCount,
  ElectrumReady,
  ElectrumDisconnected,
  ElectrumClientStatus
}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{
  SSL,
  scriptHashStatusMatches,
  TransactionHistoryItem,
  ScriptHashSubscription,
  HeaderSubscriptionResponse,
  GetScriptHashHistory,
  GetScriptHashHistoryResponse,
  ScriptHashSubscriptionResponse
}
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool._
import immortan.LNParams
import org.json4s.JsonAST.{JObject, JString}
import org.json4s.native.JsonMethods

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
  var connected: Boolean = false

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

  def killClient(client: ElectrumClient): Unit = {
    if (addresses.contains(client)) {
      System.err.println(
        s"[info][pool] disconnecting from client ${client.address}"
      )

      addresses -= client

      if (addresses.isEmpty) {
        System.err.println(s"[info][pool] no active connections left")
        EventStream.publish(ElectrumDisconnected)
        connected = false
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
            address.socketAddress,
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

    if (pickedAddress.isEmpty) {
      // we've exhausted all our addresses, this only happens
      //  when there is some weird problem with our connection
      //  so wait a while and start over
      val t = new java.util.Timer()
      val task = new java.util.TimerTask {
        def run() = {
          usedAddresses = Set.empty
          initConnect()
        }
      }
      t.schedule(task, 10000L)
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

  def getScriptHashHistory(
      scriptHash: ByteVector32,
      matchStatus: Option[String] = None,
      attemptN: Int = 0
  ): Future[List[TransactionHistoryItem]] =
    request[GetScriptHashHistoryResponse](GetScriptHashHistory(scriptHash))
      .map { case GetScriptHashHistoryResponse(_, items) => items }
      .flatMap { items =>
        matchStatus match {
          case None => Future(items)
          case Some(status) =>
            if (scriptHashStatusMatches(items, status))
              Future(items)
            else if (attemptN < 5) {
              // the history didn't match the status we were looking for, wait a little and try again
              val p = Promise[List[TransactionHistoryItem]]()
              val t = new java.util.Timer()
              val task = new java.util.TimerTask {
                def run() = {
                  getScriptHashHistory(scriptHash, Some(status), attemptN + 1)
                    .onComplete(
                      p.complete(_)
                    )
                }
              }
              t.schedule(task, 3000L)
              p.future
            } else
              // we only try that for a while, then we give up and fail
              Future.failed(
                new Exception("failed to get a script hash history that works")
              )
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
              System.err.println(
                s"[warn][pool] request $r to ${client.address} has failed with error $err, disconnecting from it and trying with another"
              )
              killClient(client)
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
    }

    // mark us as connected and emit event
    if (!connected) {
      EventStream.publish(ElectrumReady(height, tip))
      connected = true
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
      val JObject(values) = JsonMethods.parse(stream)

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
