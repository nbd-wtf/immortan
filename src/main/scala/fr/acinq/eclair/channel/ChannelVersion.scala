/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel

import scodec.bits._
import fr.acinq.eclair.channel.ChannelVersion._


object ChannelVersion {
  val LENGTH_BITS: Int = 4 * 8

  private val USE_PUBKEY_KEYPATH_BIT = 0 // bit numbers start at 0

  def setBit(bit: Int) = ChannelVersion(BitVector.low(LENGTH_BITS).set(bit).reverse)

  val ZEROES = ChannelVersion(bin"00000000000000000000000000000000")

  val STANDARD: ChannelVersion = ZEROES | setBit(USE_PUBKEY_KEYPATH_BIT)
}

object HostedChannelVersion {
  val USE_RESIZE = 1

  val RESIZABLE: ChannelVersion = STANDARD | setBit(USE_RESIZE)
}

case class ChannelVersion(bits: BitVector) {
  require(bits.size == ChannelVersion.LENGTH_BITS, "channel version takes 4 bytes")

  def |(other: ChannelVersion) = ChannelVersion(bits | other.bits)

  def &(other: ChannelVersion) = ChannelVersion(bits & other.bits)

  def ^(other: ChannelVersion) = ChannelVersion(bits ^ other.bits)

  def isSet(bit: Int): Boolean = bits.reverse.get(bit)
}