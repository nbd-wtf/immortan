package immortan.sqlite

import immortan.sqlite.SQLiteHtlcInfo._
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.CltvExpiry
import immortan.HtlcInfoTable


object SQLiteHtlcInfo {
  case class CltvAndPaymentHash(paymentHash: ByteVector32, cltvExpiry: CltvExpiry)
}

class SQLiteHtlcInfo(db: DBInterface) {
  def put(channelId: ByteVector32, commitNumber: Int, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit =
    db.change(HtlcInfoTable.newSql, channelId.toHex, commitNumber: java.lang.Integer, paymentHash.toArray, cltvExpiry.toLong: java.lang.Long)

  def allForChan(channelId: ByteVector32, commitNumer: Int): Iterable[CltvAndPaymentHash] =
    db.select(HtlcInfoTable.selectAllSql, channelId.toHex, commitNumer.toString).iterable { rc =>
      val paymentHash = ByteVector32(rc byteVec HtlcInfoTable.paymentHash)
      val cltvExpiry = CltvExpiry(rc int HtlcInfoTable.cltvExpiry)
      CltvAndPaymentHash(paymentHash, cltvExpiry)
    }
}
