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

import fr.acinq.eclair._
import scala.concurrent.duration._
import fr.acinq.bitcoin.{Satoshi, SatoshiLong}

/**
 * Created by PM on 20/08/2015.
 */

object Channel {
  val ANNOUNCEMENTS_MINCONF = 6

  val MAX_ACCEPTED_HTLCS = 483

  val MIN_DUSTLIMIT: Satoshi = 546.sat

  val MAX_NEGOTIATION_ITERATIONS = 20

  val MIN_CLTV_EXPIRY_DELTA: CltvExpiryDelta = CltvExpiryDelta(18)

  val MAX_CLTV_EXPIRY_DELTA: CltvExpiryDelta = CltvExpiryDelta(7 * 144)

  val MAX_TO_SELF_DELAY: CltvExpiryDelta = CltvExpiryDelta(2016)

  val FUNDING_TIMEOUT_FUNDEE: FiniteDuration = 28.days
}
