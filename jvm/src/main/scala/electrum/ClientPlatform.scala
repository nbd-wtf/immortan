package immortan.electrum

import java.net.{InetSocketAddress, SocketAddress}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._
import scala.util.{Success, Failure}
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
import scodec.bits.ByteVector
import io.circe.syntax._
import scoin._

import immortan.electrum.ElectrumClient._
import immortan.electrum.ElectrumClientPlatform._
import immortan.{LNParams, every, after}
import immortan.LNParams.ec

object ElectrumClientPlatform {
  InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE)

  // this is expensive and shared with all clients
  val workerGroup = new NioEventLoopGroup
}

class ElectrumClientPlatform(
    pool: ElectrumClientPool,
    server: ElectrumClientPool.ElectrumServerAddress,
    onReady: ElectrumClient => Unit
) extends ElectrumClient { self =>
  def address = server.address.getHostName()
  System.err.println(s"[info][electrum] connecting to $address")

  private var nextRequestId = new AtomicInteger(0)
  private var requests =
    scala.collection.mutable.Map.empty[String, (Request, Promise[Response])]

  private var ctx: Option[ChannelHandlerContext] = None
  private val waitForConnected = Promise[ChannelHandlerContext]()

  // We need to regularly send a ping in order not to get disconnected
  private val pingTrigger = {
    every(16.seconds) {
      if (ctx.isEmpty)
        pool.killClient(self, "a long time has passed without a connection")
      else {
        val pong = self.request[PingResponse.type](Ping)
        after(14.seconds) {
          if (!pong.isCompleted)
            pool.killClient(self, "taking too long to answer our ping")
        }
      }
    }
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

  private val b = new Bootstrap
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

  channelOpenFuture.addListeners(new ChannelFutureListener {
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
  })

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
      val r = parseElectrumMessage(self, s)
      out.add(r)
    }
  }

  class JsonRPCRequestEncoder extends MessageToMessageEncoder[JSONRPC.Request] {
    override def encode(
        ctx: ChannelHandlerContext,
        request: JSONRPC.Request,
        out: java.util.List[AnyRef]
    ): Unit = {
      out.add(request.asJson.toString)
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

    override def channelRead(
        ctx: ChannelHandlerContext,
        msg: Any
    ): Unit =
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

  private def request[R <: Response](
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
