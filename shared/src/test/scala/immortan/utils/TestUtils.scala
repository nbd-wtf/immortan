package immortan.utils

import java.net.{InetSocketAddress, Socket}
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import requests.get
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
    override val proxyAddress: Option[InetSocketAddress] = Option.empty
    override def doWhenReady(action: => Unit): Unit = action
    override def getSocket: Socket = new Socket
    override def get(url: String): String = requests.get(url).text()
  }
}
