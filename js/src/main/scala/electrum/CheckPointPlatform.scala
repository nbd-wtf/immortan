package immortan.electrum

import java.io.InputStream
import io.circe._
import io.circe.scalajs._
import scoin.{Block, ByteVector32, encodeCompact}

trait CheckPointPlatform extends CheckPointLoader {
  def loadFromChainHash(chainHash: ByteVector32): Vector[CheckPoint] =
    // use io.circe.scalajs converts along with require('checkpoints.json')

    chainHash match {
      case Block.LivenetGenesisBlock.hash => Vector.empty[CheckPoint]
      case Block.TestnetGenesisBlock.hash => Vector.empty[CheckPoint]
      case Block.RegtestGenesisBlock.hash => Vector.empty[CheckPoint]
      case Block.SignetGenesisBlock.hash  => Vector.empty[CheckPoint]
      case _ =>
        throw new RuntimeException("missing checkpoints for given chain")
    }
}
