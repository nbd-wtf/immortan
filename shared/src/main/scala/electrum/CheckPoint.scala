package immortan.electrum

import java.io.InputStream
import org.json4s.JsonAST.{JArray, JInt, JString}
import org.json4s.StreamInput
import org.json4s.native.JsonMethods
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
    val JArray(values) = JsonMethods.parse(StreamInput(stream)): @unchecked
    val checkpoints = values.collect {
      case JArray(JString(a) :: JInt(b) :: Nil) =>
        CheckPoint(
          ByteVector32.fromValidHex(a).reverse,
          encodeCompact(b.bigInteger)
        )
    }
    checkpoints.toVector
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
