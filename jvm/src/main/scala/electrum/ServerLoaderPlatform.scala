package immortan.electrum

import java.io.InputStream
import java.nio.channels.Channels
import java.net.InetSocketAddress
import io.circe._
import io.circe.jawn._
import scoin.{Block, ByteVector32}

import immortan.electrum.ElectrumClient.SSL

trait ServerLoaderPlatform extends ServerLoader {
  def loadFromChainHash(chainHash: ByteVector32): Set[ElectrumServerAddress] =
    readServerAddresses(
      classOf[
        ElectrumServerAddress
      ] getResourceAsStream ("/electrum/servers_" +
        (chainHash match {
          case Block.LivenetGenesisBlock.hash => "mainnet.json"
          case Block.SignetGenesisBlock.hash  => "signet.json"
          case Block.TestnetGenesisBlock.hash => "testnet.json"
          case Block.RegtestGenesisBlock.hash => "regtest.json"
          case _ =>
            throw new RuntimeException(
              "missing electrum servers for given chain"
            )
        }))
    )

  def readServerAddresses(stream: InputStream): Set[ElectrumServerAddress] =
    try {
      parseChannel(Channels.newChannel(stream))
        .flatMap(_.as[Map[String, Json]])
        .toTry
        .get
        .toList
        .collect {
          case (hostname, data) if data.hcursor.get[String]("s").isRight =>
            val port = data.hcursor.get[String]("s").toTry.get.toInt
            val address = InetSocketAddress.createUnresolved(hostname, port)
            ElectrumServerAddress(address, SSL.LOOSE)
        }
        .toSet
    } finally {
      stream.close
    }
}
