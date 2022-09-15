package fr.acinq.eclair.blockchain.electrum

import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

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
  HeaderSubscriptionResponse,
  ScriptHashSubscriptionResponse
}
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool._
import immortan.LNParams
import org.json4s.JsonAST.{JObject, JString}
import org.json4s.native.JsonMethods

import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Try, Random}
import scala.annotation.nowarn

class ElectrumClientPool(
    blockCount: AtomicLong,
    chainHash: ByteVector32,
    useOnion: Boolean = false,
    customAddress: Option[NodeAddress] = None
)(implicit
    ac: castor.Context
) extends CastorStateMachineActorWithState[ElectrumClientStatus] { self =>
  val addresses =
    scala.collection.mutable.Map.empty[ElectrumClient, InetSocketAddress]

  @nowarn
  def resetWaitForConnected(): Unit = {
    val p = Promise[Connected]()
    reportConnected = (c: Connected) => Future { p.success(c) }
    waitForConnected = p.future
  }
  var reportConnected: Function[Connected, Future[Unit]] = _
  var waitForConnected: Future[Connected] = _
  resetWaitForConnected()

  def initialState: State = Disconnected()
  def stay = state

  case class Disconnected()
      extends State({
        case (ElectrumReady(source, height, tip, _), _)
            if addresses.contains(source) => {
          source.subscribeToHeaders("pool", onHeader)
          handleHeader(source, height, tip, None)
        }

        case (ElectrumDisconnected(source), _) => {
          val t = new java.util.Timer()
          val task = new java.util.TimerTask { def run() = self.connect() }
          t.schedule(task, 5000L)
          addresses -= source
          stay
        }

        case _ => stay
      })

  case class Connected(
      master: ElectrumClient,
      tips: Map[ElectrumClient, TipAndHeader]
  ) extends State({
        case (
              ElectrumReady(source, height, tip, _),
              d: Connected
            ) if addresses.contains(source) => {
          source.subscribeToHeaders("pool", onHeader)
          handleHeader(source, height, tip, Some(d))
        }

        case (ElectrumDisconnected(source), d: Connected)
            if addresses.contains(source) => {
          val address = addresses(source)
          val tips = d.tips - source

          val t = new java.util.Timer()
          val task = new java.util.TimerTask { def run() = self.connect() }
          t.schedule(task, 5000L)
          addresses -= source

          if (tips.isEmpty) {
            System.err.println(
              s"[info] lost connection to $address, no active connections left"
            )
            EventStream
              .publish(ElectrumDisconnected(master /* this will be ignored */ ))

            // no more connections
            resetWaitForConnected()
            Disconnected()
          } else if (d.master != source) {
            System.err.println(
              s"[debug][pool] lost connection to $address, we still have our master server"
            )

            val newState = Connected(
              master = master,
              tips = tips
            )
            reportConnected(newState)
            newState
          } else {
            System.err
              .println(s"[info] lost connection to our master server $address")
            // we choose next best candidate as master
            val tips = d.tips - source
            val (bestClient, bestTip) = tips.toSeq.maxBy(_._2._1)
            handleHeader(
              bestClient,
              bestTip._1,
              bestTip._2,
              Some(d.copy(tips = tips))
            )
          }
        }

        case _ => stay
      }) {
    def blockHeight: Int = tips.get(master).map(_._1).getOrElse(0)
  }

  lazy val serverAddresses: Set[ElectrumServerAddress] = customAddress match {
    case Some(address) =>
      Set(ElectrumServerAddress(address.socketAddress, SSL.DECIDE))
    case None => {
      val addresses = loadFromChainHash(chainHash)
      if (useOnion) addresses
      else
        addresses.filterNot(address =>
          address.address.getHostName().endsWith(".onion")
        )
    }
  }

  def initConnect(): Unit = {
    try {
      val connections =
        Math.min(LNParams.maxChainConnectionsCount, serverAddresses.size)
      (0 until connections).foreach(_ => self.connect())
    } catch {
      case _: java.lang.NoClassDefFoundError =>
    }
  }

  def connect(): Unit = {
    pickAddress(serverAddresses, addresses.values.toSet)
      .foreach { esa =>
        val client = new ElectrumClient(self, esa)
        client.addStatusListener(self.asInstanceOf[castor.SimpleActor[Any]])
        addresses += (client -> esa.address)
      }
  }

  def getReady: Option[ElectrumReady] = {
    state match {
      case _: Disconnected => None
      case Connected(master, tips) if addresses.contains(master) =>
        val (height, tip) = tips(master)
        Some(
          ElectrumReady(
            master, // this field is ignored by ElectrumClientPool listeners (since there is only one pool)
            height,
            tip,
            addresses(master)
          )
        )
      case _: Connected => None
    }
  }

  def subscribeToHeaders(
      id: String,
      cb: HeaderSubscriptionResponse => Unit
  ): Future[HeaderSubscriptionResponse] = waitForConnected.flatMap {
    case Connected(master, _) =>
      master.subscribeToHeaders(id, cb)
  }

  def subscribeToScriptHash(
      scriptHash: ByteVector32
  )(cb: ScriptHashSubscriptionResponse => Unit): Unit =
    waitForConnected.foreach { case Connected(master, _) =>
      master.subscribeToScriptHash(scriptHash)(cb)
    }

  def request[R <: ElectrumClient.Response](
      r: ElectrumClient.Request
  ): Future[R] =
    state match {
      case Connected(master, _) => master.request[R](r)
      case _ =>
        Future.failed(
          new Exception(
            "request must be called only after the client is connected"
          )
        )
    }

  def requestMany[R <: ElectrumClient.Response](
      r: ElectrumClient.Request,
      clientsToUse: Int
  ): Future[List[R]] =
    Future.sequence[R, List, List[R]](
      scala.util.Random
        .shuffle(addresses.keys.toList)
        .take(clientsToUse)
        .map(_.request(r))
    )

  private def onHeader(resp: HeaderSubscriptionResponse): Unit = {
    val HeaderSubscriptionResponse(source, height, tip) = resp
    handleHeader(
      source,
      height,
      tip,
      state match { case d: Connected => Some(d); case _ => None }
    )
  }

  private def handleHeader(
      connection: ElectrumClient,
      height: Int,
      tip: BlockHeader,
      data: Option[Connected] = None
  ): State = {
    System.err.println(
      s"[debug][pool] got header $height from ${connection.address}"
    )

    val remoteAddress = addresses(connection)

    // we update our block count even if it doesn't come from our current master
    updateBlockCount(height)

    val newState = data match {
      case None => {
        // as soon as we have a connection to an electrum server, we select it as master
        System.err.println(
          s"[info][pool] selecting master $remoteAddress at $height"
        )
        EventStream.publish(
          ElectrumReady(connection, height, tip, remoteAddress)
        )

        val newState = Connected(
          connection,
          Map(connection -> (height, tip))
        )
        reportConnected(newState)
        newState
      }
      case Some(d) if connection != d.master && height > d.blockHeight + 2 => {
        // we only switch to a new master if there is a significant difference with our current master, because
        // we don't want to switch to a new master every time a new block arrives (some servers will be notified before others)
        // we check that the current connection is not our master because on regtest when you generate several blocks at once
        // (and maybe on testnet in some pathological cases where there's a block every second) it may seen like our master
        // skipped a block and is suddenly at height + 2
        System.err.println(
          s"[info][pool] switching to master $remoteAddress at $tip"
        )
        // we've switched to a new master, treat this as a disconnection/reconnection
        // so users (wallet, watcher, ...) will reset their subscriptions
        EventStream.publish(ElectrumDisconnected(d.master))
        EventStream.publish(
          ElectrumReady(d.master, height, tip, remoteAddress)
        )

        val newState = Connected(
          master = connection,
          tips = d.tips + (connection -> (height, tip))
        )
        reportConnected(newState)
        newState
      }
      case Some(d) =>
        System.err.println(
          s"[debug][pool] bumping our tip for ${connection.address} to $height->${tip.blockId.toHex.take(26)}"
        )
        d.copy(tips = d.tips + (connection -> (height, tip)))
    }

    setState(newState)

    newState
  }

  private def updateBlockCount(blockCount: Long): Unit = {
    // when synchronizing we don't want to advertise previous blocks
    if (this.blockCount.get() < blockCount) {
      System.err.println(s"[debug][pool] current blockchain height=$blockCount")
      EventStream.publish(CurrentBlockCount(blockCount))
      this.blockCount.set(blockCount)
    }
  }

  def master = state match {
    case Connected(master, _) => Some(master)
    case _                    => None
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
          case _                              => throw new RuntimeException
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
      serverAddresses: Set[ElectrumServerAddress],
      usedAddresses: Set[InetSocketAddress] = Set.empty
  ): Option[ElectrumServerAddress] =
    Random
      .shuffle(
        serverAddresses
          .filterNot(serverAddress =>
            usedAddresses contains serverAddress.address
          )
          .toSeq
      )
      .headOption

  type TipAndHeader = (Int, BlockHeader)
}
