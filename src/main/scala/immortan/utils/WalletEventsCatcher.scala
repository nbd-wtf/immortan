package immortan.utils

import java.net.InetSocketAddress

import fr.acinq.eclair.blockchain.electrum.ElectrumChainSync._
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._
import fr.acinq.eclair.blockchain.electrum.{
  EventStream,
  ElectrumReady,
  ElectrumDisconnected,
  CurrentBlockCount
}
import immortan.crypto.Tools.none

class WalletEventsCatcher {
  // Not using a set to ensure insertion order
  var listeners: List[WalletEventsListener] = Nil

  def add(listener: WalletEventsListener): Unit =
    listeners = (listeners :+ listener).distinct

  def remove(listener: WalletEventsListener): Unit =
    listeners = listeners diff List(listener)

  EventStream.subscribe {
    case event: WalletReady =>
      listeners.foreach(_.onWalletReady(event))
    case event: TransactionReceived =>
      listeners.foreach(_.onTransactionReceived(event))

    case event: CurrentBlockCount =>
      listeners.foreach(_.onChainTipKnown(event))
    case event: ElectrumReady =>
      listeners.foreach(_.onChainConnected())
    case ElectrumDisconnected =>
      listeners.foreach(_.onChainDisconnected())

    case event: ChainSyncStarted =>
      listeners.foreach(_.onChainSyncStarted(event.localTip, event.remoteTip))
    case event: ChainSyncProgress =>
      listeners.foreach(_.onChainSyncProgress(event.localTip, event.remoteTip))
    case event: ChainSyncEnded =>
      listeners.foreach(_.onChainSyncEnded(event.localTip))

    case WalletSyncStarted =>
      listeners.foreach(_.onWalletSyncStarted())
    case event: WalletSyncProgress =>
      listeners.foreach(_.onWalletSyncProgress(event.max, event.left))
    case WalletSyncEnded =>
      listeners.foreach(_.onWalletSyncEnded())
  }
}

class WalletEventsListener {
  def onWalletReady(event: WalletReady): Unit = none
  def onTransactionReceived(event: TransactionReceived): Unit = none

  def onChainTipKnown(event: CurrentBlockCount): Unit = none
  def onChainConnected(): Unit = none
  def onChainDisconnected(): Unit = none

  def onChainSyncStarted(localTip: Long, remoteTip: Long): Unit = none
  def onChainSyncProgress(localTip: Long, remoteTip: Long): Unit = none
  def onChainSyncEnded(localTip: Long): Unit = none

  def onWalletSyncStarted(): Unit = none
  def onWalletSyncProgress(max: Int, left: Int): Unit = none
  def onWalletSyncEnded(): Unit = none
}
