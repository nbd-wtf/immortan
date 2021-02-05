package immortan.utils

import immortan.crypto.Tools._
import scala.concurrent.duration._
import immortan.utils.ImplicitJsonFormats._
import immortan.utils.WalletSecondarySource._
import com.github.kevinsawicki.http.HttpRequest._

import akka.actor.{Actor, FSM}
import org.bitcoinj.core.{Peer, PeerGroup}
import rx.lang.scala.{Observable, Subscription}
import fr.acinq.bitcoin.{Block, ByteVector32, Transaction}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.ElectrumReady
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool
import org.bitcoinj.core.listeners.PeerConnectedEventListener
import org.bitcoinj.net.discovery.MultiplexingDiscovery
import org.bitcoinj.params.AbstractBitcoinNetParams
import immortan.crypto.Tools
import immortan.ChainWallet


object WalletSecondarySource {
  final val firstCheckTimerKey = "first-check-timeout-key"

  case class FirstCheckTimeout(sub: Option[Subscription] = None)

  case class TipObtained(height: Int)

  case object TickPerformSecondarySourceCheck

  case object SecondCheckFailure

  abstract class Bitcoinj(chainHash: ByteVector32) {
    def onConnected(count: Int): Unit

    val params: AbstractBitcoinNetParams = chainHash match {
      case Block.LivenetGenesisBlock.hash => org.bitcoinj.params.MainNetParams.get
      case Block.TestnetGenesisBlock.hash => org.bitcoinj.params.TestNet3Params.get
      case Block.RegtestGenesisBlock.hash => org.bitcoinj.params.RegTestParams.get
      case _ => throw new RuntimeException("Unknown chain hash")
    }

    val maxPeers = 3
    val peerGroup: PeerGroup = new PeerGroup(params)
    val discovery: MultiplexingDiscovery = MultiplexingDiscovery.forServices(params, 0)

    private[this] val peersListener = new PeerConnectedEventListener {
      def onPeerConnected(peer: Peer, count: Int): Unit = onConnected(count)
    }

    peerGroup.addConnectedEventListener(peersListener)
    peerGroup.setDownloadTxDependencies(0)
    peerGroup.setMaxConnections(maxPeers)
    peerGroup.addPeerDiscovery(discovery)
    peerGroup.startAsync
  }

  def jTip(chainHash: ByteVector32): Observable[TipObtained] = {
    val asyncSubject = rx.lang.scala.subjects.AsyncSubject[TipObtained]

    new Bitcoinj(chainHash) {
      asyncSubject.doOnUnsubscribe(peerGroup.stopAsync)
      override def onConnected(count: Int): Unit = if (count >= maxPeers) {
        val message = TipObtained(peerGroup.getMostCommonChainHeight)
        asyncSubject.onNext(message)
        asyncSubject.onCompleted
        peerGroup.stopAsync
      }
    }

    asyncSubject
  }

  def jBroadcast(chainHash: ByteVector32, bitcoinLibTx: Transaction): Unit = new Bitcoinj(chainHash) {
    override def onConnected(currentPeerCount: Int): Unit = if (currentPeerCount >= maxPeers) {
      val bitcoinjTx = new org.bitcoinj.core.Transaction(params, bitcoinLibTx.bin.toArray)
      try peerGroup.broadcastTransaction(bitcoinjTx, 1, true).broadcast.get catch none
      peerGroup.stopAsync
    }
  }

  def apiTip: Observable[TipObtained] = {
    val blockChain = Rx.ioQueue.map(_ => to[BlockChainHeight](get("https://blockchain.info/latestblock").connectTimeout(15000).body).height)
    val blockCypher = Rx.ioQueue.map(_ => to[BlockCypherHeight](get("https://api.blockcypher.com/v1/btc/main").connectTimeout(15000).body).height)
    val blockStream = Rx.ioQueue.map(_ => get("https://blockstream.info/api/blocks/tip/height").connectTimeout(15000).body.toInt)
    val mempoolSpace = Rx.ioQueue.map(_ => get("https://mempool.space/api/blocks/tip/height").connectTimeout(15000).body.toInt)
    val calls = List(blockChain, blockCypher, blockStream, mempoolSpace).map(_ onExceptionResumeNext Observable.empty)
    Observable.from(calls).flatten.toList.map(Tools.mostFrequentItem).map(TipObtained.apply)
  }
}

case class BlockCypherHeight(height: Int)
case class BlockChainHeight(height: Int)

sealed trait SecondaryChainEvent
case object PossibleDangerousBlockSkew extends SecondaryChainEvent
case object DefiniteDangerousBlockSkew extends SecondaryChainEvent
case object BlockCountIsTrusted extends SecondaryChainEvent

sealed trait SecondaryChainState
case object FIRST extends SecondaryChainState
case object SECOND extends SecondaryChainState
case object WAITING extends SecondaryChainState

