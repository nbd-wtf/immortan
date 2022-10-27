package immortan.electrum

import java.io.InputStream
import io.circe._
import io.circe.scalajs._
import scoin.{Block, ByteVector32}

trait ServerLoaderPlatform extends ServerLoader {
  def loadFromChainHash(chainHash: ByteVector32): Set[ElectrumServerAddress] =
    // use io.circe.scalajs converts along with require('checkpoints.json')

    chainHash match {
      case Block.LivenetGenesisBlock.hash => Set.empty[ElectrumServerAddress]
      case Block.TestnetGenesisBlock.hash => Set.empty[ElectrumServerAddress]
      case Block.RegtestGenesisBlock.hash => Set.empty[ElectrumServerAddress]
      case Block.SignetGenesisBlock.hash  => Set.empty[ElectrumServerAddress]
      case _ =>
        throw new RuntimeException("missing servers for given chain")
    }
}
