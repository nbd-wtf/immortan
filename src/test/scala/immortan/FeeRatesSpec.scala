package immortan

import immortan.utils.{BitgoFeeProvider, EarnDotComFeeProvider, EsploraFeeProvider}
import org.scalatest.funsuite.AnyFunSuite


class FeeRatesSpec extends AnyFunSuite {
  test("Provider APIs are correctly parsed") {
    assert(new EsploraFeeProvider("https://blockstream.info/api/fee-estimates").provide.block_1.toLong > 0)
    assert(new EsploraFeeProvider("https://mempool.space/api/fee-estimates").provide.block_1.toLong > 0)
    assert(EarnDotComFeeProvider.provide.block_1.toLong > 0)
    assert(BitgoFeeProvider.provide.block_1.toLong > 0)
  }
}
