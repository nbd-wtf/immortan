package fr.acinq.eclair.blockchain.watchdog

import org.bitcoinj.core.{Peer, PeerGroup}
import fr.acinq.bitcoin.{Block, ByteVector32}
import org.bitcoinj.core.listeners.PeerConnectedEventListener
import org.bitcoinj.net.discovery.MultiplexingDiscovery
import org.bitcoinj.params.AbstractBitcoinNetParams
import immortan.crypto.Tools
import akka.actor.Actor


object BitcoinjTipProvider {
  case class TrustedTipKnown(height: Long)
}

class BitcoinjTipProvider(chainHash: ByteVector32) extends Actor {
  private[this] val params: AbstractBitcoinNetParams = chainHash match {
    case Block.LivenetGenesisBlock.hash => org.bitcoinj.params.MainNetParams.get
    case Block.TestnetGenesisBlock.hash => org.bitcoinj.params.TestNet3Params.get
    case Block.RegtestGenesisBlock.hash => org.bitcoinj.params.RegTestParams.get
    case _ => throw new RuntimeException("Unknown chain hash")
  }

  private[this] val maxPeers = 5
  private[this] val peerGroup = new PeerGroup(params)
  private[this] val peersListener = new PeerConnectedEventListener {
    def onPeerConnected(peer: Peer, count: Int): Unit = if (count >= maxPeers) {
      context.parent ! BitcoinjTipProvider.TrustedTipKnown(peerGroup.getMostCommonChainHeight)
    }
  }

  peerGroup addPeerDiscovery MultiplexingDiscovery.forServices(params, 0)
  peerGroup addConnectedEventListener peersListener
  peerGroup setDownloadTxDependencies 0
  peerGroup setMaxConnections maxPeers
  peerGroup.startAsync

  override def receive: Receive = Tools.none

  override def postStop: Unit = {
    peerGroup.stopAsync
    super.postStop
  }
}
