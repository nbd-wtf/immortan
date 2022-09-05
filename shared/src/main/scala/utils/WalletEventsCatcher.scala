package immortan.utils

import java.net.InetSocketAddress

import immortan.none
import immortan.blockchain.CurrentBlockCount
import immortan.electrum.ElectrumChainSync
import immortan.electrum.EventStream
import immortan.electrum.ElectrumClient._
import immortan.electrum.ElectrumWallet._

class WalletEventsCatcher {
  // Not using a set to ensure insertion order
  var listeners: List[WalletEventsListener] = Nil

  def add(listener: WalletEventsListener): Unit =
    listeners = (listeners :+ listener).distinct

  def remove(listener: WalletEventsListener): Unit =
    listeners = listeners diff List(listener)

  EventStream.subscribe {
    case event: WalletReady => for (lst <- listeners) lst.onWalletReady(event)
    case event: TransactionReceived =>
      for (lst <- listeners) lst.onTransactionReceived(event)

    case event: CurrentBlockCount =>
      for (lst <- listeners) lst.onChainTipKnown(event)
    case event: ElectrumReady =>
      for (lst <- listeners) lst.onChainMasterSelected(event.serverAddress)
    case ElectrumDisconnected =>
      for (lst <- listeners) lst.onChainDisconnected()

    case event: ElectrumChainSync.ChainSyncStarted =>
      for (lst <- listeners)
        lst.onChainSyncStarted(event.localTip, event.remoteTip)
    case event: ElectrumChainSync.ChainSyncEnded =>
      for (lst <- listeners) lst.onChainSyncEnded(event.localTip)
  }
}

class WalletEventsListener {
  def onWalletReady(event: WalletReady): Unit = none
  def onTransactionReceived(event: TransactionReceived): Unit = none

  def onChainTipKnown(event: CurrentBlockCount): Unit = none
  def onChainMasterSelected(event: InetSocketAddress): Unit = none
  def onChainDisconnected(): Unit = none

  def onChainSyncStarted(localTip: Long, remoteTip: Long): Unit = none
  def onChainSyncEnded(localTip: Long): Unit = none
}
