package immortan

import fr.acinq.eclair.router.Sync
import fr.acinq.eclair.wire.{NodeIdTrampolineParams, TrampolineOn, TrampolineRoutingStates, TrampolineStatus}
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, randomBytes, randomBytes32}
import immortan.fsm.TrampolineBroadcaster
import immortan.fsm.TrampolineBroadcaster.RoutingOn
import immortan.utils.ChannelUtils.{makeChannelMaster, makeHostedCommits}
import immortan.utils.GraphUtils._
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

  test("Correctly merge incoming states") {
    val List(aCrc, bCrc, cCrc, dCrc, eCrc, sCrc) = List(a, b, c, d, e, s).map(Sync crc32c _.value)

    val states0 = TrampolineRoutingStates(Map.empty)

    // We are getting an initial update from node S
    val on1 = TrampolineOn(LNParams.minPayment, Long.MaxValue.msat, feeProportionalMillionths = 1000L, exponent = 0.0, logExponent = 0.0, LNParams.minRoutingCltvExpiryDelta)
    val on2 = TrampolineOn(LNParams.minPayment, Long.MaxValue.msat, feeProportionalMillionths = 2000L, exponent = 1.0, logExponent = 1.0, LNParams.minRoutingCltvExpiryDelta)
    val update1 = TrampolineStatus(NodeIdTrampolineParams(s, on1) :: NodeIdTrampolineParams(a, on1) :: NodeIdTrampolineParams(b, on2) :: Nil, paths = List(aCrc :: Nil, bCrc :: Nil), removed = Nil)

    // Initial update
    val states1 = states0.merge(s, update1)
    assert(states1.states(s).routes == Set(s :: a :: Nil, s :: b :: Nil))
    assert(states1.states(s).crc32Cache == Map(sCrc -> s, aCrc -> a, bCrc -> b))
    assert(states1.states(s).nodeToTrampoline == Map(s -> on1, a -> on1, b -> on2))

    // Applying same update twice changes nothing
    val statesDouble = states1.merge(s, update1)
    assert(statesDouble.states(s).routes == Set(s :: a :: Nil, s :: b :: Nil))
    assert(statesDouble.states(s).crc32Cache == Map(sCrc -> s, aCrc -> a, bCrc -> b))
    assert(statesDouble.states(s).nodeToTrampoline == Map(s -> on1, a -> on1, b -> on2))

    // A has changed parameters, B got removed, paths contains an unknown crc32
    val update2 = TrampolineStatus(NodeIdTrampolineParams(a, on2) :: Nil, paths = List(aCrc :: bCrc :: Nil, dCrc :: Nil), removed = bCrc :: Nil)
    val states2 = states1.merge(s, update2)
    assert(states2.states(s).routes == Set(s :: a :: Nil))
    assert(states2.states(s).crc32Cache == Map(sCrc -> s, aCrc -> a))
    assert(states2.states(s).nodeToTrampoline == Map(s -> on1, a -> on2))

    // Update from another node, C has attached itself to path for no reason, also C has a connection to same node A
    val on3 = TrampolineOn(LNParams.minPayment, Long.MaxValue.msat, feeProportionalMillionths = 4000L, exponent = 2.0, logExponent = 2.0, LNParams.minRoutingCltvExpiryDelta)
    val update3 = TrampolineStatus(NodeIdTrampolineParams(c, on3) :: NodeIdTrampolineParams(e, on3) :: NodeIdTrampolineParams(a, on1) :: Nil, paths = List(eCrc :: Nil, cCrc :: Nil, aCrc :: Nil), removed = Nil)
    val states3 = states2.merge(c, update3)
    assert(states3.states(s) == states2.states(s))
    assert(states3.states(c).routes == Set(c :: e :: Nil, c :: a :: Nil))
    assert(states3.states(c).crc32Cache == Map(aCrc -> a, cCrc -> c, eCrc -> e))
    assert(states3.states(c).nodeToTrampoline == Map(a -> on1, c -> on3, e -> on3))

    // Node C removes A from its path, but S -> A path remains
    val update4 = TrampolineStatus(Nil, paths = Nil, removed = aCrc :: Nil)
    val states4 = states3.merge(c, update4)
    assert(states4.states(s) == states2.states(s))
    assert(states4.states(c).routes == Set(c :: e :: Nil))
    assert(states4.states(c).crc32Cache == Map(cCrc -> c, eCrc -> e))
    assert(states4.states(c).nodeToTrampoline == Map(c -> on3, e -> on3))

    // Node C sends an update containing params without routes
    val update5 = TrampolineStatus(NodeIdTrampolineParams(a, on2) :: Nil, paths = Nil, removed = Nil)
    val states5 = states4.merge(c, update5)
    assert(states5.states(c) == states4.states(c))

    // Node C sends an overly long path (unreliable), so proposed route is not included
    val update6 = TrampolineStatus(NodeIdTrampolineParams(a, on2) :: NodeIdTrampolineParams(d, on2) :: Nil, paths = List(aCrc :: dCrc :: eCrc :: Nil), removed = Nil)
    val states6 = states5.merge(c, update6)
    assert(states6.states(c) == states6.states(c))
  }
}
