package immortan.blockchain.electrum

import java.net.{InetSocketAddress, SocketAddress}
import java.util
import scala.annotation.{tailrec, nowarn}
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.string.{LineEncoder, StringDecoder}
import io.netty.handler.codec.{
  LineBasedFrameDecoder,
  MessageToMessageDecoder,
  MessageToMessageEncoder
}
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.resolver.NoopAddressResolverGroup
import io.netty.util.CharsetUtil
import io.netty.util.internal.logging.{InternalLoggerFactory, JdkLoggerFactory}
import org.json4s.JsonAST._
import org.json4s.native.JsonMethods
import org.json4s.{JInt, JLong, JString}
import org.json4s._
import scodec.bits.ByteVector
import scoin._

import immortan.blockchain.bitcoind.rpc.{Error, JsonRPCRequest, JsonRPCResponse}
import immortan.blockchain.electrum.ElectrumClient._
import immortan.blockchain.electrum.ElectrumChainSync
import immortan.blockchain.fee.FeeratePerKw
import immortan.LNParams

object ElectrumClient {
  val CLIENT_NAME = "3.3.6"
  val PROTOCOL_VERSION = "1.4"

  InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE)

  // this is expensive and shared with all clients
  val workerGroup = new NioEventLoopGroup

  def computeScriptHash(publicKeyScript: ByteVector): ByteVector32 =
    Crypto.sha256(publicKeyScript).reverse

  sealed trait Request { def contextOpt: Option[Any] = None }
  sealed trait Response { def contextOpt: Option[Any] = None }

  case class ServerVersion(clientName: String, protocolVersion: String)
      extends Request
  case class ServerVersionResponse(
      clientName: String,
      protocolVersion: String
  ) extends Response

  case object Ping extends Request
  case object PingResponse extends Response

  // this is for when we don't care about the response
  case object IrrelevantResponse extends Response

  case class GetAddressHistory(address: String) extends Request
  case class TransactionHistoryItem(height: Int, txHash: ByteVector32)
  case class GetAddressHistoryResponse(
      address: String,
      history: Seq[TransactionHistoryItem] = Nil
  ) extends Response

  case class GetScriptHashHistory(scriptHash: ByteVector32) extends Request
  case class GetScriptHashHistoryResponse(
      scriptHash: ByteVector32,
      history: List[TransactionHistoryItem] = Nil
  ) extends Response

  case class AddressListUnspent(address: String) extends Request
  case class UnspentItem(
      txHash: ByteVector32,
      txPos: Int,
      value: Long,
      height: Long
  ) {
    lazy val outPoint = OutPoint(txHash.reverse, txPos)
  }
  case class AddressListUnspentResponse(
      address: String,
      unspents: Seq[UnspentItem] = Nil
  ) extends Response

  case class ScriptHashListUnspent(scriptHash: ByteVector32) extends Request
  case class ScriptHashListUnspentResponse(
      scriptHash: ByteVector32,
      unspents: Seq[UnspentItem] = Nil
  ) extends Response

  case class BroadcastTransaction(tx: Transaction) extends Request
  case class BroadcastTransactionResponse(
      tx: Transaction,
      error: Option[Error] = None
  ) extends Response

  case class GetTransactionIdFromPosition(
      height: Int,
      txPos: Int,
      merkle: Boolean = false
  ) extends Request
  case class GetTransactionIdFromPositionResponse(
      txid: ByteVector32,
      height: Int,
      txPos: Int,
      merkle: Seq[ByteVector32] = Nil
  ) extends Response

  case class GetTransaction(
      txid: ByteVector32,
      override val contextOpt: Option[Any] = None
  ) extends Request
  case class GetTransactionResponse(
      tx: Transaction,
      override val contextOpt: Option[Any]
  ) extends Response

  case class GetHeader(height: Int) extends Request
  case class GetHeaderResponse(height: Int, header: BlockHeader)
      extends Response
  object GetHeaderResponse {
    def apply(t: (Int, BlockHeader)) = new GetHeaderResponse(t._1, t._2)
  }

  case class GetHeaders(startHeight: Int, count: Int, cpHeight: Int = 0)
      extends Request
  case class GetHeadersResponse(
      source: ElectrumClient,
      startHeight: Int,
      headers: Seq[BlockHeader],
      max: Int
  ) extends Response

  case class GetMerkle(
      txid: ByteVector32,
      height: Int,
      override val contextOpt: Option[Any] = None
  ) extends Request
  case class GetMerkleResponse(
      source: Option[ElectrumClient],
      txid: ByteVector32,
      merkle: List[ByteVector32],
      blockHeight: Int,
      pos: Int,
      override val contextOpt: Option[Any] = None
  ) extends Response {
    lazy val root: ByteVector32 = {
      @tailrec
      def loop(pos: Int, hashes: Seq[ByteVector32] = Nil): ByteVector32 = {
        if (hashes.length == 1) hashes.head
        else {
          val h =
            if (pos % 2 == 1) Crypto.hash256(hashes(1) ++ hashes.head)
            else Crypto.hash256(hashes.head ++ hashes(1))
          loop(pos / 2, h +: hashes.drop(2))
        }
      }
      loop(pos, txid.reverse +: merkle.map(b => b.reverse))
    }
  }

  case class ScriptHashSubscription(scriptHash: ByteVector32) extends Request
  case class ScriptHashSubscriptionResponse(
      scriptHash: ByteVector32,
      status: String
  ) extends Response

  case class HeaderSubscription() extends Request
  case class HeaderSubscriptionResponse(
      source: ElectrumClient,
      height: Int,
      header: BlockHeader
  ) extends Response

  case class Header(
      block_height: Long,
      version: Long,
      prev_block_hash: ByteVector32,
      merkle_root: ByteVector32,
      timestamp: Long,
      bits: Long,
      nonce: Long
  ) {
    def blockHeader = BlockHeader(
      version,
      prev_block_hash.reverse,
      merkle_root.reverse,
      timestamp,
      bits,
      nonce
    )

    lazy val block_hash: ByteVector32 = blockHeader.hash
    lazy val block_id: ByteVector32 = block_hash.reverse
  }

  object Header {
    def makeHeader(height: Long, header: BlockHeader) = ElectrumClient.Header(
      height,
      header.version,
      header.hashPreviousBlock.reverse,
      header.hashMerkleRoot.reverse,
      header.time,
      header.bits,
      header.nonce
    )

    val RegtestGenesisHeader: Header =
      makeHeader(0, Block.RegtestGenesisBlock.header)
    val TestnetGenesisHeader: Header =
      makeHeader(0, Block.TestnetGenesisBlock.header)
    val LivenetGenesisHeader: Header =
      makeHeader(0, Block.LivenetGenesisBlock.header)
  }

  case class TransactionHistory(history: Seq[TransactionHistoryItem])
      extends Response

  case class ServerError(request: Request, error: Error) extends Response

  sealed trait ElectrumEvent
  case class ElectrumReady(
      source: ElectrumClient,
      height: Int,
      tip: BlockHeader,
      serverAddress: InetSocketAddress
  ) extends ElectrumEvent
  case class ElectrumDisconnected(source: ElectrumClient) extends ElectrumEvent

  sealed trait SSL

  object SSL {
    case object DECIDE extends SSL
    case object LOOSE extends SSL
  }

  def parseResponse(
      receiver: ElectrumClient,
      input: String
  ): Either[Response, JsonRPCResponse] = {
    val json = JsonMethods.parse(new String(input))
    json \ "method" match {
      case JString(method) =>
        // this is a jsonrpc request, i.e. a subscription response
        val JArray(params) = json \ "params"
        Left(((method, params): @unchecked) match {
          case ("blockchain.headers.subscribe", jheader :: Nil) => {
            val (height, header) = parseBlockHeader(jheader)
            HeaderSubscriptionResponse(receiver, height, header)
          }
          case (
                "blockchain.scripthash.subscribe",
                JString(scriptHashHex) :: JNull :: Nil
              ) =>
            ScriptHashSubscriptionResponse(
              ByteVector32.fromValidHex(scriptHashHex),
              ""
            )
          case (
                "blockchain.scripthash.subscribe",
                JString(scriptHashHex) :: JString(status) :: Nil
              ) =>
            ScriptHashSubscriptionResponse(
              ByteVector32.fromValidHex(scriptHashHex),
              status
            )
        })
      case _ => Right(parseJsonRpcResponse(receiver, json))
    }
  }

  def parseJsonRpcResponse(
      receiver: ElectrumClient,
      json: JValue
  ): JsonRPCResponse = {
    val result = json \ "result"
    val error = json \ "error" match {
      case JNull    => None
      case JNothing => None
      case other =>
        val message = other \ "message" match {
          case JString(value) => value
          case _              => ""
        }
        val code = other \ " code" match {
          case JInt(value)  => value.intValue
          case JLong(value) => value.intValue
          case _            => 0
        }
        Some(Error(code, message))
    }
    val id = json \ "id" match {
      case JString(value) => value
      case JInt(value)    => value.toString()
      case JLong(value)   => value.toString
      case _              => ""
    }
    JsonRPCResponse(result, error, id)
  }

  def longField(jvalue: JValue, field: String): Long =
    (jvalue \ field: @unchecked) match {
      case JLong(value) => value.longValue
      case JInt(value)  => value.longValue
    }

  def intField(jvalue: JValue, field: String): Int =
    (jvalue \ field: @unchecked) match {
      case JLong(value) => value.intValue
      case JInt(value)  => value.intValue
    }

  def parseBlockHeader(json: JValue): (Int, BlockHeader) = {
    val height = intField(json, "height")
    val JString(hex) = json \ "hex"
    (height, BlockHeader.read(hex))
  }

  def makeRequest(request: Request, reqId: String): JsonRPCRequest =
    request match {
      case ServerVersion(clientName, protocolVersion) =>
        JsonRPCRequest(
          id = reqId,
          method = "server.version",
          params = clientName :: protocolVersion :: Nil
        )
      case Ping =>
        JsonRPCRequest(id = reqId, method = "server.ping", params = Nil)
      case GetAddressHistory(address) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.address.get_history",
          params = address :: Nil
        )
      case GetScriptHashHistory(scripthash) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.scripthash.get_history",
          params = scripthash.toHex :: Nil
        )
      case AddressListUnspent(address) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.address.listunspent",
          params = address :: Nil
        )
      case ScriptHashListUnspent(scripthash) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.scripthash.listunspent",
          params = scripthash.toHex :: Nil
        )
      case ScriptHashSubscription(scriptHash) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.scripthash.subscribe",
          params = scriptHash.toString() :: Nil
        )
      case BroadcastTransaction(tx) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.transaction.broadcast",
          params = Transaction.write(tx).toHex :: Nil
        )
      case GetTransactionIdFromPosition(height, tx_pos, merkle) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.transaction.id_from_pos",
          params = height :: tx_pos :: merkle :: Nil
        )
      case GetTransaction(txid, _) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.transaction.get",
          params = txid :: Nil
        )
      case HeaderSubscription() =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.headers.subscribe",
          params = Nil
        )
      case GetHeader(height) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.block.header",
          params = height :: Nil
        )
      case GetHeaders(start_height, count, _) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.block.headers",
          params = start_height :: count :: Nil
        )
      case GetMerkle(txid, height, _) =>
        JsonRPCRequest(
          id = reqId,
          method = "blockchain.transaction.get_merkle",
          params = txid :: height :: Nil
        )
    }

  def parseJsonResponse(
      source: ElectrumClient,
      request: Request,
      json: JsonRPCResponse
  ): Response = {
    json.error match {
      case Some(error) =>
        (request: @unchecked) match {
          case BroadcastTransaction(tx) =>
            BroadcastTransactionResponse(
              tx,
              Some(error)
            ) // for this request type, error are considered a "normal" response
          case _ => ServerError(request, error)
        }
      case None =>
        (request: @unchecked) match {
          case _: ServerVersion => {
            val JArray(jitems) = json.result
            val JString(clientName) = jitems.head
            val JString(protocolVersion) = jitems(1)
            ServerVersionResponse(clientName, protocolVersion)
          }
          case Ping => PingResponse
          case GetAddressHistory(address) => {
            val JArray(jitems) = json.result
            val items = jitems.map(jvalue => {
              val JString(tx_hash) = jvalue \ "tx_hash"
              val height = intField(jvalue, "height")
              TransactionHistoryItem(
                height,
                ByteVector32.fromValidHex(tx_hash)
              )
            })
            GetAddressHistoryResponse(address, items)
          }
          case GetScriptHashHistory(scripthash) => {
            val JArray(jitems) = json.result
            val items = jitems.map(jvalue => {
              val JString(tx_hash) = jvalue \ "tx_hash"
              val height = intField(jvalue, "height")
              TransactionHistoryItem(
                height,
                ByteVector32.fromValidHex(tx_hash)
              )
            })
            GetScriptHashHistoryResponse(scripthash, items)
          }
          case AddressListUnspent(address) => {
            val JArray(jitems) = json.result
            val items = jitems.map(jvalue => {
              val JString(tx_hash) = jvalue \ "tx_hash"
              val tx_pos = intField(jvalue, "tx_pos")
              val height = intField(jvalue, "height")
              val value = longField(jvalue, "value")
              UnspentItem(
                ByteVector32.fromValidHex(tx_hash),
                tx_pos,
                value,
                height
              )
            })
            AddressListUnspentResponse(address, items)
          }
          case ScriptHashListUnspent(scripthash) => {
            val JArray(jitems) = json.result
            val items = jitems.map(jvalue => {
              val JString(tx_hash) = jvalue \ "tx_hash"
              val tx_pos = intField(jvalue, "tx_pos")
              val height = longField(jvalue, "height")
              val value = longField(jvalue, "value")
              UnspentItem(
                ByteVector32.fromValidHex(tx_hash),
                tx_pos,
                value,
                height
              )
            })
            ScriptHashListUnspentResponse(scripthash, items)
          }
          case GetTransactionIdFromPosition(height, tx_pos, false) => {
            val JString(tx_hash) = json.result
            GetTransactionIdFromPositionResponse(
              ByteVector32.fromValidHex(tx_hash),
              height,
              tx_pos,
              Nil
            )
          }
          case GetTransactionIdFromPosition(height, tx_pos, true) => {
            val JString(tx_hash) = json.result \ "tx_hash"
            val JArray(hashes) = json.result \ "merkle"
            val leaves = hashes collect { case JString(value) =>
              ByteVector32.fromValidHex(value)
            }
            GetTransactionIdFromPositionResponse(
              ByteVector32.fromValidHex(tx_hash),
              height,
              tx_pos,
              leaves
            )
          }
          case GetTransaction(_, context_opt) => {
            val JString(hex) = json.result
            GetTransactionResponse(Transaction.read(hex), context_opt)
          }
          case HeaderSubscription() => {
            val (height, header) = parseBlockHeader(json.result)
            HeaderSubscriptionResponse(source, height, header)
          }
          case ScriptHashSubscription(scriptHash) => {
            json.result match {
              case JString(status) =>
                ScriptHashSubscriptionResponse(scriptHash, status)
              case _ => ScriptHashSubscriptionResponse(scriptHash, "")
            }
          }
          case BroadcastTransaction(tx) => {
            val JString(message) = json.result
            // if we got here, it means that the server's response does not contain an error and message should be our
            // transaction id. However, it seems that at least on testnet some servers still use an older version of the
            // Electrum protocol and return an error message in the result field
            Try(ByteVector32.fromValidHex(message)) match {
              case Success(txid) if txid == tx.txid =>
                BroadcastTransactionResponse(tx, None)
              case Success(txid) =>
                BroadcastTransactionResponse(
                  tx,
                  Some(
                    Error(
                      1,
                      s"response txid $txid does not match request txid ${tx.txid}"
                    )
                  )
                )
              case Failure(_) =>
                BroadcastTransactionResponse(tx, Some(Error(1, message)))
            }
          }
          case GetHeader(height) => {
            val JString(hex) = json.result
            GetHeaderResponse(height, BlockHeader.read(hex))
          }
          case GetHeaders(start_height, _, _) => {
            val max = intField(json.result, "max")
            val JString(hex) = json.result \ "hex"
            val bin = ByteVector.fromValidHex(hex).toArray
            val blockHeaders = bin.grouped(80).map(BlockHeader.read).toList
            GetHeadersResponse(source, start_height, blockHeaders, max)
          }
          case GetMerkle(txid, _, context_opt) => {
            val JArray(hashes) = json.result \ "merkle"
            val leaves = hashes collect { case JString(value) =>
              ByteVector32.fromValidHex(value)
            }
            val blockHeight = intField(json.result, "block_height")
            val JInt(pos) = json.result \ "pos"
            GetMerkleResponse(
              Some(source),
              txid,
              leaves,
              blockHeight,
              pos.toInt,
              context_opt
            )
          }
          case other => IrrelevantResponse
        }
    }
  }
}

