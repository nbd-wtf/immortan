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
import fr.acinq.bitcoin.SatoshiLong

/**
 * Created by PM on 20/08/2015.
 */

object Channel {
  // see https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
  val ANNOUNCEMENTS_MINCONF = 6

  // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
  val MAX_FUNDING = 1000000000.sat // = 10 btc
  val MAX_ACCEPTED_HTLCS = 483

  // we don't want the counterparty to use a dust limit lower than that, because they wouldn't only hurt themselves we may need them to publish their commit tx in certain cases (backup/restore)
  val MIN_DUSTLIMIT = 546.sat

  // we won't exchange more than this many signatures when negotiating the closing fee
  val MAX_NEGOTIATION_ITERATIONS = 20

  // this is defined in BOLT 11
  val MIN_CLTV_EXPIRY_DELTA = CltvExpiryDelta(18)
  val MAX_CLTV_EXPIRY_DELTA = CltvExpiryDelta(7 * 144) // one week

  // since BOLT 1.1, there is a max value for the refund delay of the main commitment tx
  val MAX_TO_SELF_DELAY = CltvExpiryDelta(2016)

  // as a fundee, we will wait that much time for the funding tx to confirm (funder will rely on the funding tx being double-spent)
  val FUNDING_TIMEOUT_FUNDEE = 5.days

  // pruning occurs if no new update has been received in two weeks (BOLT 7)
  val REFRESH_CHANNEL_UPDATE_INTERVAL = 10.days

  // @formatter:off
  sealed trait BroadcastReason
  case object PeriodicRefresh extends BroadcastReason
  case object Reconnected extends BroadcastReason
  case object AboveReserve extends BroadcastReason
  // @formatter:on
}