sealed trait SecondaryChainData
case object AwaitingAction extends SecondaryChainData
case class InitialCheck(baseHeight: Option[Long], checkHeight: Option[Long] = None, skipToSecondaryCheck: Boolean = false) extends SecondaryChainData
case class SecondaryAPICheck(baseHeight: Long) extends SecondaryChainData

class WalletSecondarySource(chainHash: ByteVector32,
                            blockCount: AtomicLong, blockCountIsTrusted: AtomicReference[Boolean],
                            wallet: ChainWallet) extends FSM[SecondaryChainState, SecondaryChainData] {

  private[this] val dangerousSkewBlocks = 6

  context.system.eventStream.subscribe(channel = classOf[ElectrumReady], subscriber = self)

  setTimer(TickPerformSecondarySourceCheck.toString, TickPerformSecondarySourceCheck, 6.hours, repeat = true)

  startWith(FIRST, AwaitingAction)

  when(FIRST) {
    case Event(TickPerformSecondarySourceCheck, AwaitingAction) =>
      val sub = jTip(chainHash).subscribe(tipObtained => self ! tipObtained)
      setTimer(firstCheckTimerKey, FirstCheckTimeout(sub.toSome), 15.seconds)
      stay using InitialCheck(baseHeight = None)


    case Event(FirstCheckTimeout(sub), data1: InitialCheck) =>
      // Prevent bitcoinj from firing anyway at later time
      sub.foreach(_.unsubscribe)

      if (data1.baseHeight.isDefined) startAPICheck(data1.baseHeight.get)
      else stay using data1.copy(skipToSecondaryCheck = true)


    case Event(base: ElectrumReady, data1: InitialCheck) =>
      if (data1.skipToSecondaryCheck) startAPICheck(base.height)
      else if (data1.checkHeight.isEmpty) stay using data1.copy(baseHeight = base.height.toLong.toSome)
      else if (data1.checkHeight.get - base.height > dangerousSkewBlocks) startAPICheck(base.height)
      else informTrusted


    case Event(check: TipObtained, data1: InitialCheck) =>
      // Prevent timer from firing anyway at later time
      cancelTimer(firstCheckTimerKey)

      if (data1.baseHeight.isEmpty) stay using data1.copy(checkHeight = check.height.toLong.toSome)
      else if (check.height - data1.baseHeight.get > dangerousSkewBlocks) startAPICheck(data1.baseHeight.get)
      else informTrusted
  }

  when(SECOND) {
    case Event(check: TipObtained, data1: SecondaryAPICheck) =>
      val isDangerousBlockSkewDetected = check.height - data1.baseHeight > dangerousSkewBlocks
      if (isDangerousBlockSkewDetected) restartBase else informTrusted


    case Event(SecondCheckFailure, _: SecondaryAPICheck) =>
      // API check failed, consider block count safe for now
      informTrusted
  }

  when(WAITING) {
    case Event(TickPerformSecondarySourceCheck, AwaitingAction) =>
      val sub = jTip(chainHash).subscribe(tipObtained => self ! tipObtained)
      setTimer(firstCheckTimerKey, FirstCheckTimeout(sub.toSome), 15.seconds)
      stay


    case Event(FirstCheckTimeout(sub), AwaitingAction) =>
      apiTip.foreach(self ! _, _ => self ! SecondCheckFailure)
      // Prevent bitcoinj from firing anyway at later time
      sub.foreach(_.unsubscribe)
      stay


    case Event(check: TipObtained, AwaitingAction) =>
      // Prevent timer from firing anyway at later time
      cancelTimer(firstCheckTimerKey)

      val isDangerousBlockSkewDetected = check.height - blockCount.get > dangerousSkewBlocks
      if (isDangerousBlockSkewDetected) restartBase else informTrusted
  }

  private def startAPICheck(baseHeight: Long) = {
    apiTip.foreach(self ! _, _ => self ! SecondCheckFailure)
    goto(SECOND) using SecondaryAPICheck(baseHeight)
  }

  private def informTrusted = {
    blockCountIsTrusted.set(true)
    context.system.eventStream.publish(BlockCountIsTrusted)
    goto(WAITING) using AwaitingAction
  }

  private def restartBase = {
    blockCountIsTrusted.set(false)
    context.system.eventStream.publish(PossibleDangerousBlockSkew)
    wallet.clientPool ! ElectrumClientPool.Reconnect
    self ! TickPerformSecondarySourceCheck
    goto(FIRST) using AwaitingAction
  }

  initialize
}

class BlockSkewCatcher extends Actor {
  context.system.eventStream.subscribe(channel = classOf[SecondaryChainEvent], subscriber = self)

  private[this] val maxAttempts = 3

  override def receive: Receive = catchEvent(1)

  def catchEvent(timesFailed: Int): Receive = {
    case BlockCountIsTrusted => context become catchEvent(1)

    case PossibleDangerousBlockSkew if timesFailed < maxAttempts => context become catchEvent(timesFailed + 1)

    case PossibleDangerousBlockSkew => context.system.eventStream.publish(DefiniteDangerousBlockSkew)
  }
}