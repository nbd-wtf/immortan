package immortan.sqlite

import immortan._
import spray.json._
import fr.acinq.eclair._
import immortan.utils.ImplicitJsonFormats._

import java.lang.{Long => JLong}
import fr.acinq.eclair.wire.{UpdateAddHtlc, UpdateFulfillHtlc}
import fr.acinq.bitcoin.Crypto.PublicKey
import immortan.crypto.Tools.Fiat2Btc
import fr.acinq.bitcoin.ByteVector32
import scala.util.Try


case class SentToNodeSummary(fees: MilliSatoshi, sent: MilliSatoshi, count: Long)

case class TotalStatSummary(fees: MilliSatoshi, received: MilliSatoshi, sent: MilliSatoshi, count: Long)

case class TotalStatSummaryExt(summary: Option[TotalStatSummary], from: Long, to: Long)

class SQlitePaymentBag(db: DBInterface) extends PaymentBag with PaymentDBUpdater {
  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = db.select(PaymentTable.selectOneSql, paymentHash.toHex).headTry(toPaymentInfo).toOption

  def addSearchablePayment(search: String, paymentHash: ByteVector32): Unit = db.change(PaymentTable.newVirtualSql, search, paymentHash.toHex)

  def searchPayments(rawSearchQuery: String): RichCursor = db.search(PaymentTable.searchSql, rawSearchQuery)

  def listRecentPayments: RichCursor = db.select(PaymentTable.selectRecentSql)

  def updOkOutgoing(upd: UpdateFulfillHtlc, fee: MilliSatoshi): Unit = db.change(PaymentTable.updOkOutgoingSql, upd.paymentPreimage.toHex, fee.toLong: JLong, upd.paymentHash.toHex)

  def updStatusIncoming(add: UpdateAddHtlc, status: String): Unit = db.change(PaymentTable.updParamsIncomingSql, status, add.amountMsat.toLong: JLong, System.currentTimeMillis: JLong, add.paymentHash.toHex)

  def abortPayment(paymentHash: ByteVector32): Unit = db.change(PaymentTable.updStatusSql, PaymentStatus.ABORTED, paymentHash.toHex)

  def replaceOutgoingPayment(nodeId: PublicKey, prex: PaymentRequestExt, description: PaymentDescription, action: Option[PaymentAction],
                             finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc, chainFee: MilliSatoshi): Unit =
    db txWrap {
      db.change(PaymentTable.deleteSql, prex.pr.paymentHash.toHex)
      db.change(PaymentTable.newSql, nodeId.toString, prex.raw, ByteVector32.Zeroes.toHex, PaymentStatus.PENDING, System.currentTimeMillis: JLong, description.toJson.compactPrint,
        action.map(_.toJson.compactPrint).getOrElse(new String), prex.pr.paymentHash.toHex, 0L: JLong /* RECEIVED = 0 MSAT, OUTGOING */, finalAmount.toLong: JLong /* SENT IS KNOWN */,
        0L: JLong /* FEE IS UNCERTAIN YET */, balanceSnap.toLong: JLong, fiatRateSnap.toJson.compactPrint, chainFee.toLong: JLong, 0: java.lang.Integer /* INCOMING = 0 */, new String)
    }

  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription,
                             balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc, chainFee: MilliSatoshi): Unit =
    db txWrap {
      val finalReceived = prex.pr.amount.map(_.toLong: JLong).getOrElse(0L: JLong)
      val finalStatus = if (prex.pr.amount.isDefined) PaymentStatus.PENDING else PaymentStatus.HIDDEN

      db.change(PaymentTable.deleteSql, prex.pr.paymentHash.toHex)
      db.change(PaymentTable.newSql, invalidPubKey.toString, prex.raw, preimage.toHex, finalStatus, System.currentTimeMillis: JLong, description.toJson.compactPrint,
        new String /* NO ACTION */, prex.pr.paymentHash.toHex, finalReceived /* MUST COME FROM PR! IF RECEIVED = 0 MSAT THEN NO AMOUNT */, 0L: JLong /* SENT = 0 MSAT, NOTHING TO SEND */,
        0L: JLong /* NO FEE FOR INCOMING */, balanceSnap.toLong: JLong, fiatRateSnap.toJson.compactPrint, chainFee.toLong: JLong, 1: java.lang.Integer /* INCOMING = 1 */, new String)
    }

  def toNodeSummary(nodeId: PublicKey): Try[SentToNodeSummary] = db.select(PaymentTable.selectToNodeSummarySql, nodeId.toString) headTry { rc =>
    SentToNodeSummary(fees = MilliSatoshi(rc long 0), sent = MilliSatoshi(rc long 1), count = rc long 2)
  }

  def betweenSummary(start: Long, end: Long): Try[TotalStatSummary] = db.select(PaymentTable.selectBetweenSummarySql, start.toString, end.toString) headTry { rc =>
    TotalStatSummary(fees = MilliSatoshi(rc long 0), received = MilliSatoshi(rc long 1), sent = MilliSatoshi(rc long 2), count = rc long 3)
  }

  def toPaymentInfo(rc: RichCursor): PaymentInfo =
    PaymentInfo(payeeNodeIdString = rc string PaymentTable.nodeId, prString = rc string PaymentTable.pr, preimageString = rc string PaymentTable.preimage, status = rc string PaymentTable.status,
      stamp = rc long PaymentTable.stamp, descriptionString = rc string PaymentTable.description, actionString = rc string PaymentTable.action, paymentHashString = rc string PaymentTable.hash,
      received = MilliSatoshi(rc long PaymentTable.receivedMsat), sent = MilliSatoshi(rc long PaymentTable.sentMsat), fee = MilliSatoshi(rc long PaymentTable.feeMsat),
      balanceSnapshot = MilliSatoshi(rc long PaymentTable.balanceMsat), fiatRatesString = rc string PaymentTable.fiatRates,
      chainFee = MilliSatoshi(rc long PaymentTable.chainFee), incoming = rc long PaymentTable.incoming)
}