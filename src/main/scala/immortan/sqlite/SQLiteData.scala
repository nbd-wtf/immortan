package immortan.sqlite

import spray.json._
import immortan.sqlite.SQLiteData._
import immortan.utils.ImplicitJsonFormats._

import java.lang.{Integer => JInt}
import immortan.utils.{FeeRatesInfo, FiatRatesInfo}
import fr.acinq.bitcoin.{BlockHeader, ByteVector32}
import fr.acinq.eclair.wire.{HostedChannelBranding, SwapInState}
import immortan.{DataBag, LastChainBalance, SwapInStateExt, WalletSecret}
import fr.acinq.eclair.wire.LightningMessageCodecs.{hostedChannelBrandingCodec, swapInStateCodec}
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb.persistentDataCodec
import fr.acinq.eclair.blockchain.electrum.PersistentData
import fr.acinq.eclair.blockchain.electrum.db.WalletDb
import immortan.wire.ExtCodecs.walletSecretCodec
import fr.acinq.bitcoin.Crypto.PublicKey
import immortan.crypto.Tools.Bytes
import scodec.bits.ByteVector
import scala.util.Try


object SQLiteData {
  final val LABEL_FORMAT = "label-format"
  final val LABEL_FEE_RATES = "label-fee-rates"
  final val LABEL_FIAT_RATES = "label-fiat-rates"

  final val LABEL_BRANDING_PREFIX = "label-branding-node-"
  final val LABEL_SWAP_IN_STATE_PREFIX = "label-swap-in-node-"
  final val LABEL_ELECTRUM_DATA_PREFIX = "label-electrum-data-"
  final val LABEL_PAYMENT_REPORT_PREFIX = "label-payment-report-"
  final val LABEL_LAST_CHAIN_BALANCE_PREFIX = "label-last-chain-balance-"
  def byteVecToString(bv: ByteVector): String = new String(bv.toArray, "UTF-8")
}

class SQLiteData(val db: DBInterface) extends WalletDb with DataBag {
  def delete(label: String): Unit = db.change(DataTable.killSql, label)

  def tryGet(keyValueLabel: String): Try[ByteVector] =
    db.select(DataTable.selectSql, keyValueLabel)
      .headTry(_ byteVec DataTable.content)

  def put(label: String, content: Bytes): Unit = {
    // Insert and then update because of INSERT IGNORE
    db.change(DataTable.newSql, label, content)
    db.change(DataTable.updSql, content, label)
  }

  // StorageFormat

  def putSecret(secret: WalletSecret): Unit = put(LABEL_FORMAT, walletSecretCodec.encode(secret).require.toByteArray)

  def tryGetSecret: Try[WalletSecret] = tryGet(LABEL_FORMAT).map(raw => walletSecretCodec.decode(raw.toBitVector).require.value)

  // Last balance, fiat rates, fee rates

  def putLastChainBalance(data: LastChainBalance, tag: String): Unit = put(LABEL_LAST_CHAIN_BALANCE_PREFIX + tag, data.toJson.compactPrint getBytes "UTF-8")

  def tryGetLastChainBalance(tag: String): Try[LastChainBalance] = tryGet(LABEL_LAST_CHAIN_BALANCE_PREFIX + tag).map(SQLiteData.byteVecToString) map to[LastChainBalance]

  def putFiatRatesInfo(data: FiatRatesInfo): Unit = put(LABEL_FIAT_RATES, data.toJson.compactPrint getBytes "UTF-8")

  def tryGetFiatRatesInfo: Try[FiatRatesInfo] = tryGet(LABEL_FIAT_RATES).map(SQLiteData.byteVecToString) map to[FiatRatesInfo]

  def putFeeRatesInfo(data: FeeRatesInfo): Unit = put(LABEL_FEE_RATES, data.toJson.compactPrint getBytes "UTF-8")

  def tryGetFeeRatesInfo: Try[FeeRatesInfo] = tryGet(LABEL_FEE_RATES).map(SQLiteData.byteVecToString) map to[FeeRatesInfo]

