package immortan

import fr.acinq.eclair.wire._
import fr.acinq.eclair.{
  MilliSatoshi,
  MilliSatoshiLong,
  randomBytes,
  randomBytes32
}
import immortan.fsm.TrampolineBroadcaster
import immortan.fsm.TrampolineBroadcaster.RoutingOn
import immortan.utils.ChannelUtils.{makeChannelMaster, makeHostedCommits}
import immortan.utils.GraphUtils._
import immortan.utils.TestUtils.WAIT_UNTIL_TRUE
import utest._

import scala.collection.mutable

object TrampolineBroadcasterSpec extends TestSuite {
  val tests = Tests {
    test("Broadcast routable amounts to peers") {
      LNParams.secret = WalletSecret(
        LightningNodeKeys.makeFromSeed(randomBytes(32).toArray),
        mnemonic = Nil,
        seed = randomBytes32
      )
      LNParams.trampoline = TrampolineOn(
        LNParams.minPayment,
        Long.MaxValue.msat,
        feeProportionalMillionths = 1000L,
        exponent = 0.0,
        logExponent = 0.0,
        LNParams.minRoutingCltvExpiryDelta
      )
      val (_, _, cm) = makeChannelMaster(Seq(randomBytes32))
      val hcs1 =
        makeHostedCommits(
          nodeId = a,
          alias = "peer1",
          toLocal = 100000000L.msat
        )
      val hcs2 =
        makeHostedCommits(nodeId = c, alias = "peer2", toLocal = 50000000L.msat)
      cm.chanBag.put(hcs1)
      cm.chanBag.put(hcs2)
      cm.all = Channel.load(Set(cm), cm.chanBag)

      val sendings = mutable.Buffer.empty[TrampolineStatus]

      val broadcaster = new TrampolineBroadcaster(cm) {
        override def doBroadcast(
            msg: Option[TrampolineStatus],
            info: RemoteNodeInfo
        ): Unit = sendings += msg.get
      }

      broadcaster process RoutingOn(LNParams.trampoline)
      WAIT_UNTIL_TRUE(
        broadcaster.state.isInstanceOf[TrampolineBroadcaster.RoutingEnabled]
      )

      // Channel #1 becomes online
      cm.all.values
        .find(
          _.data
            .asInstanceOf[HostedCommits]
            .remoteInfo
            .nodeId == hcs1.remoteInfo.nodeId
        )
        .foreach(chan => chan.BECOME(chan.data, Channel.Open()))
      broadcaster process TrampolineBroadcaster.LastBroadcast(
        TrampolineUndesired,
        hcs1.remoteInfo,
        1d
      )
      broadcaster process TrampolineBroadcaster.CMDBroadcast
      synchronized(wait(500L))
      assert(sendings.isEmpty)
      sendings.clear()

      // Channel #2 becomes online
      cm.all.values
        .find(
          _.data
            .asInstanceOf[HostedCommits]
            .remoteInfo
            .nodeId == hcs2.remoteInfo.nodeId
        )
        .foreach(chan => chan.BECOME(chan.data, Channel.Open()))
      broadcaster process TrampolineBroadcaster.LastBroadcast(
        TrampolineUndesired,
        hcs2.remoteInfo,
        1d
      )
      broadcaster process TrampolineBroadcaster.CMDBroadcast
      WAIT_UNTIL_TRUE(sendings.size == 2)
      val TrampolineStatusInit(
        Nil,
        TrampolineOn(_, MilliSatoshi(50000000), 1000, 0d, 0d, _)
      ) :: TrampolineStatusInit(
        Nil,
        TrampolineOn(_, MilliSatoshi(100000000), 1000, 0d, 0d, _)
      ) :: Nil = sendings.toList
      sendings.clear()

      // User has changed settings
      broadcaster process RoutingOn(LNParams.trampoline.copy(exponent = 10))
      broadcaster process TrampolineBroadcaster.CMDBroadcast
      WAIT_UNTIL_TRUE(sendings.size == 2)
      val TrampolineStatusUpdate(
        Nil,
        _,
        Some(TrampolineOn(_, MilliSatoshi(50000000), _, 10d, 0d, _)),
        _
      ) :: TrampolineStatusUpdate(
        Nil,
        _,
        Some(TrampolineOn(_, MilliSatoshi(100000000), _, 10d, 0d, _)),
        _
      ) :: Nil = sendings.toList
      sendings.clear()

      // No change in channels params
      broadcaster process TrampolineBroadcaster.CMDBroadcast
      synchronized(wait(500L))
      assert(sendings.isEmpty)

      // User does not want to route
      broadcaster process TrampolineBroadcaster.RoutingOff
      broadcaster process TrampolineBroadcaster.CMDBroadcast
      WAIT_UNTIL_TRUE(sendings.size == 2)
      val TrampolineUndesired :: TrampolineUndesired :: Nil = sendings.toList
      sendings.clear()

      // User wants to route again
      broadcaster process RoutingOn(LNParams.trampoline)
      broadcaster process TrampolineBroadcaster.CMDBroadcast
      WAIT_UNTIL_TRUE(sendings.size == 2)
      val TrampolineStatusInit(
        Nil,
        TrampolineOn(_, MilliSatoshi(50000000), 1000, 0d, 0d, _)
      ) :: TrampolineStatusInit(
        Nil,
        TrampolineOn(_, MilliSatoshi(100000000), 1000, 0d, 0d, _)
      ) :: Nil = sendings.toList
      sendings.clear()

      // Channel #2 got disconnected so routing is not possible at all
      cm.all.values
        .find(
          _.data
            .asInstanceOf[HostedCommits]
            .remoteInfo
            .nodeId == hcs2.remoteInfo.nodeId
        )
        .foreach(chan => chan.BECOME(chan.data, Channel.Sleeping()))
      broadcaster.broadcasters -= hcs2.remoteInfo.nodeId
      broadcaster process TrampolineBroadcaster.CMDBroadcast
      WAIT_UNTIL_TRUE(sendings.size == 1)
      val TrampolineUndesired :: Nil = sendings.toList
      sendings.clear()

      // Channel #2 is online again
      cm.all.values
        .find(
          _.data
            .asInstanceOf[HostedCommits]
            .remoteInfo
            .nodeId == hcs2.remoteInfo.nodeId
        )
        .foreach(chan => chan.BECOME(chan.data, Channel.Open()))
      broadcaster process TrampolineBroadcaster.LastBroadcast(
        TrampolineUndesired,
        hcs2.remoteInfo,
        1d
      )
      broadcaster process TrampolineBroadcaster.CMDBroadcast
      WAIT_UNTIL_TRUE(sendings.size == 2)
      val TrampolineStatusInit(
        Nil,
        TrampolineOn(_, MilliSatoshi(50000000), 1000, 0d, 0d, _)
      ) :: TrampolineStatusInit(
        Nil,
        TrampolineOn(_, MilliSatoshi(100000000), 1000, 0d, 0d, _)
      ) :: Nil = sendings.toList
    }

    test("Correctly merge incoming states") {
      val states0 = TrampolineRoutingStates(Map.empty)

      // We are getting an initial update from node S
      val on1 = TrampolineOn(
        LNParams.minPayment,
        Long.MaxValue.msat,
        feeProportionalMillionths = 1000L,
        exponent = 0.0,
        logExponent = 0.0,
        LNParams.minRoutingCltvExpiryDelta
      )
      val on2 = TrampolineOn(
        LNParams.minPayment,
        Long.MaxValue.msat,
        feeProportionalMillionths = 2000L,
        exponent = 1.0,
        logExponent = 1.0,
        LNParams.minRoutingCltvExpiryDelta
      )
      val update1 = TrampolineStatusInit(
        List(
          List(NodeIdTrampolineParams(a, on2)),
          List(NodeIdTrampolineParams(b, on2))
        ),
        on1
      )

      // Initial update
      val states1 = states0.init(s, update1)
      assert(
        states1.states(s).completeRoutes.map(_.map(_.nodeId)) == Set(
          s :: a :: Nil,
          s :: b :: Nil
        )
      )

      // Applying same update twice changes nothing
      val statesDouble = states1.init(s, update1)
      assert(
        statesDouble.states(s).completeRoutes.map(_.map(_.nodeId)) == Set(
          s :: a :: Nil,
          s :: b :: Nil
        )
      )

      // A has changed parameters, B got removed, new route added
      val on3 = TrampolineOn(
        LNParams.minPayment,
        Long.MaxValue.msat,
        feeProportionalMillionths = 2000L,
        exponent = 2.0,
        logExponent = 2.0,
        LNParams.minRoutingCltvExpiryDelta
      )
      val update2 = TrampolineStatusUpdate(
        List(
          List(NodeIdTrampolineParams(c, on2), NodeIdTrampolineParams(d, on2))
        ),
        Map(a -> on3),
        updatedPeerParams = None,
        removed = Set(b)
      )
      val states2 = states1.merge(s, update2)
      assert(
        states2.states(s).completeRoutes.map(_.map(_.nodeId)) == Set(
          s :: a :: Nil,
          s :: c :: d :: Nil
        )
      )
      assert(
        states2.states(s).completeRoutes.map(_.map(_.trampolineOn)) == Set(
          on1 :: on3 :: Nil,
          on1 :: on2 :: on2 :: Nil
        )
      )

      // Update from another node
      val on4 = TrampolineOn(
        LNParams.minPayment,
        Long.MaxValue.msat,
        feeProportionalMillionths = 4000L,
        exponent = 2.0,
        logExponent = 2.0,
        LNParams.minRoutingCltvExpiryDelta
      )
      val update3 = TrampolineStatusInit(
        List(
          List(NodeIdTrampolineParams(a, on2)),
          List(NodeIdTrampolineParams(b, on2), NodeIdTrampolineParams(d, on3))
        ),
        on4
      )

      val states3 = states2.init(c, update3)
      assert(states3.states(s) == states2.states(s))
      assert(
        states3.states(c).completeRoutes.map(_.map(_.nodeId)) == Set(
          c :: a :: Nil,
          c :: b :: d :: Nil
        )
      )
      assert(
        states3.states(c).completeRoutes.map(_.map(_.trampolineOn)) == Set(
          on4 :: on2 :: Nil,
          on4 :: on2 :: on3 :: Nil
        )
      )

      // Node C removes D from its path and updates its params, but S -> A path remains
      val update4 = TrampolineStatusUpdate(
        Nil,
        Map.empty,
        updatedPeerParams = Some(on3),
        removed = Set(d)
      )
      val states4 = states3.merge(c, update4)
      assert(states4.states(s) == states2.states(s))
      assert(
        states4.states(c).completeRoutes.map(_.map(_.nodeId)) == Set(
          c :: a :: Nil
        )
      )
      assert(
        states4.states(c).completeRoutes.map(_.map(_.trampolineOn)) == Set(
          on3 :: on2 :: Nil
        )
      )

      val update5 = TrampolineStatusUpdate(
        List(
          List(
            NodeIdTrampolineParams(b, on2),
            NodeIdTrampolineParams(d, on3),
            NodeIdTrampolineParams(e, on3)
          )
        ),
        Map.empty,
        updatedPeerParams = None,
        removed = Set.empty
      )
      val states5 = states4.merge(c, update5)
      assert(states5.states(c) == states4.states(c))
    }
  }
}
