package immortan.sqlite

import java.lang.{Long => JLong}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi
import immortan.KeysendReceivedInfo
import scodec.bits.ByteVector
import scala.util.Try


case class KeysendReceivedSummary(received: MilliSatoshi, count: Long)

class SQLiteKeysendReceived(db: DBInterface) {
  def findByGroupId(groupId: ByteVector32): Iterable[KeysendReceivedInfo] = db.select(KeysendReceivedTable.selectByGroupIdSql, groupId.toHex).iterable(toKeysendReceivedInfo)

  def addNew(preimage: ByteVector32, groupId: ByteVector32, senderPubKey: Option[PublicKey], senderSignature: Option[ByteVector64], senderMessage: Option[String], sigOK: Boolean, amount: MilliSatoshi): Unit =
    db.change(KeysendReceivedTable.newSql, preimage.toHex, groupId.toHex, senderPubKey.map(_.value.toHex).getOrElse(new String), senderSignature.map(_.toHex).getOrElse(new String),
      senderMessage.getOrElse(new String), if (sigOK) 1L: JLong else 0L: JLong, amount.toLong: JLong, System.currentTimeMillis: JLong)

  def summary: Try[KeysendReceivedSummary] = db.select(KeysendReceivedTable.selectSummarySql).headTry { rc =>
    KeysendReceivedSummary(received = MilliSatoshi(rc long 0), count = rc long 1)
  }

  def toPubKey(rc: RichCursor): Option[PublicKey] = for {
    rawPubKey <- Option(rc string KeysendReceivedTable.senderPubKey)
  } yield PublicKey.fromBin(ByteVector.fromValidHex(rawPubKey), checkValid = false)

  def toKeysendReceivedInfo(rc: RichCursor): KeysendReceivedInfo =
    KeysendReceivedInfo(ByteVector32.fromValidHex(rc string KeysendReceivedTable.preimage), ByteVector32.fromValidHex(rc string KeysendReceivedTable.groupId),
      toPubKey(rc), Option(rc string KeysendReceivedTable.senderSignature).map(ByteVector64.fromValidHex), Option(rc string KeysendReceivedTable.senderMessage),
      rc int KeysendReceivedTable.signatureChecksOut, MilliSatoshi(rc long KeysendReceivedTable.amount), rc long KeysendReceivedTable.stamp)
}
