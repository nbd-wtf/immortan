package immortan

import fr.acinq.eclair.wire.{TrampolineOn, TrampolineStatus, TrampolineUndesired}
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, randomBytes, randomBytes32}
import immortan.fsm.TrampolineBroadcaster
import immortan.utils.ChannelUtils.{makeChannelMaster, makeHostedCommits}
import immortan.utils.GraphUtils.{a, c}
import immortan.utils.TestUtils.WAIT_UNTIL_TRUE
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable


class TrampolineBroadcasterSpec extends AnyFunSuite {
  test("Broadcast routable amounts to peers") {
    LNParams.secret = WalletSecret(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), mnemonic = Nil, seed = randomBytes32)
    LNParams.trampoline = TrampolineOn(LNParams.minPayment, Long.MaxValue.msat, feeProportionalMillionths = 1000L, exponent = 0.0, logExponent = 0.0, LNParams.minRoutingCltvExpiryDelta)
    val (_, _, cm) = makeChannelMaster(Seq(randomBytes32))
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1", toLocal = 100000000L.msat)
    val hcs2 = makeHostedCommits(nodeId = c, alias = "peer2", toLocal = 50000000L.msat)
    cm.chanBag.put(hcs1)
    cm.chanBag.put(hcs2)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val sendings = mutable.Buffer.empty[(TrampolineStatus, String)]

    val broadcaster = new TrampolineBroadcaster(cm) {
      override def doBroadcast(msg: Option[TrampolineStatus], info: RemoteNodeInfo): Unit = sendings += Tuple2(msg.get, info.alias)
    }

    broadcaster process LNParams.trampoline
    WAIT_UNTIL_TRUE(broadcaster.state == TrampolineBroadcaster.ROUTING_ENABLED)

    // Channel #1 becomes online
    cm.all.values.find(_.data.asInstanceOf[HostedCommits].remoteInfo.nodeId == hcs1.remoteInfo.nodeId).foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    broadcaster process TrampolineBroadcaster.LastBroadcast(None, hcs1.remoteInfo, 1D)
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.toList == (TrampolineUndesired, "peer1") :: Nil)
    sendings.clear

    // Channel #2 becomes online
    cm.all.values.find(_.data.asInstanceOf[HostedCommits].remoteInfo.nodeId == hcs2.remoteInfo.nodeId).foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    broadcaster process TrampolineBroadcaster.LastBroadcast(None, hcs2.remoteInfo, 1D)
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val (TrampolineOn(_, MilliSatoshi(50000000), 1000, 0D, 0D, _), "peer1") :: (TrampolineOn(_, MilliSatoshi(100000000), 1000, 0D, 0D, _), "peer2") :: Nil = sendings.toList
    sendings.clear

    // User has changed settings
    broadcaster process LNParams.trampoline.copy(exponent = 10)
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val (TrampolineOn(_, MilliSatoshi(50000000), 1000, 10D, 0D, _), "peer1") :: (TrampolineOn(_, MilliSatoshi(100000000), 1000, 10D, 0D, _), "peer2") :: Nil = sendings.toList
    sendings.clear

    // No change in channels params
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    synchronized(wait(100L))
    assert(sendings.isEmpty)

    // Use does not want to route
    broadcaster process TrampolineUndesired
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val (TrampolineUndesired, "peer1") :: (TrampolineUndesired, "peer2") :: Nil = sendings.toList
    sendings.clear

    // User wants to route again
    broadcaster process LNParams.trampoline
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val (TrampolineOn(_, MilliSatoshi(50000000), 1000, 0D, 0D, _), "peer1") :: (TrampolineOn(_, MilliSatoshi(100000000), 1000, 0D, 0D, _), "peer2") :: Nil = sendings.toList
    sendings.clear

    // Channel #2 got disconnected so routing is not possible at all
    cm.all.values.find(_.data.asInstanceOf[HostedCommits].remoteInfo.nodeId == hcs2.remoteInfo.nodeId).foreach(chan => chan.BECOME(chan.data, Channel.SLEEPING))
    broadcaster.broadcasters -= hcs2.remoteInfo.nodeId
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 1)
    val (TrampolineUndesired, "peer1") :: Nil = sendings.toList
    sendings.clear

    // Channel #2 is online again
    cm.all.values.find(_.data.asInstanceOf[HostedCommits].remoteInfo.nodeId == hcs2.remoteInfo.nodeId).foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    broadcaster process TrampolineBroadcaster.LastBroadcast(None, hcs2.remoteInfo, 1D)
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val (TrampolineOn(_, MilliSatoshi(50000000), 1000, 0D, 0D, _), "peer1") :: (TrampolineOn(_, MilliSatoshi(100000000), 1000, 0D, 0D, _), "peer2") :: Nil = sendings.toList
    sendings.clear
  }
}
