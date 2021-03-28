package immortan.sqlite

import java.lang.{Long => JLong}
import immortan.KeysendRequestInfo
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.payment.PaymentRequest


class SQLiteKeysendRequests(db: DBInterface) {
  def addNew(ksPr: PaymentRequest, description: String, groupId: ByteVector32): Unit =
    db.change(KeysendRequestsTable.newSql, PaymentRequest.write(ksPr), description,
      groupId.toHex, 0L: JLong, 0L: JLong, System.currentTimeMillis: JLong)

  def updateExisting(amountIncrease: MilliSatoshi, groupId: ByteVector32): Unit =
    db.change(KeysendRequestsTable.updSql, amountIncrease.toLong: JLong,
      System.currentTimeMillis: JLong, groupId.toHex)

  def listRecent(limit: Int): RichCursor = db.select(KeysendRequestsTable.selectRecentSql, limit.toString)

  def toKeysendRequestInfo(rc: RichCursor): KeysendRequestInfo =
    KeysendRequestInfo(rc string KeysendRequestsTable.ksPr, rc string KeysendRequestsTable.description, ByteVector32.fromValidHex(rc string KeysendRequestsTable.groupId),
      MilliSatoshi(rc long KeysendRequestsTable.totalAmount), rc long KeysendRequestsTable.totalPayments, rc long KeysendRequestsTable.lastStamp)
}
