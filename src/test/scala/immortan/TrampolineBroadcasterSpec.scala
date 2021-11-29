package immortan

import fr.acinq.eclair.wire.{TrampolineOn, TrampolineStatus}
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, randomBytes, randomBytes32}
import immortan.fsm.TrampolineBroadcaster
import immortan.fsm.TrampolineBroadcaster.RoutingOn
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

    val sendings = mutable.Buffer.empty[TrampolineStatus]

    val broadcaster = new TrampolineBroadcaster(cm) {
      override def doBroadcast(msg: Option[TrampolineStatus], info: RemoteNodeInfo): Unit = sendings += msg.get
    }

    broadcaster process RoutingOn(LNParams.trampoline)
    WAIT_UNTIL_TRUE(broadcaster.state == TrampolineBroadcaster.ROUTING_ENABLED)

    // Channel #1 becomes online
    cm.all.values.find(_.data.asInstanceOf[HostedCommits].remoteInfo.nodeId == hcs1.remoteInfo.nodeId).foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    broadcaster process TrampolineBroadcaster.LastBroadcast(TrampolineStatus.empty, hcs1.remoteInfo, 1D)
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    synchronized(wait(100L))
    WAIT_UNTIL_TRUE(sendings.isEmpty)
    sendings.clear

    // Channel #2 becomes online
    cm.all.values.find(_.data.asInstanceOf[HostedCommits].remoteInfo.nodeId == hcs2.remoteInfo.nodeId).foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    broadcaster process TrampolineBroadcaster.LastBroadcast(TrampolineStatus.empty, hcs2.remoteInfo, 1D)
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val TrampolineOn(_, MilliSatoshi(50000000), 1000, 0D, 0D, _) :: TrampolineOn(_, MilliSatoshi(100000000), 1000, 0D, 0D, _) :: Nil = sendings.toList.map(_.params.head.trampolineOn)
    sendings.clear

    // User has changed settings
    broadcaster process RoutingOn(LNParams.trampoline.copy(exponent = 10))
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val TrampolineOn(_, MilliSatoshi(50000000), 1000, 10D, 0D, _) :: TrampolineOn(_, MilliSatoshi(100000000), 1000, 10D, 0D, _) :: Nil = sendings.toList.map(_.params.head.trampolineOn)
    sendings.clear

    // No change in channels params
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    synchronized(wait(100L))
    assert(sendings.isEmpty)

    // User does not want to route
    broadcaster process TrampolineBroadcaster.RoutingOff
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val TrampolineStatus.empty :: TrampolineStatus.empty :: Nil = sendings.toList
    sendings.clear

    // User wants to route again
    broadcaster process RoutingOn(LNParams.trampoline)
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val TrampolineOn(_, MilliSatoshi(50000000), 1000, 0D, 0D, _) :: TrampolineOn(_, MilliSatoshi(100000000), 1000, 0D, 0D, _) :: Nil = sendings.toList.map(_.params.head.trampolineOn)
    sendings.clear

    // Channel #2 got disconnected so routing is not possible at all
    cm.all.values.find(_.data.asInstanceOf[HostedCommits].remoteInfo.nodeId == hcs2.remoteInfo.nodeId).foreach(chan => chan.BECOME(chan.data, Channel.SLEEPING))
    broadcaster.broadcasters -= hcs2.remoteInfo.nodeId
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 1)
    val TrampolineStatus.empty :: Nil = sendings.toList
    sendings.clear

    // Channel #2 is online again
    cm.all.values.find(_.data.asInstanceOf[HostedCommits].remoteInfo.nodeId == hcs2.remoteInfo.nodeId).foreach(chan => chan.BECOME(chan.data, Channel.OPEN))
    broadcaster process TrampolineBroadcaster.LastBroadcast(TrampolineStatus.empty, hcs2.remoteInfo, 1D)
    broadcaster process TrampolineBroadcaster.CMDBroadcast
    WAIT_UNTIL_TRUE(sendings.size == 2)
    val TrampolineOn(_, MilliSatoshi(50000000), 1000, 0D, 0D, _) :: TrampolineOn(_, MilliSatoshi(100000000), 1000, 0D, 0D, _) :: Nil = sendings.toList.map(_.params.head.trampolineOn)
    sendings.clear
  }
}
