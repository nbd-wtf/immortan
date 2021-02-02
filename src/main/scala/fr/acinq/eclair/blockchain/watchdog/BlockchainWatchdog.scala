/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.blockchain.watchdog

import fr.acinq.bitcoin.BlockHeader

/**
 * Created by t-bast on 29/09/2020.
 */

/** Monitor secondary blockchain sources to detect when we're being eclipsed. */
object BlockchainWatchdog {
  case class BlockHeaderAt(blockCount: Long, blockHeader: BlockHeader)

  sealed trait BlockchainWatchdogEvent

  case class DangerousBlocksSkew(recentHeaders: LatestHeaders) extends BlockchainWatchdogEvent

  case class LatestHeaders(currentBlockCount: Long, blockHeaders: Seq[BlockHeaderAt], source: String)
}
