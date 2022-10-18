package immortan.electrum

import java.util.concurrent.atomic.AtomicInteger
import java.net.{InetSocketAddress, SocketAddress}
import scala.annotation.tailrec
import scala.concurrent.{Promise, Future}
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
import org.json4s._
import org.json4s.native.JsonMethods
import org.json4s.{JInt, JLong, JString}
import scodec.bits.ByteVector
import scoin._

import immortan.LNParams
import immortan.LNParams.ec
import immortan.electrum.ElectrumClient._

trait ElectrumEvent

sealed trait ElectrumClientStatus extends ElectrumEvent
case class ElectrumReady(height: Int, tip: BlockHeader)
    extends ElectrumClientStatus
case object ElectrumDisconnected extends ElectrumClientStatus

case class CurrentBlockCount(blockCount: Long) extends ElectrumEvent

object ElectrumClient {
  val CLIENT_NAME = "3.3.6"
  val PROTOCOL_VERSION = "1.4"

  InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE)

  class ElectrumServerError(msg: String) extends Exception

  // this is expensive and shared with all clients
  val workerGroup = new NioEventLoopGroup

  def computeScriptHash(publicKeyScript: ByteVector): ByteVector32 =
    Crypto.sha256(publicKeyScript).reverse

  def computeScriptHashStatus(items: List[TransactionHistoryItem]): String =
    Crypto
      .sha256(
        ByteVector.view(
          items
            .map { case TransactionHistoryItem(height, txid) =>
              s"${txid.toHex}:$height:"
            }
            .mkString("")
            .getBytes()
        )
      )
      .toHex

  def scriptHashStatusMatches(
      history: List[TransactionHistoryItem],
      status: String
  ): Boolean =
    product(
      history
        .groupBy {
          case TransactionHistoryItem(height, _) if height <= 0 => 99999999
          case TransactionHistoryItem(height, _)                => height
        }
        .toList
        .sortBy(_._1)
        .map(_._2)
        .map(items => items.permutations.toList)
    )
      .map(_.flatten)
      .map(computeScriptHashStatus(_))
      .find(_ == status)
      .isDefined

  private def product[T](xss: List[List[T]]): List[List[T]] = xss match {
    case Nil    => List(Nil)
    case h :: t => for (xh <- h; xt <- product(t)) yield xh :: xt
  }

  sealed trait Request { def contextOpt: Option[Any] = None }
  sealed trait Response extends ElectrumEvent {
    def contextOpt: Option[Any] = None
  }

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

  case class TransactionHistoryItem(height: Int, txHash: ByteVector32)

  case class GetScriptHashHistory(scriptHash: ByteVector32) extends Request
  case class GetScriptHashHistoryResponse(
      scriptHash: ByteVector32,
      history: List[TransactionHistoryItem] = Nil
  ) extends Response

  case class GetScriptHashMempool(scriptHash: ByteVector32) extends Request
  case class GetScriptHashMempoolResponse(
      scriptHash: ByteVector32,
      mempool: List[TransactionHistoryItem] = Nil
  ) extends Response

  case class BroadcastTransaction(tx: Transaction) extends Request
  case class BroadcastTransactionResponse(
      tx: Transaction,
      error: Option[JSONRPC.Error] = None
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
    val SignetGenesisHeader: Header =
      makeHeader(0, Block.SignetGenesisBlock.header)
    val TestnetGenesisHeader: Header =
      makeHeader(0, Block.TestnetGenesisBlock.header)
    val LivenetGenesisHeader: Header =
      makeHeader(0, Block.LivenetGenesisBlock.header)
  }

  case class TransactionHistory(history: Seq[TransactionHistoryItem])
      extends Response

  case class ServerError(request: Request, error: JSONRPC.Error)
      extends Response

  sealed trait SSL

  object SSL {
    case object DECIDE extends SSL
    case object LOOSE extends SSL
  }

  def parseResponse(
      receiver: ElectrumClient,
      input: String
  ): Either[Response, JSONRPC.Response] = {
    val json = JsonMethods.parse(input)
    json \ "method" match {
      case JString(method) =>
        // this is a jsonrpc request, i.e. a subscription response
        val JArray(params) = json \ "params": @unchecked
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
  ): JSONRPC.Response = {
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
        Some(JSONRPC.Error(code, message))
    }
    val id = json \ "id" match {
      case JString(value) => value
      case JInt(value)    => value.toString()
      case JLong(value)   => value.toString
      case _              => ""
    }
    JSONRPC.Response(result, error, id)
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
    val JString(hex) = json \ "hex": @unchecked
    (height, BlockHeader.read(hex))
  }

  def makeRequest(request: Request, reqId: String): JSONRPC.Request =
    request match {
      case ServerVersion(clientName, protocolVersion) =>
        JSONRPC.Request(
          id = reqId,
          method = "server.version",
          params = clientName :: protocolVersion :: Nil
        )
      case Ping =>
        JSONRPC.Request(id = reqId, method = "server.ping", params = Nil)
      case GetScriptHashHistory(scripthash) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.scripthash.get_history",
          params = scripthash.toHex :: Nil
        )
      case GetScriptHashMempool(scripthash) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.scripthash.get_mempool",
          params = scripthash.toHex :: Nil
        )
      case ScriptHashSubscription(scriptHash) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.scripthash.subscribe",
          params = scriptHash.toString() :: Nil
        )
      case BroadcastTransaction(tx) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.transaction.broadcast",
          params = Transaction.write(tx).toHex :: Nil
        )
      case GetTransaction(txid, _) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.transaction.get",
          params = txid :: Nil
        )
      case HeaderSubscription() =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.headers.subscribe",
          params = Nil
        )
      case GetHeader(height) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.block.header",
          params = height :: Nil
        )
      case GetHeaders(start_height, count, _) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.block.headers",
          params = start_height :: count :: Nil
        )
      case GetMerkle(txid, height, _) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.transaction.get_merkle",
          params = txid :: height :: Nil
        )
    }

  def parseJsonResponse(
      source: ElectrumClient,
      request: Request,
      json: JSONRPC.Response
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
            val JArray(jitems) = json.result: @unchecked
            val JString(clientName) = jitems.head: @unchecked
            val JString(protocolVersion) = jitems(1): @unchecked
            ServerVersionResponse(clientName, protocolVersion)
          }
          case Ping => PingResponse
          case GetScriptHashHistory(scripthash) => {
            val JArray(jitems) = json.result: @unchecked
            val items = jitems.map(jvalue => {
              val JString(tx_hash) = jvalue \ "tx_hash": @unchecked
              val height = intField(jvalue, "height")
              TransactionHistoryItem(
                height,
                ByteVector32.fromValidHex(tx_hash)
              )
            })
            GetScriptHashHistoryResponse(scripthash, items)
          }
          case GetScriptHashMempool(scripthash) => {
            val JArray(jitems) = json.result: @unchecked
            val items = jitems.map(jvalue => {
              val JString(tx_hash) = jvalue \ "tx_hash": @unchecked
              val height = intField(jvalue, "height")
              TransactionHistoryItem(
                height,
                ByteVector32.fromValidHex(tx_hash)
              )
            })
            GetScriptHashMempoolResponse(scripthash, items)
          }
          case GetTransaction(_, contextOpt) => {
            val JString(hex) = json.result: @unchecked
            GetTransactionResponse(Transaction.read(hex), contextOpt)
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
            val JString(message) = json.result: @unchecked
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
                    JSONRPC.Error(
                      1,
                      s"response txid $txid does not match request txid ${tx.txid}"
                    )
                  )
                )
              case Failure(_) =>
                BroadcastTransactionResponse(
                  tx,
                  Some(JSONRPC.Error(1, message))
                )
            }
          }
          case GetHeader(height) => {
            val JString(hex) = json.result: @unchecked
            GetHeaderResponse(height, BlockHeader.read(hex))
          }
          case GetHeaders(start_height, _, _) => {
            val max = intField(json.result, "max")
            val JString(hex) = json.result \ "hex": @unchecked
            val bin = ByteVector.fromValidHex(hex).toArray
            val blockHeaders = bin.grouped(80).map(BlockHeader.read).toList
            GetHeadersResponse(source, start_height, blockHeaders, max)
          }
          case GetMerkle(txid, _, contextOpt) => {
            val JArray(hashes) = json.result \ "merkle": @unchecked
            val leaves = hashes collect { case JString(value) =>
              ByteVector32.fromValidHex(value)
            }
            val blockHeight = intField(json.result, "block_height")
            val JInt(pos) = json.result \ "pos": @unchecked
            GetMerkleResponse(
              Some(source),
              txid,
              leaves,
              blockHeight,
              pos.toInt,
              contextOpt
            )
          }
          case other => IrrelevantResponse
        }
    }
  }
}

