package immortan

import immortan.utils._
import scala.concurrent.duration._
import akka.actor.{ActorSystem, Props}
import fr.acinq.bitcoin.{Block, ByteVector32}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import immortan.utils.WalletSecondarySource.{TickPerformSecondarySourceCheck, TipObtained}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.Outcome

// This test makes network calls

class WalletSecondarySourceSpec extends TestKit(ActorSystem("test")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(chainHash: ByteVector32)

  override protected def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(FixtureParam(Block.LivenetGenesisBlock.hash)))

  test("Get chain tip from bitcoinj and api") { f =>
    val bitcoinj = WalletSecondarySource.bitcoinj(f.chainHash).toBlocking.single
    val api = WalletSecondarySource.api.toBlocking.single
    assert(bitcoinj === api)
  }

  test("Tip confirmed, Electrum finishes first") { f =>
    val probe = TestProbe.apply
    val blockCountIsTrusted = new AtomicReference[Boolean](false)
    val blockCount = new AtomicLong(WalletSecondarySource.bitcoinj(f.chainHash).toBlocking.single.height)
    val wallet = ChainWallet(wallet = null, eventsCatcher = TestProbe.apply.ref, clientPool = TestProbe.apply.ref, watcher = TestProbe.apply.ref)
    val actor: TestFSMRef[SecondaryChainState, SecondaryChainData, WalletSecondarySource] = TestFSMRef(new WalletSecondarySource(f.chainHash, blockCount, blockCountIsTrusted, wallet))
    system.eventStream.subscribe(channel = classOf[SecondaryChainEvent], subscriber = probe.ref)

    // Initial check passes
    actor ! TickPerformSecondarySourceCheck
    actor ! ElectrumClient.ElectrumReady(blockCount.get.toInt, tip = null, serverAddress = null)
    awaitCond(actor.stateName === WAITING, 15.seconds)
    probe.expectMsgType[BlockCountIsTrusted.type]
    assert(blockCountIsTrusted.get)

    // Next check passes eventually
    actor ! TickPerformSecondarySourceCheck
    // Simulate bitcoinj timeout to switch actor to API check
    actor.cancelTimer(WalletSecondarySource.firstCheckTimerKey)
    actor ! WalletSecondarySource.FirstCheckTimeout(None)
    probe.expectMsgType[BlockCountIsTrusted.type](15.seconds)
    assert(blockCountIsTrusted.get)
  }

  test("Tip confimed, bitcoinj finishes first") { f =>
    val probe = TestProbe.apply
    val blockCountIsTrusted = new AtomicReference[Boolean](false)
    val blockCount = new AtomicLong(WalletSecondarySource.bitcoinj(f.chainHash).toBlocking.single.height)
    val wallet = ChainWallet(wallet = null, eventsCatcher = TestProbe.apply.ref, clientPool = TestProbe.apply.ref, watcher = TestProbe.apply.ref)
    val actor: TestFSMRef[SecondaryChainState, SecondaryChainData, WalletSecondarySource] = TestFSMRef(new WalletSecondarySource(f.chainHash, blockCount, blockCountIsTrusted, wallet))
    system.eventStream.subscribe(channel = classOf[SecondaryChainEvent], subscriber = probe.ref)

    actor ! TickPerformSecondarySourceCheck
    // Simulate bitcoinj succeessfully finishing first
    actor.cancelTimer(WalletSecondarySource.firstCheckTimerKey)
    // Bitcoinj returns something strange, move to next check phase
    actor ! TipObtained(blockCount.get.toInt + 100)
    actor ! ElectrumClient.ElectrumReady(blockCount.get.toInt, tip = null, serverAddress = null)
    awaitCond(actor.stateName === SECOND)
    // API returns good chain height which matches Electrum
    awaitCond(actor.stateName === WAITING, 15.seconds)
    probe.expectMsgType[BlockCountIsTrusted.type]
    assert(blockCountIsTrusted.get)

    // Next check does not pass
    blockCount.set(blockCount.get - 7)
    actor ! TickPerformSecondarySourceCheck
    probe.expectMsgType[PossibleDangerousBlockSkew.type](15.seconds)
    awaitCond(actor.stateName === FIRST)
    assert(!blockCountIsTrusted.get)
    // Connected to different nodes
    actor ! ElectrumClient.ElectrumReady(blockCount.get.toInt + 7, tip = null, serverAddress = null)
    awaitCond(actor.stateName === WAITING, 15.seconds)
    probe.expectMsgType[BlockCountIsTrusted.type]
    assert(blockCountIsTrusted.get)
  }

  test("Terminal event on many subsequent reconnect fails") { f =>
    val probe = TestProbe.apply
    val blockCountIsTrusted = new AtomicReference[Boolean](false)
    val blockCount = new AtomicLong(WalletSecondarySource.bitcoinj(f.chainHash).toBlocking.single.height)
    val wallet = ChainWallet(wallet = null, eventsCatcher = TestProbe.apply.ref, clientPool = TestProbe.apply.ref, watcher = TestProbe.apply.ref)
    val actor: TestFSMRef[SecondaryChainState, SecondaryChainData, WalletSecondarySource] = TestFSMRef(new WalletSecondarySource(f.chainHash, blockCount, blockCountIsTrusted, wallet))
    system.eventStream.subscribe(channel = classOf[SecondaryChainEvent], subscriber = probe.ref)
    system.actorOf(Props[BlockSkewCatcher]) // Catcher is on

    val skewed = ElectrumClient.ElectrumReady(blockCount.get.toInt - 10, tip = null, serverAddress = null)
    actor ! TickPerformSecondarySourceCheck
    actor ! skewed
    awaitCond(actor.stateName === SECOND, 25.seconds)
    probe.expectMsgType[PossibleDangerousBlockSkew.type]
    awaitCond(actor.stateName === FIRST)
    actor ! skewed
    awaitCond(actor.stateName === SECOND, 25.seconds)
    probe.expectMsgType[PossibleDangerousBlockSkew.type]
    awaitCond(actor.stateName === FIRST)
    actor ! skewed
    awaitCond(actor.stateName === SECOND, 25.seconds)
    probe.expectMsgType[PossibleDangerousBlockSkew.type]

    probe.expectMsgType[DefiniteDangerousBlockSkew.type]
    assert(!blockCountIsTrusted.get)

    awaitCond(actor.stateName === FIRST)
    actor ! ElectrumClient.ElectrumReady(blockCount.get.toInt, tip = null, serverAddress = null)
    awaitCond(actor.stateName === WAITING, 15.seconds)
    probe.expectMsgType[BlockCountIsTrusted.type]
    assert(blockCountIsTrusted.get)
  }
}
