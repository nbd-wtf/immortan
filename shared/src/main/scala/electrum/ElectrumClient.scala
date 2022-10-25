package immortan.electrum

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scodec.bits.ByteVector
import io.circe._
import io.circe.parser.parse
import io.circe.generic.semiauto._
import io.circe.syntax._
import scoin._

import immortan.{LNParams, every, after}
import immortan.electrum.ElectrumClient._

trait ElectrumEvent

sealed trait ElectrumClientStatus extends ElectrumEvent
case class ElectrumReady(height: Int, tip: BlockHeader)
    extends ElectrumClientStatus
case object ElectrumDisconnected extends ElectrumClientStatus

case class CurrentBlockCount(blockCount: Long) extends ElectrumEvent

trait ElectrumClient {
  def address: String
  def shutdown(): Unit
  def request[R <: Response](r: Request): Future[R]
}

object ElectrumClient {
  val CLIENT_NAME = "3.3.6"
  val PROTOCOL_VERSION = "1.4"

  class ElectrumServerError(msg: String) extends Exception

  def apply(
      pool: ElectrumClientPool,
      server: ElectrumClientPool.ElectrumServerAddress,
      onReady: ElectrumClient => Unit
  ) = new ElectrumClientPlatform(pool, server, onReady)

  def computeScriptHash(publicKeyScript: ByteVector): ByteVector32 =
    Crypto.sha256(publicKeyScript).reverse

  def computeScriptHashStatus(items: List[TransactionHistoryItem]): String =
    Crypto
      .sha256(
        ByteVector.view(
          items
            .map { case TransactionHistoryItem(height, txid) =>
              s"${txid.toHex}:$height:"
            }
            .mkString("")
            .getBytes()
        )
      )
      .toHex

  def scriptHashStatusMatches(
      history: List[TransactionHistoryItem],
      status: String
  ): Boolean =
    product(
      history
        .groupBy {
          case TransactionHistoryItem(height, _) if height <= 0 => 99999999
          case TransactionHistoryItem(height, _)                => height
        }
        .toList
        .sortBy(_._1)
        .map(_._2)
        .map(items => items.permutations.toList)
    )
      .map(_.flatten)
      .map(computeScriptHashStatus(_))
      .find(_ == status)
      .isDefined

  private def product[T](xss: List[List[T]]): List[List[T]] = xss match {
    case Nil    => List(Nil)
    case h :: t => for (xh <- h; xt <- product(t)) yield xh :: xt
  }

  sealed trait Request { def contextOpt: Option[Any] = None }
  sealed trait Response extends ElectrumEvent {
    def contextOpt: Option[Any] = None
  }

  // this is for when we don't care about the response
  case object IrrelevantResponse extends Response

  case class ServerVersion(clientName: String, protocolVersion: String)
      extends Request
  case class ServerVersionResponse(
      clientName: String,
      protocolVersion: String
  ) extends Response

  case object Ping extends Request
  case object PingResponse extends Response

  case class GetScriptHashHistory(scriptHash: ByteVector32) extends Request
  case class TransactionHistoryItem(height: Int, txHash: ByteVector32)
  case class GetScriptHashHistoryResponse(
      scriptHash: ByteVector32,
      history: List[TransactionHistoryItem] = Nil
  ) extends Response

  case class BroadcastTransaction(tx: Transaction) extends Request
  case class BroadcastTransactionResponse(
      tx: Transaction,
      error: Option[JSONRPC.Error] = None
  ) extends Response

  case class GetTransaction(
      txid: ByteVector32,
      override val contextOpt: Option[Any] = None
  ) extends Request
  case class GetTransactionResponse(
      tx: Transaction,
      override val contextOpt: Option[Any]
  ) extends Response

  case class GetHeaders(startHeight: Int, count: Int, cpHeight: Int = 0)
      extends Request
  case class GetHeadersResponse(
      source: ElectrumClient,
      startHeight: Int,
      headers: Seq[BlockHeader],
      max: Int
  ) extends Response

  case class GetMerkle(
      txid: ByteVector32,
      height: Int,
      override val contextOpt: Option[Any] = None
  ) extends Request
  case class GetMerkleResponse(
      source: Option[ElectrumClient],
      txid: ByteVector32,
      merkle: List[ByteVector32],
      blockHeight: Int,
      pos: Int,
      override val contextOpt: Option[Any] = None
  ) extends Response {
    lazy val root: ByteVector32 = {
      @tailrec
      def loop(pos: Int, hashes: Seq[ByteVector32] = Nil): ByteVector32 = {
        if (hashes.length == 1) hashes.head
        else {
          val h =
            if (pos % 2 == 1) Crypto.hash256(hashes(1) ++ hashes.head)
            else Crypto.hash256(hashes.head ++ hashes(1))
          loop(pos / 2, h +: hashes.drop(2))
        }
      }
      loop(pos, txid.reverse +: merkle.map(b => b.reverse))
    }
  }

