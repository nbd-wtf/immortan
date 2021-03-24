package immortan

import fr.acinq.eclair._
import org.scalatest.funsuite.AnyFunSuite


class TrampolineFeerateSpec extends AnyFunSuite {
  test("Non-linear fees for trampoline routing") {

    val smallPayment = 1000000L.msat
    val smallLinear = proportionalFee(smallPayment, proportionalFee = 1000)
    val smallTrampolineLinear = trampolineFee(smallLinear.toLong, baseFee = 0L.msat, exponent = 1.0D, logExponent = 0.0D)
    val smallTrampolineModerateDiscount = trampolineFee(smallLinear.toLong, baseFee = 0L.msat, exponent = 0.97D, logExponent = 3.9D)
    val smallTrampolineLargeDiscount = trampolineFee(smallLinear.toLong, baseFee = 0L.msat, exponent = 0.9D, logExponent = 4.0D)

    assert(smallLinear == 1000L.msat)
    assert(smallTrampolineLinear == 1001L.msat) // About the same as linear
    assert(smallTrampolineModerateDiscount == 2690L.msat) // Overcharges for small amount
    assert(smallTrampolineLargeDiscount == 2779L.msat) // Overcharges a lot for a small amount

    val largePayment = 1000000000L.msat
    val largeLinear = proportionalFee(largePayment, proportionalFee = 1000)
    val largeTrampolineLinear = trampolineFee(largeLinear.toLong, baseFee = 0L.msat, exponent = 1.0D, logExponent = 0.0D)
    val largeTrampolineModerateDiscount = trampolineFee(largeLinear.toLong, baseFee = 0L.msat, exponent = 0.97D, logExponent = 3.9D)
    val largeTrampolineLargeDiscount = trampolineFee(largeLinear.toLong, baseFee = 0L.msat, exponent = 0.9D, logExponent = 4.0D)

    assert(largeLinear == 1000000L.msat)
    assert(largeTrampolineLinear == 1000001L.msat) // About the same as linear
    assert(largeTrampolineModerateDiscount == 688712L.msat) // Discounts on large amount
    assert(largeTrampolineLargeDiscount == 287620L.msat) // Discounts a lot on large amount
  }
}
