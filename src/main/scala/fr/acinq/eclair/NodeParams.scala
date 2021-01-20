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

package fr.acinq.eclair

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.crypto.keymanager.{ChannelKeyManager, NodeKeyManager}
import fr.acinq.eclair.router.Router.RouterConf
import fr.acinq.eclair.wire.{Color, NodeAddress}
import scala.concurrent.duration._

/**
 * Created by PM on 26/02/2017.
 */
case class NodeParams(nodeKeyManager: NodeKeyManager,
                      channelKeyManager: ChannelKeyManager,
                      blockCount: AtomicLong,
                      features: Features,
                      dustLimit: Satoshi,
                      onChainFeeConf: OnChainFeeConf,
                      maxHtlcValueInFlightMsat: UInt64,
                      maxAcceptedHtlcs: Int,
                      expiryDelta: CltvExpiryDelta,
                      fulfillSafetyBeforeTimeout: CltvExpiryDelta,
                      minFinalExpiryDelta: CltvExpiryDelta,
                      htlcMinimum: MilliSatoshi,
                      toRemoteDelay: CltvExpiryDelta,
                      maxToLocalDelay: CltvExpiryDelta,
                      minDepthBlocks: Int,
                      feeBase: MilliSatoshi,
                      feeProportionalMillionth: Int,
                      reserveToFundingRatio: Double,
                      maxReserveToFundingRatio: Double,
                      revocationTimeout: FiniteDuration,
                      autoReconnect: Boolean,
                      initialRandomReconnectDelay: FiniteDuration,
                      maxReconnectInterval: FiniteDuration,
                      chainHash: ByteVector32,
                      channelFlags: Byte,
                      watchSpentWindow: FiniteDuration,
                      paymentRequestExpiry: FiniteDuration,
                      multiPartPaymentExpiry: FiniteDuration,
                      minFundingSatoshis: Satoshi,
                      maxFundingSatoshis: Satoshi,
                      routerConf: RouterConf,
                      enableTrampolinePayment: Boolean)