object JSONRPC {
  case class Request(
      jsonrpc: String = "2.0",
      id: String,
      method: String,
      params: Seq[Any] = Nil
  )
  case class Response(
      result: org.json4s.JsonAST.JValue,
      error: Option[Error],
      id: String
  )
  case class Error(code: Int, message: String)
}

class ElectrumClient(
    pool: ElectrumClientPool,
    server: ElectrumClientPool.ElectrumServerAddress,
    onReady: ElectrumClient => Unit
) { self =>
  def address = server.address.getHostName()
  System.err.println(s"[info][electrum] connecting to $address")

  var nextRequestId = new AtomicInteger(0)
  var requests =
    scala.collection.mutable.Map.empty[String, (Request, Promise[Response])]

  var ctx: Option[ChannelHandlerContext] = None

  val waitForConnected = Promise[ChannelHandlerContext]()

  // We need to regularly send a ping in order not to get disconnected
  val pingTrigger = {
    val t = new java.util.Timer()
    val task = new java.util.TimerTask {
      def run() = {
        if (ctx.isEmpty)
          pool.killClient(self, "a long time has passed without a connection")
        else {
          val pong = self.request[PingResponse.type](Ping)
          val pongWaiter = new java.util.TimerTask {
            def run(): Unit = {
              if (!pong.isCompleted)
                pool.killClient(self, "taking too long to answer our ping")
            }
          }
          t.schedule(pongWaiter, 14000L)
        }
      }
    }
    t.schedule(task, 16000L, 16000L)
    t
  }

  def shutdown(): Unit = {
    System.err.println(s"[debug][electrum] shutting down $address")
    ctx.foreach(_.close())
    pingTrigger.cancel()
    requests.foreach { case (_, (_, p: Promise[Response])) =>
      if (!p.isCompleted) p.failure(new Exception("client shutdown"))
    }
    requests.clear()
    if (!waitForConnected.isCompleted)
      waitForConnected.failure(
        new Exception("client shutdown before connecting")
      )
  }

  val b = new Bootstrap
  b.channel(classOf[NioSocketChannel])
  b.group(workerGroup)

  b.option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
  b.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
  b.option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
  b.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)

  b.handler(new ChannelInitializer[SocketChannel] {
    override def initChannel(ch: SocketChannel): Unit = {
      if (server.ssl == SSL.LOOSE || server.address.getPort() == 50002) {
        val sslCtx = SslContextBuilder.forClient
          .trustManager(InsecureTrustManagerFactory.INSTANCE)
          .build
        ch.pipeline.addLast(
          sslCtx.newHandler(
            ch.alloc,
            server.address.getHostName(),
            server.address.getPort()
          )
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
  })

  val channelOpenFuture: ChannelFuture =
    b.connect(server.address.getHostName(), server.address.getPort())

  if (LNParams.connectionProvider.proxyAddress.isDefined)
    b.resolver(NoopAddressResolverGroup.INSTANCE)

  channelOpenFuture addListeners new ChannelFutureListener {
    override def operationComplete(future: ChannelFuture): Unit = {
      if (!future.isSuccess) {
        // the connection was not open successfully, close this actor
        pool.killClient(self, s"failed to connect: $future")
      } else {
        // if we are here it means the connection was opened successfully
        // listen for when the connection is closed
        future.channel.closeFuture.addListener(new ChannelFutureListener {
          override def operationComplete(future: ChannelFuture): Unit =
            // this just means it was closed
            pool.killClient(self, s"operation ended: $future")
        })
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
          if (!future.isSuccess())
            pool.killClient(self, s"channel closed: $future")
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
          if (!future.isSuccess)
            pool.killClient(self, s"failed to write: $future")
        }
      }
      ctx.write(msg, promise addListener listener)
    }

    override def exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable
    ): Unit =
      pool.killClient(self, s"exception: $cause")
  }

  class ElectrumResponseDecoder extends MessageToMessageDecoder[String] {
    override def decode(
        ctx: ChannelHandlerContext,
        msg: String,
        out: java.util.List[AnyRef]
    ): Unit = {
      val s = msg.asInstanceOf[String]
      val r = parseResponse(self, s)
      out.add(r)
    }
  }

  class JsonRPCRequestEncoder extends MessageToMessageEncoder[JSONRPC.Request] {
    override def encode(
        ctx: ChannelHandlerContext,
        request: JSONRPC.Request,
        out: java.util.List[AnyRef]
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
    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      request[ServerVersionResponse](
        ServerVersion(CLIENT_NAME, PROTOCOL_VERSION),
        ctx
      )
        .onComplete {
          case Success(v: ServerVersionResponse) =>
            self.ctx = Some(ctx)
            onReady(self)
            if (!waitForConnected.isCompleted) waitForConnected.success(ctx)

            request[HeaderSubscriptionResponse](HeaderSubscription(), ctx)
              .onComplete {
                case Success(resp: HeaderSubscriptionResponse) =>
                case Failure(err) =>
                  System.err.println(
                    s"[warn][electrum] ${address} failed to get a header subscription"
                  )
              }
          case err =>
            pool.killClient(self, s"bad response: $err")
        }
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
      msg match {
        case Right(json: JSONRPC.Response) =>
          requests.get(json.id) match {
            case Some((request, promise)) => {
              val response = parseJsonResponse(self, request, json)

              // always return the response to the caller
              promise.success(response)
              requests.remove(json.id)

              // but also fire events when it is a subscription response
              response match {
                case response: HeaderSubscriptionResponse =>
                  pool.onHeader(response)
                case response: ScriptHashSubscriptionResponse =>
                  pool.onScriptHash(response)
                case _ =>
              }
            }
            case None => {}
          }

        case Left(response: HeaderSubscriptionResponse) =>
          pool.onHeader(response)
        case Left(response: ScriptHashSubscriptionResponse) =>
          pool.onScriptHash(response)
      }
  }

  def request[R <: Response](
      r: Request,
      ctx: ChannelHandlerContext
  ): Future[R] = {
    val promise = Promise[Response]()

    val reqId = nextRequestId.getAndIncrement().toString()
    if (ctx.channel.isWritable) {
      ctx.channel.writeAndFlush(makeRequest(r, reqId))
      requests += (reqId -> ((r, promise)))
    } else {
      pool.killClient(self, "channel not writable")
      promise.failure(
        new Exception(
          s"channel not writable. connection to $address was closed."
        )
      )
    }

    promise.future.map {
      case resp: ServerError =>
        throw new ElectrumServerError(s"$address has sent an error: $resp")
      case any => any.asInstanceOf[R]
    }
  }

  def request[R <: Response](r: Request): Future[R] =
    waitForConnected.future.flatMap { ctx => request(r, ctx) }
}