  // Payment reports

  def putReport(paymentHash: ByteVector32, report: String): Unit = put(LABEL_PAYMENT_REPORT_PREFIX + paymentHash.toHex, report getBytes "UTF-8")

  def tryGetReport(paymentHash: ByteVector32): Try[String] = tryGet(LABEL_PAYMENT_REPORT_PREFIX + paymentHash.toHex).map(byteVecToString)

  // HostedChannelBranding

  def putBranding(nodeId: PublicKey, branding: HostedChannelBranding): Unit = {
    val hostedChannelBranding = hostedChannelBrandingCodec.encode(branding).require.toByteArray
    put(LABEL_BRANDING_PREFIX + nodeId.toString, hostedChannelBranding)
  }

  def tryGetBranding(nodeId: PublicKey): Try[HostedChannelBranding] =
    tryGet(LABEL_BRANDING_PREFIX + nodeId.toString) map { rawHostedChannelBranding =>
      hostedChannelBrandingCodec.decode(rawHostedChannelBranding.toBitVector).require.value
    }

  // SwapInState

  def putSwapInState(nodeId: PublicKey, state: SwapInState): Unit = {
    val swapInState = swapInStateCodec.encode(state).require.toByteArray
    put(LABEL_SWAP_IN_STATE_PREFIX + nodeId.toString, swapInState)
  }

  def tryGetSwapInState(nodeId: PublicKey): Try[SwapInStateExt] =
    tryGet(LABEL_SWAP_IN_STATE_PREFIX + nodeId.toString) map { rawSwapInState =>
      SwapInStateExt(swapInStateCodec.decode(rawSwapInState.toBitVector).require.value, nodeId)
    }

  // WalletDb

  override def persist(data: PersistentData, tag: String): Unit = put(LABEL_ELECTRUM_DATA_PREFIX + tag, persistentDataCodec.encode(data).require.toByteArray)

  override def readPersistentData(tag: String): Option[PersistentData] = tryGet(LABEL_ELECTRUM_DATA_PREFIX + tag).map(raw => persistentDataCodec.decode(raw.toBitVector).require.value).toOption

  // HeadersDb

  override def addHeaders(startHeight: Int, headers: Seq[BlockHeader] = Nil): Unit = {
    val addHeaderSqlPQ = db.makePreparedQuery(ElectrumHeadersTable.addHeaderSql)

    db txWrap {
      for (Tuple2(header, idx) <- headers.zipWithIndex) {
        val serialized: Array[Byte] = BlockHeader.write(header).toArray
        db.change(addHeaderSqlPQ, startHeight + idx: JInt, header.hash.toHex, serialized)
      }
    }

    addHeaderSqlPQ.close
  }

  override def getHeader(height: Int): Option[BlockHeader] =
    db.select(ElectrumHeadersTable.selectByHeightSql, height.toString).headTry { rc =>
      BlockHeader.read(rc bytes ElectrumHeadersTable.header)
    }.toOption

  // Only used in testing currently
  override def getHeader(blockHash: ByteVector32): Option[HeightAndHeader] =
    db.select(ElectrumHeadersTable.selectByBlockHashSql, blockHash.toHex).headTry { rc =>
      val header = BlockHeader.read(rc bytes ElectrumHeadersTable.header)
      val height = rc int ElectrumHeadersTable.height
      (height, header)
    }.toOption

  override def getHeaders(startHeight: Int, maxCount: Int): Seq[BlockHeader] =
    db.select(ElectrumHeadersTable.selectHeadersSql, startHeight.toString, maxCount.toString).iterable { rc =>
      BlockHeader.read(rc bytes ElectrumHeadersTable.header)
    }.toList

  override def getTip: Option[HeightAndHeader] =
    db.select(ElectrumHeadersTable.selectTipSql).headTry { rc =>
      val header = BlockHeader.read(rc bytes ElectrumHeadersTable.header)
      val height = rc int ElectrumHeadersTable.height
      (height, header)
    }.toOption
}
