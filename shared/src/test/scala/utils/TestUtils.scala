package immortan.utils

import java.net.InetSocketAddress
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import requests.get
import scoin.ln._

import immortan.ConnectionProvider

object TestUtils {
  @tailrec def WAIT_UNTIL_TRUE(condition: => Boolean, left: Int = 100): Unit =
    Try(condition) match {
      case Success(true) =>

      case _ if left >= 0 =>
        TestUtils synchronized wait(50L)
        WAIT_UNTIL_TRUE(condition, left - 1)

      case Failure(exception) =>
        throw exception

      case _ =>
    }

  @tailrec def WAIT_UNTIL_RESULT[T](provider: => T, left: Int = 100): T =
    Try(provider) match {
      case Success(result) => result

      case _ if left >= 0 =>
        TestUtils synchronized wait(50L)
        WAIT_UNTIL_RESULT(provider, left - 1)

      case Failure(exception) =>
        throw exception
    }

  class RequestsConnectionProvider extends ConnectionProvider {
    override val proxyAddress: Option[java.net.InetSocketAddress] = Option.empty
    override def doWhenReady(action: => Unit): Unit = action
    override def getSocket: Socket = new Socket
    override def get(url: String): String = requests.get(url).text()
  }

  class Socket extends immortan.Socket {
    private val s = new java.net.Socket

    def connect(address: NodeAddress, timeout: Int): Unit = {
      val socketAddress = address match {
        case IPv4(ipv4, port) => new InetSocketAddress(ipv4, port)
        case IPv6(ipv6, port) => new InetSocketAddress(ipv6, port)
        case Tor2(tor2, port) => new InetSocketAddress(tor2 + ".onion", port)
        case Tor3(tor3, port) => new InetSocketAddress(tor3 + ".onion", port)
      }
      s.connect(socketAddress, timeout)
    }

    def write(data: Array[Byte]): Unit =
      s.getOutputStream.write(data)

    def read(buffer: Array[Byte], offset: Int, len: Int): Int =
      s.getInputStream.read(buffer, 0, buffer.length)

    def close(): Unit =
      s.close()
  }
}