  case class ScriptHashSubscription(scriptHash: ByteVector32) extends Request
  case class ScriptHashSubscriptionResponse(
      scriptHash: ByteVector32,
      status: String
  ) extends Response

  case class HeaderSubscription() extends Request
  case class HeaderSubscriptionResponse(
      source: ElectrumClient,
      height: Int,
      header: BlockHeader
  ) extends Response

  case class Header(
      block_height: Long,
      version: Long,
      prev_block_hash: ByteVector32,
      merkle_root: ByteVector32,
      timestamp: Long,
      bits: Long,
      nonce: Long
  ) {
    def blockHeader = BlockHeader(
      version,
      prev_block_hash.reverse,
      merkle_root.reverse,
      timestamp,
      bits,
      nonce
    )

    lazy val block_hash: ByteVector32 = blockHeader.hash
    lazy val block_id: ByteVector32 = block_hash.reverse
  }

  object Header {
    def makeHeader(height: Long, header: BlockHeader) = ElectrumClient.Header(
      height,
      header.version,
      header.hashPreviousBlock.reverse,
      header.hashMerkleRoot.reverse,
      header.time,
      header.bits,
      header.nonce
    )

    val RegtestGenesisHeader: Header =
      makeHeader(0, Block.RegtestGenesisBlock.header)
    val SignetGenesisHeader: Header =
      makeHeader(0, Block.SignetGenesisBlock.header)
    val TestnetGenesisHeader: Header =
      makeHeader(0, Block.TestnetGenesisBlock.header)
    val LivenetGenesisHeader: Header =
      makeHeader(0, Block.LivenetGenesisBlock.header)
  }

  case class TransactionHistory(history: Seq[TransactionHistoryItem])
      extends Response

  case class ServerError(request: Request, error: JSONRPC.Error)
      extends Response

  sealed trait SSL

  object SSL {
    case object DECIDE extends SSL
    case object LOOSE extends SSL
  }

