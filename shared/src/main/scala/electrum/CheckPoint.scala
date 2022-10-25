package immortan.electrum

import java.io.InputStream
import scala.math.BigInt
import io.circe._
import io.circe.parser.decode
import scoin.{Block, ByteVector32, encodeCompact}

import immortan.electrum.db.HeaderDb

case class CheckPoint(hash: ByteVector32, nextBits: Long)

object CheckPoint {
  import Blockchain.RETARGETING_PERIOD

  var loadFromChainHash: ByteVector32 => Vector[CheckPoint] = {
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
    case _ => throw new RuntimeException("missing checkpoints for given chain")
  }

  def load(stream: InputStream): Vector[CheckPoint] = {
    val inputBytes = Array.ofDim[Byte](20000)
    stream.read(inputBytes)
    val input = new String(inputBytes, "UTF-8")

    decode[List[(String, BigInt)]](input).toTry.get.toSet.map { (hash, bits) =>
      CheckPoint(
        ByteVector32.fromValidHex(hash).reverse,
        encodeCompact(bits.bigInteger)
      )
    }.toVector
  }

  def load(chainHash: ByteVector32, headerDb: HeaderDb): Vector[CheckPoint] = {
    val checkpoints = CheckPoint.loadFromChainHash(chainHash)
    val checkpoints1 = headerDb.getTip match {
      case Some((height, _)) =>
        val newcheckpoints = for {
          h <-
            checkpoints.size * RETARGETING_PERIOD - 1 + RETARGETING_PERIOD to height - RETARGETING_PERIOD by RETARGETING_PERIOD
        } yield {
          // we * should * have these headers in our db
          val cpheader = headerDb.getHeader(h).get
          val nextDiff = headerDb.getHeader(h + 1).get.bits
          CheckPoint(cpheader.hash, nextDiff)
        }
        checkpoints ++ newcheckpoints
      case None => checkpoints
    }
    checkpoints1
  }
}
