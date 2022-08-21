package immortan

import scoin.ln._
import utest._

object TrampolineFeerateSpec extends TestSuite {
  val tests = Tests {
    test("Non-linear fees for trampoline routing") {
      {
        val payment = MilliSatoshi(20000000L)
        val linear = proportionalFee(payment, proportionalFee = 1000)
        val trampolineModerateDiscount =
          trampolineFee(linear.toLong, exponent = 0.82d, logExponent = 2.2d)
        val trampolineLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.79d, logExponent = 2.1d)
        val trampolineExtraLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.76d, logExponent = 2.0d)

        assert(linear == MilliSatoshi(20000L))
        assert(trampolineModerateDiscount == MilliSatoshi(3520))
        assert(trampolineLargeDiscount == MilliSatoshi(2623))
        assert(trampolineExtraLargeDiscount == MilliSatoshi(1955))
      }

      {
        val payment = MilliSatoshi(1000000L)
        val linear = proportionalFee(payment, proportionalFee = 1000)
        val trampolineModerateDiscount =
          trampolineFee(linear.toLong, exponent = 0.82d, logExponent = 2.2d)
        val trampolineLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.79d, logExponent = 2.1d)
        val trampolineExtraLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.76d, logExponent = 2.0d)

        assert(linear == MilliSatoshi(1000L))
        assert(trampolineModerateDiscount == MilliSatoshi(359))
        assert(trampolineLargeDiscount == MilliSatoshi(293))
        assert(trampolineExtraLargeDiscount == MilliSatoshi(239))
      }

      {
        val payment = MilliSatoshi(10000000L)
        val linear = proportionalFee(payment, proportionalFee = 500)
        val trampolineModerateDiscount =
          trampolineFee(linear.toLong, exponent = 0.82d, logExponent = 2.2d)
        val trampolineLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.79d, logExponent = 2.1d)
        val trampolineExtraLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.76d, logExponent = 2.0d)

        assert(linear == MilliSatoshi(5000L))
        assert(trampolineModerateDiscount == MilliSatoshi(1191))
        assert(trampolineLargeDiscount == MilliSatoshi(926))
        assert(trampolineExtraLargeDiscount == MilliSatoshi(721))
      }

      {
        val payment = MilliSatoshi(100000000L)
        val linear = proportionalFee(payment, proportionalFee = 500)
        val trampolineModerateDiscount =
          trampolineFee(linear.toLong, exponent = 0.82d, logExponent = 2.2d)
        val trampolineLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.79d, logExponent = 2.1d)
        val trampolineExtraLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.76d, logExponent = 2.0d)

        assert(linear == MilliSatoshi(50000L))
        assert(trampolineModerateDiscount == MilliSatoshi(7320))
        assert(trampolineLargeDiscount == MilliSatoshi(5304))
        assert(trampolineExtraLargeDiscount == MilliSatoshi(3843))
      }

      {
        val payment = MilliSatoshi(1000000000L)
        val linear = proportionalFee(payment, proportionalFee = 500)
        val trampolineModerateDiscount =
          trampolineFee(linear.toLong, exponent = 0.82d, logExponent = 2.2d)
        val trampolineLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.79d, logExponent = 2.1d)
        val trampolineExtraLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.76d, logExponent = 2.0d)

        assert(linear == MilliSatoshi(500000L))
        assert(trampolineModerateDiscount == MilliSatoshi(47403))
        assert(trampolineLargeDiscount == MilliSatoshi(32006))
        assert(trampolineExtraLargeDiscount == MilliSatoshi(21612))
      }

      {
        val payment = MilliSatoshi(10000000000L)
        val linear = proportionalFee(payment, proportionalFee = 500)
        val trampolineModerateDiscount =
          trampolineFee(linear.toLong, exponent = 0.82d, logExponent = 2.2d)
        val trampolineLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.79d, logExponent = 2.1d)
        val trampolineExtraLargeDiscount =
          trampolineFee(linear.toLong, exponent = 0.76d, logExponent = 2.0d)

        assert(linear == MilliSatoshi(5000000L))
        assert(trampolineModerateDiscount == MilliSatoshi(311695))
        assert(trampolineLargeDiscount == MilliSatoshi(196282))
        assert(trampolineExtraLargeDiscount == MilliSatoshi(123611))
      }
    }
  }
}
