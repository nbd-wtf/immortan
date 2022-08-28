package immortan.blockchain.fee

import scala.concurrent.Future
import scoin.FeeratesPerKB

trait FeeProvider {
  def getFeerates: Future[FeeratesPerKB]
}

case object CannotRetrieveFeerates
    extends RuntimeException(
      "cannot retrieve feerates: channels may be at risk"
    )
