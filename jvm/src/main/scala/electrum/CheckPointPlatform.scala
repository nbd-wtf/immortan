package immortan.electrum

import java.io.InputStream
import java.nio.channels.Channels
import io.circe._
import io.circe.jawn._
import scoin.{Block, ByteVector32, encodeCompact}

trait CheckPointPlatform extends CheckPointLoader {
  def loadFromChainHash(chainHash: ByteVector32): Vector[CheckPoint] =
    chainHash match {
      case Block.LivenetGenesisBlock.hash =>
        load(
          classOf[CheckPoint].getResourceAsStream(
            "/electrum/checkpoints_mainnet.json"
          )
        )
      case Block.TestnetGenesisBlock.hash =>
        load(
          classOf[CheckPoint].getResourceAsStream(
            "/electrum/checkpoints_testnet.json"
          )
        )
      case Block.RegtestGenesisBlock.hash => Vector.empty[CheckPoint]
      case Block.SignetGenesisBlock.hash  => Vector.empty[CheckPoint]
      case _ =>
        throw new RuntimeException("missing checkpoints for given chain")
    }

  private def load(stream: InputStream): Vector[CheckPoint] = {
    parseChannel(Channels.newChannel(stream))
      .flatMap(_.as[List[(String, BigInt)]])
      .toTry
      .get
      .map { case (hash, bits) =>
        CheckPoint(
          ByteVector32.fromValidHex(hash).reverse,
          encodeCompact(bits.bigInteger)
        )
      }
      .toVector
  }
}
