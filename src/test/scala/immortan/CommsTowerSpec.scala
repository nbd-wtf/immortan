package immortan

import immortan.crypto.Tools
import org.scalatest.funsuite.AnyFunSuite
import fr.acinq.eclair.wire.{Init, LightningMessage, Pong}


class CommsTowerSpec extends AnyFunSuite {
  test("Successfully connect, send Ping, get Pong") {
    var responses = List.empty[LightningMessage]

    val listener = new ConnectionListener {
      override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = worker.sendPing
      override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = responses ::= msg
    }

    val remoteInfo = (new SyncParams).acinq
    val kpap = KeyPairAndPubKey(Tools.randomKeyPair, remoteInfo.nodeId)
    CommsTower.listen(Set(listener), kpap, remoteInfo)

    synchronized(wait(5000L))
    assert(responses.head.isInstanceOf[Pong])
  }
}
