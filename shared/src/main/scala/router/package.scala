package immortan

import scoin._

package object router {
  def proportionalFee(
      paymentAmount: MilliSatoshi,
      proportionalFee: Long
  ): MilliSatoshi = (paymentAmount * proportionalFee) / 1000000

  def nodeFee(
      baseFee: MilliSatoshi,
      proportionalRatio: Long,
      paymentAmount: MilliSatoshi
  ): MilliSatoshi = baseFee + proportionalFee(paymentAmount, proportionalRatio)

  // proportional^(exponent = 1) + ln(proportional)^(logExponent = 0) is linear
  // proportional^(exponent = 0.82) + ln(proportional)^(logExponent = 2.2) gives moderate discounts
  // proportional^(exponent = 0.79) + ln(proportional)^(logExponent = 2.1) gives substantial discounts for large amounts
  // proportional^(exponent = 0.76) + ln(proportional)^(logExponent = 2.0) gives extremely large discounts for large amounts
  // proportional^(exponent = 0) + ln(proportional)^(logExponent = 0) gives base + 2 msat, independent of payment amount
  def trampolineFee(
      proportional: Long,
      exponent: Double,
      logExponent: Double
  ): MilliSatoshi = {
    val nonLinearFeeMsat = math.pow(proportional.toDouble, exponent) + math.pow(
      math.log(proportional.toDouble),
      logExponent
    )
    MilliSatoshi(nonLinearFeeMsat.ceil.toLong)
  }
}