class ElectrumClient(
    connectionPool: ElectrumClientPool,
    server: ElectrumClientPool.ElectrumServerAddress
)(implicit
    ac: castor.Context
) extends castor.StateMachineActor[Any] { self =>
  def address = server.address.getHostName()
  System.err.println(s"[info] connecting to $address")

  var requests =
    scala.collection.mutable.Map.empty[String, (Request, Promise[Response])]

  // We need to regularly send a ping in order not to get disconnected
  val cancelPingTrigger = {
    val t = new java.util.Timer()
    val task = new java.util.TimerTask { def run() = self.request(Ping) }
    t.schedule(task, 30000L, 30000L)
    () => task.cancel()
  }

  val b = new Bootstrap
  b channel classOf[NioSocketChannel]
  b group workerGroup

  b.option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
  b.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
  b.option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
  b.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)

  b handler new ChannelInitializer[SocketChannel] {
    override def initChannel(ch: SocketChannel): Unit = {
      if (server.ssl == SSL.LOOSE || server.address.getPort() == 50002) {
        val sslCtx = SslContextBuilder.forClient
          .trustManager(InsecureTrustManagerFactory.INSTANCE)
          .build
        ch.pipeline addLast sslCtx.newHandler(
          ch.alloc,
          server.address.getHostName(),
          server.address.getPort()
        )
      }

      // Inbound
      ch.pipeline.addLast(new LineBasedFrameDecoder(Int.MaxValue, true, true))
      ch.pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8))
      ch.pipeline.addLast(new ElectrumResponseDecoder)
      ch.pipeline.addLast(new ResponseHandler())

      // Outbound
      ch.pipeline.addLast(new LineEncoder)
      ch.pipeline.addLast(new JsonRPCRequestEncoder)

      // Error handler
      ch.pipeline.addLast(new ExceptionHandler)

      LNParams.connectionProvider.proxyAddress.foreach { address =>
        // Optional proxy which must be the first handler
        val handler = new Socks5ProxyHandler(address)
        ch.pipeline.addFirst(handler)
      }
    }
  }

  val channelOpenFuture: ChannelFuture =
    b.connect(server.address.getHostName(), server.address.getPort())

  if (LNParams.connectionProvider.proxyAddress.isDefined)
    b.resolver(NoopAddressResolverGroup.INSTANCE)

  channelOpenFuture addListeners new ChannelFutureListener {
    override def operationComplete(future: ChannelFuture): Unit = {
      if (!future.isSuccess) {
        // the connection was not open successfully, close this actor
        System.err.println(s"[info] failed to connect to $address: $future")
        self.close()
      } else {
        // if we are here it means the connection was opened successfully
        // listen for when the connection is closed
        future.channel.closeFuture addListener new ChannelFutureListener {
          override def operationComplete(future: ChannelFuture): Unit =
            // this just means it was closed
            self.close()
        }
      }
    }
  }

  class ExceptionHandler extends ChannelDuplexHandler {
    override def connect(
        ctx: ChannelHandlerContext,
        remoteAddress: SocketAddress,
        localAddress: SocketAddress,
        promise: ChannelPromise
    ): Unit = {
      val listener = new ChannelFutureListener {
        override def operationComplete(future: ChannelFuture): Unit =
          if (!future.isSuccess) self.close()
      }
      ctx.connect(remoteAddress, localAddress, promise addListener listener)
    }

    override def write(
        ctx: ChannelHandlerContext,
        msg: scala.Any,
        promise: ChannelPromise
    ): Unit = {
      val listener = new ChannelFutureListener {
        override def operationComplete(future: ChannelFuture): Unit = {
          if (!future.isSuccess) {
            System.err.println(s"[info] failed to write to $address: $future")
            self.close()
          }
        }
      }
      ctx.write(msg, promise addListener listener)
    }

    override def exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable
    ): Unit = self.close()
  }

  class ElectrumResponseDecoder extends MessageToMessageDecoder[String] {
    override def decode(
        ctx: ChannelHandlerContext,
        msg: String,
        out: util.List[AnyRef]
    ): Unit = {
      val s = msg.asInstanceOf[String]
      val r = parseResponse(self, s)
      out.add(r)
    }
  }

  class JsonRPCRequestEncoder extends MessageToMessageEncoder[JsonRPCRequest] {
    override def encode(
        ctx: ChannelHandlerContext,
        request: JsonRPCRequest,
        out: util.List[AnyRef]
    ): Unit = {
      import org.json4s.JsonDSL._
      import org.json4s._

      val json =
        ("method" -> request.method) ~ ("params" -> request.params.map {
          case s: String       => new JString(s)
          case b: ByteVector32 => new JString(b.toHex)
          case f: FeeratePerKw => new JLong(f.toLong)
          case b: Boolean      => new JBool(b)
          case t: Int          => new JInt(t)
          case t: Long         => new JLong(t)
          case t: Double       => new JDouble(t)
        }) ~ ("id" -> request.id) ~ ("jsonrpc" -> request.jsonrpc)
      val serialized = JsonMethods.compact(JsonMethods.render(json))
      out.add(serialized)
    }
  }

  class ResponseHandler() extends ChannelInboundHandlerAdapter {
    override def channelActive(ctx: ChannelHandlerContext): Unit =
      self.send(ctx)

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
      msg match {
        case Right(json: JsonRPCResponse) => {
          requests.get(json.id) match {
            case Some((request, promise)) => {
              promise.success(parseJsonResponse(self, request, json))
              requests.remove(json.id)
            }
            case None => {}
          }
        }

        case Left(response @ HeaderSubscriptionResponse(_, height, tip)) => {
          headerSubscriptions.foreach(_.send(response))
        }

        case Left(response @ ScriptHashSubscriptionResponse(scriptHash, _)) => {
          scriptHashSubscriptions
            .get(response.scriptHash)
            .foreach(listeners => listeners.foreach(_.send(response)))
        }
      }
  }

  var scriptHashSubscriptions =
    Map.empty[ByteVector32, Set[castor.SimpleActor[Any]]]
  val headerSubscriptions =
    collection.mutable.HashSet.empty[castor.SimpleActor[Any]]
  val statusListeners =
    collection.mutable.HashSet
      .empty[castor.SimpleActor[Any]]
  var reqId = 0

  def initialState = Disconnected()
  def stay = state

  case class Disconnected()
      extends State({
        case ctx: ChannelHandlerContext => {
          request(ServerVersion(CLIENT_NAME, PROTOCOL_VERSION), ctx)
            .onComplete {
              case Success(v: ServerVersionResponse) => {
                headerSubscriptions += self
                request(HeaderSubscription(), ctx)
                  .onComplete {
                    case Success(w: HeaderSubscriptionResponse) => self.send(w)
                    case _                                      => {}
                  }
              }
              case err => {
                self.close()
                stay
              }
            }
          WaitingForHeader(ctx)
        }

        case _ => stay
      })

  case class WaitingForHeader(ctx: ChannelHandlerContext)
      extends State({
        case HeaderSubscriptionResponse(_, height, tip) => {
          statusListeners.foreach(
            _.send(ElectrumReady(self, height, tip, server.address))
          )
          Connected(ctx, height, tip)
        }

        case _ => stay
      })

  case class Connected(
      ctx: ChannelHandlerContext,
      height: Int,
      tip: BlockHeader
  ) extends State({
        case HeaderSubscriptionResponse(_, height, tip) =>
          Connected(ctx, height, tip)

        case PoisonPill => {
          self.close()
          Disconnected()
        }

        case _ => stay
      })

  def addStatusListener(listener: castor.SimpleActor[Any]): Unit = {
    statusListeners += listener

    state match {
      case d: Connected =>
        listener.send(ElectrumReady(self, d.height, d.tip, server.address))
      case _ => {}
    }
  }

  def removeStatusListener(listener: castor.SimpleActor[Any]): Unit =
    statusListeners -= listener

  def subscribeToHeaders(listener: castor.SimpleActor[Any]): Unit = {
    headerSubscriptions += listener
    state match {
      case d: Connected =>
        listener.send(HeaderSubscriptionResponse(self, d.height, d.tip))
      case _ => {}
    }
  }

  def subscribeToScriptHash(
      scriptHash: ByteVector32,
      listener: castor.SimpleActor[Any]
  ): Unit = {
    scriptHashSubscriptions = scriptHashSubscriptions.updated(
      scriptHash,
      scriptHashSubscriptions.getOrElse(scriptHash, Set()) + listener
    )
    request(ScriptHashSubscription(scriptHash))
  }

  def request(r: Request, ctx: ChannelHandlerContext): Future[Response] = {
    val promise = Promise[Response]()

    val electrumRequestId = reqId.toString
    if (ctx.channel.isWritable) {
      ctx.channel.writeAndFlush(makeRequest(r, electrumRequestId))
      reqId = reqId + 1
      requests += (electrumRequestId -> ((r, promise)))
    } else {
      self.close()
      promise.failure(
        new Exception(
          s"channel not writable. connection to $address was closed."
        )
      )
    }

    promise.future
  }

  def request(r: Request): Future[Response] = {
    state match {
      case Connected(ctx, _, _) => request(r, ctx)
      case otherstate =>
        Future
          .failed(
            new Exception(
              s"failed to make request for $r; client must be Connected, not $otherstate"
            )
          )
    }
  }

  def close(): Unit = {
    statusListeners.foreach(_.send(ElectrumDisconnected(self)))
    cancelPingTrigger()
    state match {
      case _: Disconnected       => {}
      case WaitingForHeader(ctx) => ctx.close()
      case Connected(ctx, _, _)  => ctx.close()
    }
  }
}