  def makeRequest(request: Request, reqId: String): JSONRPC.Request =
    request match {
      case ServerVersion(clientName, protocolVersion) =>
        JSONRPC.Request(
          id = reqId,
          method = "server.version",
          params = clientName :: protocolVersion :: Nil
        )
      case Ping =>
        JSONRPC.Request(id = reqId, method = "server.ping", params = Nil)
      case GetScriptHashHistory(scripthash) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.scripthash.get_history",
          params = scripthash.toHex :: Nil
        )
      case ScriptHashSubscription(scriptHash) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.scripthash.subscribe",
          params = scriptHash.toString() :: Nil
        )
      case BroadcastTransaction(tx) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.transaction.broadcast",
          params = Transaction.write(tx).toHex :: Nil
        )
      case GetTransaction(txid, _) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.transaction.get",
          params = txid :: Nil
        )
      case HeaderSubscription() =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.headers.subscribe",
          params = Nil
        )
      case GetHeaders(start_height, count, _) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.block.headers",
          params = start_height :: count :: Nil
        )
      case GetMerkle(txid, height, _) =>
        JSONRPC.Request(
          id = reqId,
          method = "blockchain.transaction.get_merkle",
          params = txid :: height :: Nil
        )
    }

  def parseElectrumMessage(
      receiver: ElectrumClient,
      input: String
  ): Either[Response, JSONRPC.Response] = {
    val json = parse(input).toTry.get
    val c = json.hcursor
    val params = c.downField("params")

    // presence of method indicates this is a json-rpc request, i.e. a subscription notification
    //   otherwise this is a json-rpc response
    c.get[String]("method") match {
      case Left(_) =>
        Right(json.as[JSONRPC.Response].toTry.get)
      case Right("blockchain.headers.subscribe") =>
        val height = params.downN(0).downField("height").as[Int].toTry.get
        val header = BlockHeader.read(
          params.downN(0).downField("hex").as[String].toTry.get
        )
        Left(HeaderSubscriptionResponse(receiver, height, header))
      case Right("blockchain.scripthash.subscribe") =>
        val scripthash = ByteVector32.fromValidHex(
          params.downN(0).downField("scripthash").as[String].toTry.get
        )
        val status =
          params.downN(0).downField("status").as[String].getOrElse("")
        Left(ScriptHashSubscriptionResponse(scripthash, status))
      case Right(other) =>
        System.err.println(
          s"[debug][electrum] got unexpected '$other' at ${receiver.address}, ignoring"
        )
        Right(JSONRPC.Response("ignored", None, None))
    }
  }

  def parseJsonResponse(
      source: ElectrumClient,
      request: Request,
      json: JSONRPC.Response
  ): Response = (json.error, json.result) match {
    case (Some(error), _) =>
      request match {
        case BroadcastTransaction(tx) =>
          // for this request type, error are considered a "normal" response
          BroadcastTransactionResponse(tx, Some(error))
        case _ => ServerError(request, error)
      }
    case (_, Some(response)) =>
      val c = response.hcursor
      request match {
        case _: ServerVersion =>
          val software = c.downN(0).as[String].toTry.get
          val version = c.downN(1).as[String].toTry.get
          ServerVersionResponse(software, version)
        case Ping => PingResponse
        case GetScriptHashHistory(scripthash) =>
          val items = c.values.get.map { it =>
            TransactionHistoryItem(
              it.hcursor.get[Int]("height").toTry.get,
              ByteVector32.fromValidHex(
                it.hcursor.get[String]("tx_hash").toTry.get
              )
            )
          }
          GetScriptHashHistoryResponse(scripthash, items.toList)
        case GetTransaction(_, cOpt) =>
          val tx = Transaction.read(c.as[String].toTry.get)
          GetTransactionResponse(tx, cOpt)
        case HeaderSubscription() =>
          val height = c.get[Int]("height").toTry.get
          val header = BlockHeader.read(c.get[String]("hex").toTry.get)
          HeaderSubscriptionResponse(source, height, header)
        case ScriptHashSubscription(scriptHash) =>
          val status = c.as[String].getOrElse("")
          ScriptHashSubscriptionResponse(scriptHash, status)
        case BroadcastTransaction(tx) =>
          // if we got here, it means that the server's response does not contain an error and message should be our
          // transaction id. However, it seems that at least on testnet some servers still use an older version of the
          // Electrum protocol and return an error message in the result field
          val msg = c
            .as[String]
            .getOrElse(
              "error: response from BroadcastTransaction is not a string"
            )
          Try(ByteVector32.fromValidHex(msg)) match {
            case Success(txid) if txid == tx.txid =>
              BroadcastTransactionResponse(tx, None)
            case Success(txid) =>
              val err = Some(
                JSONRPC.Error(
                  1,
                  s"response txid $txid does not match request txid ${tx.txid}"
                )
              )
              BroadcastTransactionResponse(tx, err)
            case Failure(_) =>
              BroadcastTransactionResponse(tx, Some(JSONRPC.Error(1, msg)))
          }
        case GetHeaders(start_height, _, _) =>
          val max = c.get[Int]("max").toTry.get
          val hex = c.get[String]("hex").toTry.get
          val bin = ByteVector.fromValidHex(hex).toArray
          val blockHeaders = bin.grouped(80).map(BlockHeader.read).toList
          GetHeadersResponse(source, start_height, blockHeaders, max)
        case GetMerkle(txid, _, cOpt) =>
          val pos = c.get[Int]("pos").toTry.get
          val height = c.get[Int]("block_height").toTry.get
          val hashes =
            c.downField("merkle")
              .values
              .get
              .map(_.as[String].toTry.get)
              .map(ByteVector32.fromValidHex(_))
              .toList
          GetMerkleResponse(Some(source), txid, hashes, height, pos, cOpt)
      }
    case _ => IrrelevantResponse
  }
}

object JSONRPC {
  case class Request(
      id: String,
      method: String,
      params: Seq[Any] = Nil
  )
  given Encoder[Request] = new Encoder {
    final def apply(req: Request): Json = Json.obj(
      "jsonrpc" := "2.0",
      "id" := req.id,
      "method" := req.method,
      "params" := Json.fromValues(req.params.map {
        case s: String       => s.asJson
        case b: ByteVector32 => b.toHex.asJson
        case f: FeeratePerKw => f.toLong.asJson
        case b: Boolean      => b.asJson
        case t: Int          => t.asJson
        case t: Long         => t.asJson
        case t: Double       => t.asJson
      })
    )
  }

  case class Response(
      id: String,
      error: Option[Error],
      result: Option[Json]
  )
  given Decoder[Response] = new Decoder {
    final def apply(c: HCursor): Decoder.Result[Response] =
      for {
        id <- c.downField("id").as[String]
        error = c.downField("error").as[Error].toOption
        result = c.downField("result").focus
      } yield {
        new Response(id, error, result)
      }
  }

  case class Error(code: Int, message: String)
  given Decoder[Error] = deriveDecoder
  given Encoder[Error] = deriveEncoder
}
