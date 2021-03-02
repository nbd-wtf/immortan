package immortan.sqlite

import immortan._
import spray.json._
import fr.acinq.eclair._
import immortan.utils.ImplicitJsonFormats._

import java.lang.{Long => JLong}
import fr.acinq.eclair.wire.UpdateFulfillHtlc
import immortan.PaymentInfo.RevealedParts
import fr.acinq.bitcoin.Crypto.PublicKey
import immortan.crypto.Tools.Fiat2Btc
import fr.acinq.bitcoin.ByteVector32
import scala.util.Try


case class RelayedSummary(relayed: MilliSatoshi, earned: MilliSatoshi, count: Long)

case class PaidSummary(fees: MilliSatoshi, received: MilliSatoshi, sent: MilliSatoshi, count: Long)

class SQlitePaymentBag(db: DBInterface) extends PaymentBag {
  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = db.select(PaymentTable.selectOneSql, paymentHash.toHex).headTry(toPaymentInfo).toOption

  def getRelayedPreimageInfo(paymentHash: ByteVector32): Option[RelayedPreimageInfo] = db.select(RelayPreimageTable.selectByHashSql, paymentHash.toHex).headTry(toRelayedPreimageInfo).toOption

  def addSearchablePayment(search: String, paymentHash: ByteVector32): Unit = db.change(PaymentTable.newVirtualSql, search, paymentHash.toHex)

  def searchPayments(rawSearchQuery: String): RichCursor = db.search(PaymentTable.searchSql, rawSearchQuery)

  def listRecentPayments: RichCursor = db.select(PaymentTable.selectRecentSql)

  def listRecentRelays: RichCursor = db.select(RelayPreimageTable.selectRecentSql)

  def abortOutgoing(paymentHash: ByteVector32): Unit = db.change(PaymentTable.updStatusSql, PaymentStatus.ABORTED, paymentHash.toHex)

  def addRelayedPreimageInfo(paymentHash: ByteVector32, preimage: ByteVector32, stamp: Long, fromNodeIdOpt: Option[PublicKey], relayed: MilliSatoshi, earned: MilliSatoshi): Unit =
    db.change(RelayPreimageTable.newSql, paymentHash.toHex, preimage.toHex, stamp: JLong, relayed.toLong: JLong, earned.toLong: JLong, fromNodeIdOpt.map(_.toString) getOrElse new String)

  def updOkOutgoing(upd: UpdateFulfillHtlc, fee: MilliSatoshi): Unit =
    db.change(PaymentTable.updOkOutgoingSql, upd.paymentPreimage.toHex,
      fee.toLong: JLong, upd.paymentHash.toHex)

  def updOkIncoming(revealedParts: RevealedParts, paymentHash: ByteVector32): Unit =
    db.change(PaymentTable.updOkIncomingSql, revealedParts.map(_.amount).sum.toLong: JLong,
      System.currentTimeMillis: JLong, revealedParts.toJson.compactPrint, paymentHash.toHex)

  def replaceOutgoingPayment(prex: PaymentRequestExt, description: PaymentDescription, action: Option[PaymentAction],
                             finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc, chainFee: MilliSatoshi): Unit =
    db txWrap {
      db.change(PaymentTable.deleteSql, prex.pr.paymentHash.toHex)
      db.change(PaymentTable.newSql, prex.raw, ChannelMaster.NO_PREIMAGE.toHex, PaymentStatus.PENDING, System.currentTimeMillis: JLong, description.toJson.compactPrint,
        action.map(_.toJson.compactPrint).getOrElse(new String), prex.pr.paymentHash.toHex, 0L: JLong /* RECEIVED = 0 MSAT */, finalAmount.toLong: JLong /* SENT IS KNOWN */,
        0L: JLong /* FEE IS UNCERTAIN YET */, balanceSnap.toLong: JLong, fiatRateSnap.toJson.compactPrint, chainFee.toLong: JLong, 0: java.lang.Integer /* INCOMING = 0 */,
        List.empty[RevealedPart].toJson.compactPrint /* NO REVELAED PARTS FOR OUTGOING */)
    }

  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription,
                             balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc, chainFee: MilliSatoshi): Unit =
    db txWrap {
      db.change(PaymentTable.deleteSql, prex.pr.paymentHash.toHex)
      db.change(PaymentTable.newSql, prex.raw, preimage.toHex, PaymentStatus.PENDING, System.currentTimeMillis: JLong, description.toJson.compactPrint,
        new String /* NO ACTION */, prex.pr.paymentHash.toHex, prex.pr.amount.getOrElse(0L.msat).toLong: JLong /* MUST COME FROM PR! NO AMOUNT IF RECEIVED = 0 */,
        0L: JLong /* SENT = 0 MSAT, NOTHING TO SEND */, 0L: JLong /* NO FEE FOR INCOMING PAYMENT */, balanceSnap.toLong: JLong, fiatRateSnap.toJson.compactPrint,
        chainFee.toLong: JLong, 1: java.lang.Integer /* INCOMING = 1 */, List.empty[RevealedPart].toJson.compactPrint /* NO REVELAED PARTS YET */)
    }

  def paidSummary: Try[PaidSummary] = db.select(PaymentTable.selectSummarySql).headTry { rc =>
    PaidSummary(fees = MilliSatoshi(rc long 0), received = MilliSatoshi(rc long 1), sent = MilliSatoshi(rc long 2), count = rc long 3)
  }

  def relayedSummary: Try[RelayedSummary] = db.select(RelayPreimageTable.selectSummarySql).headTry { rc =>
    RelayedSummary(relayed = MilliSatoshi(rc long 0), earned = MilliSatoshi(rc long 1), count = rc long 2)
  }

  def toPaymentInfo(rc: RichCursor): PaymentInfo =
    PaymentInfo(prString = rc string PaymentTable.pr, preimageString = rc string PaymentTable.preimage,
      status = rc string PaymentTable.status, stamp = rc long PaymentTable.stamp, descriptionString = rc string PaymentTable.description,
      actionString = rc string PaymentTable.action, paymentHashString = rc string PaymentTable.hash, received = MilliSatoshi(rc long PaymentTable.receivedMsat),
      sent = MilliSatoshi(rc long PaymentTable.sentMsat), fee = MilliSatoshi(rc long PaymentTable.feeMsat), balanceSnapshot = MilliSatoshi(rc long PaymentTable.balanceMsat),
      fiatRatesString = rc string PaymentTable.fiatRates, chainFee = MilliSatoshi(rc long PaymentTable.chainFee), revealedPartsString = rc string PaymentTable.revealedParts,
      incoming = rc long PaymentTable.incoming)

  def toRelayedPreimageInfo(rc: RichCursor): RelayedPreimageInfo =
    RelayedPreimageInfo(paymentHashString = rc string RelayPreimageTable.hash, preimageString = rc string RelayPreimageTable.preimage,
      fromNodeIdString = rc string RelayPreimageTable.fromNodeId, relayed = MilliSatoshi(rc long RelayPreimageTable.relayed),
      earned = MilliSatoshi(rc long RelayPreimageTable.earned), stamp = rc long RelayPreimageTable.stamp)
}