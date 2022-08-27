package immortan

import java.net.InetSocketAddress
import scoin.ln.NodeAddress

trait Socket {
  def connect(address: NodeAddress, timeout: Int): Unit
  def write(data: Array[Byte]): Unit
  def read(buffer: Array[Byte], offset: Int, len: Int): Int
  def close(): Unit
}

trait ConnectionProvider {
  val proxyAddress: Option[InetSocketAddress]
  def getSocket: Socket
  def doWhenReady(action: => Unit): Unit
  def get(url: String): String
  def notifyAppAvailable(): Unit = {}
}
