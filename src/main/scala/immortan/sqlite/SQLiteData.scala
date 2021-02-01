package immortan.sqlite

import spray.json._
import immortan.sqlite.SQLiteData._
import immortan.utils.ImplicitJsonFormats._

import java.lang.{Integer => JInt}
import fr.acinq.bitcoin.{BlockHeader, ByteVector32}
import fr.acinq.eclair.wire.{HostedChannelBranding, SwapInState}
import immortan.{DataTable, ElectrumHeadersTable, StorageFormat, SwapInStateExt}
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb.persistentDataCodec
import fr.acinq.eclair.wire.HostedMessagesCodecs.hostedChannelBrandingCodec
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.PersistentData
import fr.acinq.eclair.wire.SwapCodecs.swapInStateCodec
import fr.acinq.eclair.blockchain.electrum.db.WalletDb
import fr.acinq.bitcoin.Crypto.PublicKey
import immortan.crypto.Tools.Bytes
import scodec.bits.BitVector
import scala.util.Try


object SQLiteData {
  final val LABEL_FORMAT = "label-format"
  final val LABEL_ELECTRUM_DATA = "label-electrum-data"
  final val LABEL_BRANDING_PREFIX = "label-branding-node-"
  final val LABEL_SWAP_IN_STATE_PREFIX = "label-swap-in-node-"
}

class SQLiteData(db: DBInterface) extends WalletDb {
  def delete(label: String): Unit = db.change(DataTable.killSql, label)

  def tryGet(label: String): Try[BitVector] = db.select(DataTable.selectSql, label).headTry(_ bitVec DataTable.content)

  def put(label: String, content: Bytes): Unit = {
    // Insert and then update because of INSERT IGNORE
    db.change(DataTable.newSql, label, content)
    db.change(DataTable.updSql, content, label)
  }

  // StorageFormat

  def putFormat(format: StorageFormat): Unit = put(LABEL_FORMAT, format.toJson.compactPrint getBytes "UTF-8")

  def tryGetFormat: Try[StorageFormat] = tryGet(LABEL_FORMAT).map(_.toHex) map to[StorageFormat]

  // HostedChannelBranding

  def putBranding(nodeId: PublicKey, branding: HostedChannelBranding): Unit = {
    val hostedChannelBranding = hostedChannelBrandingCodec.encode(branding).require.toByteArray
    put(LABEL_BRANDING_PREFIX + nodeId.toString, hostedChannelBranding)
  }

  def tryGetBranding(nodeId: PublicKey): Try[HostedChannelBranding] =
    tryGet(LABEL_BRANDING_PREFIX + nodeId.toString) map { rawHostedChannelBranding =>
      hostedChannelBrandingCodec.decode(rawHostedChannelBranding).require.value
    }

  // SwapInState

  def putSwapInState(nodeId: PublicKey, state: SwapInState): Unit = {
    val swapInState = swapInStateCodec.encode(state).require.toByteArray
    put(LABEL_SWAP_IN_STATE_PREFIX + nodeId.toString, swapInState)
  }

  def tryGetSwapInState(nodeId: PublicKey): Try[SwapInStateExt] =
    tryGet(LABEL_SWAP_IN_STATE_PREFIX + nodeId.toString) map { rawSwapInState =>
      SwapInStateExt(swapInStateCodec.decode(rawSwapInState).require.value, nodeId)
    }

  // WalletDb

  override def persist(data: PersistentData): Unit = put(LABEL_ELECTRUM_DATA, persistentDataCodec.encode(data).require.toByteArray)

  override def readPersistentData: Option[PersistentData] = tryGet(LABEL_ELECTRUM_DATA).map(persistentDataCodec.decode).map(_.require.value).toOption

  // HeadersDb

  override def addHeaders(startHeight: Int, headers: Seq[BlockHeader] = Nil): Unit = db txWrap {
    for (Tuple2(header, idx) <- headers.zipWithIndex) db.change(ElectrumHeadersTable.table, startHeight + idx: JInt, header.hash.toHex, BlockHeader.write(header).toArray)
  }

  override def getHeader(height: Int): Option[BlockHeader] =
    db.select(ElectrumHeadersTable.selectHeaderByHeightSql, height.toString).headTry { rc =>
      BlockHeader.read(rc bytes ElectrumHeadersTable.header)
    }.toOption

  override def getHeader(blockHash: ByteVector32): Option[HeightAndHeader] =
    db.select(ElectrumHeadersTable.selectHeaderByHeightSql, blockHash.toHex).headTry { rc =>
      val header = BlockHeader.read(rc bytes ElectrumHeadersTable.header)
      val height = rc int ElectrumHeadersTable.height
      (height, header)
    }.toOption

  override def getHeaders(startHeight: Int, maxCount: Int): Seq[BlockHeader] =
    db.select(ElectrumHeadersTable.selectHeadersSql, startHeight.toString).iterable { rc =>
      BlockHeader.read(rc bytes ElectrumHeadersTable.header)
    }.toList

  override def getTip: Option[HeightAndHeader] =
    db.select(ElectrumHeadersTable.selectTipSql).headTry { rc =>
      val header = BlockHeader.read(rc bytes ElectrumHeadersTable.header)
      val height = rc int ElectrumHeadersTable.height
      (height, header)
    }.toOption
}
