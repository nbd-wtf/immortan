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

import org.json4s.JsonAST.{JArray, JInt}
import org.json4s.{DefaultFormats, Serialization}
import scala.concurrent.{ExecutionContext, Future}
import fr.acinq.bitcoin.{Block, BlockHeader, ByteVector32}
import com.softwaremill.sttp.{StatusCodes, SttpBackend, Uri, UriContext, sttp}
import fr.acinq.eclair.blockchain.watchdog.BlockchainWatchdog.{BlockHeaderAt, LatestHeaders}
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import scala.concurrent.duration.DurationInt
import com.softwaremill.sttp.json4s.asJson
import org.json4s.jackson.Serialization


/**
 * Created by t-bast on 12/10/2020.
 */

/** This actor queries a configurable explorer API to fetch block headers. */
object ExplorerApi {

  implicit val formats: DefaultFormats = DefaultFormats
  implicit val serialization: Serialization = Serialization
  implicit val sttpBackend: SttpBackend[Future, Nothing] = OkHttpFutureBackend()

  sealed trait Explorer {
    // @formatter:off
    /** Explorer friendly-name. */
    def name: String
    /** Map from chainHash to explorer API URI. */
    def baseUris: Map[ByteVector32, Uri]
    /** Fetch latest headers from the explorer. */
    def getLatestHeaders(baseUri: Uri, currentBlockCount: Long)(implicit context: ExecutionContext): Future[Seq[BlockHeaderAt]]
    // @formatter:on
  }

  /** Explorer API based on Esplora: see https://github.com/Blockstream/esplora/blob/master/API.md. */
  sealed trait Esplora extends Explorer {
    override def getLatestHeaders(baseUri: Uri, lastKnownTip: Long)(implicit context: ExecutionContext): Future[Seq[BlockHeaderAt]] = {
      val nextKnownTip = lastKnownTip + 10
      for {
        headers <- sttp.readTimeout(10.seconds).get(baseUri.path(baseUri.path :+ "blocks" :+ nextKnownTip.toString))
          .response(asJson[JArray])
          .send()
          .map(r => r.code match {
            // HTTP 404 is a "normal" error: we're trying to lookup future blocks that haven't been mined.
            case StatusCodes.NotFound => Seq.empty
            case _ => r.unsafeBody.arr
          })
          .map(blocks => blocks.map(block => {
            val JInt(height) = block \ "height"
            val JInt(version) = block \ "version"
            val JInt(time) = block \ "timestamp"
            val JInt(bits) = block \ "bits"
            val JInt(nonce) = block \ "nonce"
            val previousBlockHash = (block \ "previousblockhash").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
            val merkleRoot = (block \ "merkle_root").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
            val header = BlockHeader(version.toLong, previousBlockHash, merkleRoot, time.toLong, bits.toLong, nonce.toLong)
            BlockHeaderAt(height.toLong, header)
          }))
          .map(_.filter(_.blockCount >= lastKnownTip).distinct)
      } yield headers
    }
  }

  /** Query https://blockstream.info/ to fetch block headers. */
  case object BlockstreamExplorer extends Esplora {
    override val name = "blockstream.info"
    override val baseUris = Map(
      Block.TestnetGenesisBlock.hash -> uri"https://blockstream.info/testnet/api",
      Block.LivenetGenesisBlock.hash -> uri"https://blockstream.info/api"
    )
  }

  /** Query https://mempool.space/ to fetch block headers. */
  case object MempoolSpaceExplorer extends Esplora {
    override val name = "mempool.space"
    override val baseUris = Map(
      Block.TestnetGenesisBlock.hash -> uri"https://mempool.space/testnet/api",
      Block.LivenetGenesisBlock.hash -> uri"https://mempool.space/api"
    )
  }
}