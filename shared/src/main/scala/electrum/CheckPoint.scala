package immortan.electrum

import scala.math.BigInt
import scoin.{Block, ByteVector32}

import immortan.electrum.db.HeaderDb

case class CheckPoint(hash: ByteVector32, nextBits: Long)

trait CheckPointLoader {
  def loadFromChainHash(chainHash: ByteVector32): Vector[CheckPoint]
}

object CheckPoint extends CheckPointPlatform {
  import Blockchain.RETARGETING_PERIOD

  def load(chainHash: ByteVector32, headerDb: HeaderDb): Vector[CheckPoint] = {
    val checkpoints = loadFromChainHash(chainHash)
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
