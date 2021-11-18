package immortan

import fr.acinq.eclair._
import org.scalatest.funsuite.AnyFunSuite


class TrampolineFeerateSpec extends AnyFunSuite {
  test("Non-linear fees for trampoline routing") {
    {
      val payment = 20000000L.msat
      val linear = proportionalFee(payment, proportionalFee = 1000)
      val trampolineModerateDiscount = trampolineFee(linear.toLong, exponent = 0.82D, logExponent = 2.2D)
      val trampolineLargeDiscount = trampolineFee(linear.toLong, exponent = 0.79D, logExponent = 2.1D)
      val trampolineExtraLargeDiscount = trampolineFee(linear.toLong, exponent = 0.76D, logExponent = 2.0D)

      assert(linear == 20000L.msat)
      assert(trampolineModerateDiscount == 3520.msat)
      assert(trampolineLargeDiscount == 2623.msat)
      assert(trampolineExtraLargeDiscount == 1955.msat)
    }

    {
      val payment = 1000000L.msat
      val linear = proportionalFee(payment, proportionalFee = 1000)
      val trampolineModerateDiscount = trampolineFee(linear.toLong, exponent = 0.82D, logExponent = 2.2D)
      val trampolineLargeDiscount = trampolineFee(linear.toLong, exponent = 0.79D, logExponent = 2.1D)
      val trampolineExtraLargeDiscount = trampolineFee(linear.toLong, exponent = 0.76D, logExponent = 2.0D)

      assert(linear == 1000L.msat)
      assert(trampolineModerateDiscount == 359.msat)
      assert(trampolineLargeDiscount == 293.msat)
      assert(trampolineExtraLargeDiscount == 239.msat)
    }

    {
      val payment = 10000000L.msat
      val linear = proportionalFee(payment, proportionalFee = 500)
      val trampolineModerateDiscount = trampolineFee(linear.toLong, exponent = 0.82D, logExponent = 2.2D)
      val trampolineLargeDiscount = trampolineFee(linear.toLong, exponent = 0.79D, logExponent = 2.1D)
      val trampolineExtraLargeDiscount = trampolineFee(linear.toLong, exponent = 0.76D, logExponent = 2.0D)

      assert(linear == 5000L.msat)
      assert(trampolineModerateDiscount == 1191.msat)
      assert(trampolineLargeDiscount == 926.msat)
      assert(trampolineExtraLargeDiscount == 721.msat)
    }

    {
      val payment = 100000000L.msat
      val linear = proportionalFee(payment, proportionalFee = 500)
      val trampolineModerateDiscount = trampolineFee(linear.toLong, exponent = 0.82D, logExponent = 2.2D)
      val trampolineLargeDiscount = trampolineFee(linear.toLong, exponent = 0.79D, logExponent = 2.1D)
      val trampolineExtraLargeDiscount = trampolineFee(linear.toLong, exponent = 0.76D, logExponent = 2.0D)

      assert(linear == 50000L.msat)
      assert(trampolineModerateDiscount == 7320.msat)
      assert(trampolineLargeDiscount == 5304.msat)
      assert(trampolineExtraLargeDiscount == 3843.msat)
    }

    {
      val payment = 1000000000L.msat
      val linear = proportionalFee(payment, proportionalFee = 500)
      val trampolineModerateDiscount = trampolineFee(linear.toLong, exponent = 0.82D, logExponent = 2.2D)
      val trampolineLargeDiscount = trampolineFee(linear.toLong, exponent = 0.79D, logExponent = 2.1D)
      val trampolineExtraLargeDiscount = trampolineFee(linear.toLong, exponent = 0.76D, logExponent = 2.0D)

      assert(linear == 500000L.msat)
      assert(trampolineModerateDiscount == 47403.msat)
      assert(trampolineLargeDiscount == 32006.msat)
      assert(trampolineExtraLargeDiscount == 21612.msat)
    }

    {
      val payment = 10000000000L.msat
      val linear = proportionalFee(payment, proportionalFee = 500)
      val trampolineModerateDiscount = trampolineFee(linear.toLong, exponent = 0.82D, logExponent = 2.2D)
      val trampolineLargeDiscount = trampolineFee(linear.toLong, exponent = 0.79D, logExponent = 2.1D)
      val trampolineExtraLargeDiscount = trampolineFee(linear.toLong, exponent = 0.76D, logExponent = 2.0D)

      assert(linear == 5000000L.msat)
      assert(trampolineModerateDiscount == 311695.msat)
      assert(trampolineLargeDiscount == 196282.msat)
      assert(trampolineExtraLargeDiscount == 123611.msat)
    }
  }
}
