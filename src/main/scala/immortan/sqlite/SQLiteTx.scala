package immortan.sqlite

import spray.json._
import immortan.utils.ImplicitJsonFormats._
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._
import immortan.{TxDescription, TxInfo}
import java.lang.{Long => JLong}

import immortan.crypto.Tools.Fiat2Btc
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import scala.util.Try


case class TxSummary(fees: MilliSatoshi, received: MilliSatoshi, sent: MilliSatoshi, count: Long)

class SQLiteTx(db: DBInterface) {
  def updDoubleSpent(txid: ByteVector32): Unit =
    db.change(TxTable.updDoubleSpentSql, txid.toHex, 1L: JLong)

  def updConfidence(event: TransactionConfidenceChanged): Unit = db txWrap {
    db.change(TxTable.updCompletedAtSql, event.txid.toHex, event.msecs: JLong)
    db.change(TxTable.updDepthSql, event.txid.toHex, event.depth: JLong)
  }

  def listRecentTxs: RichCursor = db.select(TxTable.selectRecentSql)

  def txSummary: Try[TxSummary] = db.select(TxTable.selectSummarySql).headTry { rc =>
    TxSummary(fees = MilliSatoshi(rc long 0), received = MilliSatoshi(rc long 1), sent = MilliSatoshi(rc long 2), count = rc long 3)
  }

  def putTx(event: TransactionReceived, description: TxDescription, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc): Unit =
    db.change(TxTable.newSql, event.tx.txid.toHex, event.depth: JLong, event.received.toLong: JLong, event.sent.toLong: JLong, event.feeOpt.map(_.toLong: JLong).getOrElse(0L: JLong),
      event.msecs: JLong /* SEEN */, event.msecs: JLong /* COMPLETED */, description.toJson.compactPrint, balanceSnap.toLong: JLong, fiatRateSnap.toJson.compactPrint,
      if (event.received >= event.sent) 1L: JLong else 0L: JLong /* INCOMING? */, 0L: JLong /* NOT DOUBLE SPENT */)

  def toTxInfo(rc: RichCursor): TxInfo =
    TxInfo(txidString = rc string TxTable.txid, depth = rc long TxTable.depth, receivedMsat = MilliSatoshi(rc long TxTable.receivedMsat),
      sentMsat = MilliSatoshi(rc long TxTable.sentMsat), feeMsat = MilliSatoshi(rc long TxTable.feeMsat), seenAt = rc long TxTable.firstSeen,
      completedAt = rc long TxTable.completedAt, descriptionString = rc string TxTable.description, balanceSnapshot = MilliSatoshi(rc long TxTable.balanceMsat),
      fiatRatesString = rc string TxTable.fiatRates, incoming = rc long TxTable.incoming, doubleSpent = rc long TxTable.doubleSpent)
}

abstract class SQLiteTxCached(db: DBInterface) extends SQLiteTx(db) {
  override def putTx(event: TransactionReceived, description: TxDescription, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc): Unit = {
    super.putTx(event, description, balanceSnap, fiatRateSnap)
    invalidateCache
  }

  override def updDoubleSpent(txid: ByteVector32): Unit = {
    super.updDoubleSpent(txid)
    invalidateCache
  }

  def invalidateCache: Unit
}