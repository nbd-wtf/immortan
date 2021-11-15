package fr.acinq.eclair

import fr.acinq.bitcoin.{Btc, BtcAmount, MilliBtc, Satoshi, btc2satoshi, millibtc2satoshi}


case class MilliSatoshi(underlying: Long) extends AnyVal with Ordered[MilliSatoshi] {
  def +(other: MilliSatoshi): MilliSatoshi = MilliSatoshi(underlying + other.underlying)
  def -(other: MilliSatoshi): MilliSatoshi = MilliSatoshi(underlying - other.underlying)
  def +(other: BtcAmount): MilliSatoshi = MilliSatoshi(underlying + other.toMilliSatoshi.underlying)
  def -(other: BtcAmount): MilliSatoshi = MilliSatoshi(underlying - other.toMilliSatoshi.underlying)
  def *(m: Double): MilliSatoshi = MilliSatoshi(underlying = (underlying * m).toLong)
  def *(m: Long): MilliSatoshi = MilliSatoshi(underlying * m)
  def /(d: Long): MilliSatoshi = MilliSatoshi(underlying / d)

  override def compare(other: MilliSatoshi): Int = underlying.compareTo(other.underlying)
  // Since BtcAmount is a sealed trait that MilliSatoshi cannot extend, we need to redefine comparison operators.
  def compare(other: BtcAmount): Int = compare(other.toMilliSatoshi)
  def <=(other: BtcAmount): Boolean = compare(other) <= 0
  def >=(other: BtcAmount): Boolean = compare(other) >= 0
  def <(other: BtcAmount): Boolean = compare(other) < 0
  def >(other: BtcAmount): Boolean = compare(other) > 0

  // Asymmetric min/max functions to provide more control on the return type
  def max(other: MilliSatoshi): MilliSatoshi = if (this > other) this else other
  def min(other: MilliSatoshi): MilliSatoshi = if (this < other) this else other
  def max(other: BtcAmount): MilliSatoshi = if (this > other) this else other.toMilliSatoshi
  def min(other: BtcAmount): MilliSatoshi = if (this < other) this else other.toMilliSatoshi
  def truncateToSatoshi: Satoshi = Satoshi(underlying / 1000)
  def toLong: Long = underlying
}

object MilliSatoshi {
  private def satoshi2millisatoshi(input: Satoshi): MilliSatoshi = MilliSatoshi(input.toLong * 1000L)

  def toMilliSatoshi(amount: BtcAmount): MilliSatoshi = amount match {
    case millis: MilliBtc => satoshi2millisatoshi(millibtc2satoshi(millis))
    case bitcoin: Btc => satoshi2millisatoshi(btc2satoshi(bitcoin))
    case sat: Satoshi => satoshi2millisatoshi(sat)
  }
}
