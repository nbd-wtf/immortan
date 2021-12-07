package immortan

import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKB, FeeratePerKw, FeeratesPerKB}
import immortan.utils.FeeRates._
import immortan.utils.{BitgoFeeProvider, EsploraFeeProvider}
import org.scalatest.funsuite.AnyFunSuite


class FeeRatesSpec extends AnyFunSuite {
  LNParams.connectionProvider = new ClearnetConnectionProvider

  test("Provider APIs are correctly parsed") {
    assert(new EsploraFeeProvider("https://blockstream.info/api/fee-estimates").provide.block_1.toLong > 0)
    assert(new EsploraFeeProvider("https://mempool.space/api/fee-estimates").provide.block_1.toLong > 0)
    assert(BitgoFeeProvider.provide.block_1.toLong > 0)
  }

  test("Feerates are correctly smoothed") {
    val fr1 = FeeratesPerKB(
      mempoolMinFee = FeeratePerKB(50000.sat),
      block_1 = FeeratePerKB(2100000.sat),
      blocks_2 = FeeratePerKB(1800000.sat),
      blocks_6 = FeeratePerKB(1500000.sat),
      blocks_12 = FeeratePerKB(1100000.sat),
      blocks_36 = FeeratePerKB(500000.sat),
      blocks_72 = FeeratePerKB(200000.sat),
      blocks_144 = FeeratePerKB(150000.sat),
      blocks_1008 = FeeratePerKB(50000.sat)
    )

    val fr2 = FeeratesPerKB(
      mempoolMinFee = FeeratePerKB(500000.sat),
      block_1 = FeeratePerKB(21000000.sat),
      blocks_2 = FeeratePerKB(18000000.sat),
      blocks_6 = FeeratePerKB(15000000.sat),
      blocks_12 = FeeratePerKB(11000000.sat),
      blocks_36 = FeeratePerKB(5000000.sat),
      blocks_72 = FeeratePerKB(2000000.sat),
      blocks_144 = FeeratePerKB(1500000.sat),
      blocks_1008 = FeeratePerKB(500000.sat)
    )

    val history = List(fr1, fr2)
    val smoothed = smoothedFeeratesPerKw(history)
    assert(smoothed.blocks_72 == FeeratePerKw(275000.sat))
  }
}
