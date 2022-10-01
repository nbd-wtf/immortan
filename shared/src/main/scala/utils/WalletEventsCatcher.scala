package immortan.utils

import java.net.InetSocketAddress

import immortan.none
import immortan.electrum.CurrentBlockCount
import immortan.electrum.ElectrumChainSync._
import immortan.electrum.ElectrumClient._
import immortan.electrum.ElectrumWallet._
import immortan.electrum.{
  EventStream,
  ElectrumReady,
  ElectrumDisconnected,
  CurrentBlockCount
}

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
