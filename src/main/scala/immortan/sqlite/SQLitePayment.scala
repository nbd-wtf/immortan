package immortan.sqlite

import immortan._
import spray.json._
import fr.acinq.eclair._
import immortan.utils.ImplicitJsonFormats._
import java.lang.{Long => JLong}

import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.eclair.wire.FullPaymentTag
import immortan.utils.PaymentRequestExt
import immortan.crypto.Tools.Fiat2Btc
import fr.acinq.bitcoin.ByteVector32
import scala.util.Try


case class RelaySummary(relayed: MilliSatoshi, earned: MilliSatoshi, count: Long)

case class PaymentSummary(fees: MilliSatoshi, received: MilliSatoshi, sent: MilliSatoshi, count: Long)

class SQlitePaymentBag(db: DBInterface, preimageDb: DBInterface) extends PaymentBag {
  def getPaymentInfo(paymentHash: ByteVector32): Try[PaymentInfo] = db.select(PaymentTable.selectByHashSql, paymentHash.toHex).headTry(toPaymentInfo)

  def getPreimage(hash: ByteVector32): Try[ByteVector32] = preimageDb.select(PreimageTable.selectByHashSql, hash.toHex).headTry(_ string PreimageTable.preimage).map(ByteVector32.fromValidHex)

  def addSearchablePayment(search: String, paymentHash: ByteVector32): Unit = db.change(PaymentTable.newVirtualSql, search, paymentHash.toHex)

  def searchPayments(rawSearchQuery: String): RichCursor = db.search(PaymentTable.searchSql, rawSearchQuery)

  def listRecentPayments: RichCursor = db.select(PaymentTable.selectRecentSql)

  def listRecentRelays: RichCursor = db.select(RelayTable.selectRecentSql)

  def addPreimage(paymentHash: ByteVector32, preimage: ByteVector32): Unit = preimageDb.change(PreimageTable.newSql, paymentHash.toHex, preimage.toHex)

  def updAbortedOutgoing(paymentHash: ByteVector32): Unit = db.change(PaymentTable.updStatusSql, PaymentStatus.ABORTED, paymentHash.toHex)

  def updOkOutgoing(fulfill: RemoteFulfill, fee: MilliSatoshi): Unit = db.change(PaymentTable.updOkOutgoingSql, fulfill.preimage.toHex, fee.toLong: JLong, fulfill.ourAdd.paymentHash.toHex)

  def updOkIncoming(receivedAmount: MilliSatoshi, paymentHash: ByteVector32): Unit = db.change(PaymentTable.updOkIncomingSql, receivedAmount.toLong: JLong, System.currentTimeMillis: JLong, paymentHash.toHex)

  def addRelayedPreimageInfo(fullTag: FullPaymentTag, preimage: ByteVector32, relayed: MilliSatoshi, earned: MilliSatoshi): Unit =
    db.change(RelayTable.newSql, fullTag.paymentHash.toHex, fullTag.paymentSecret.toHex, preimage.toHex, System.currentTimeMillis: JLong, relayed.toLong: JLong, earned.toLong: JLong)

  def replaceOutgoingPayment(prex: PaymentRequestExt, description: PaymentDescription, action: Option[PaymentAction],
                             finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc, chainFee: MilliSatoshi): Unit =
    db txWrap {
      db.change(PaymentTable.deleteSql, prex.pr.paymentHash.toHex)
      db.change(PaymentTable.newSql, prex.raw, ChannelMaster.NO_PREIMAGE.toHex, PaymentStatus.PENDING, System.currentTimeMillis: JLong, description.toJson.compactPrint,
        action.map(_.toJson.compactPrint).getOrElse(new String), prex.pr.paymentHash.toHex, 0L: JLong /* RECEIVED = 0 MSAT */, finalAmount.toLong: JLong /* SENT IS KNOWN */,
        0L: JLong /* FEE IS UNCERTAIN YET */, balanceSnap.toLong: JLong, fiatRateSnap.toJson.compactPrint, chainFee.toLong: JLong, 0: java.lang.Integer /* INCOMING = 0 */)
    }

  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription,
                             balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc, chainFee: MilliSatoshi): Unit =
    db txWrap {
      db.change(PaymentTable.deleteSql, prex.pr.paymentHash.toHex)
      db.change(PaymentTable.newSql, prex.raw, preimage.toHex, PaymentStatus.PENDING, System.currentTimeMillis: JLong, description.toJson.compactPrint,
        new String /* NO ACTION */, prex.pr.paymentHash.toHex, prex.pr.amount.getOrElse(0L.msat).toLong: JLong /* MUST COME FROM PR! NO AMOUNT IF RECEIVED = 0 */,
        0L: JLong /* SENT = 0 MSAT, NOTHING TO SEND */, 0L: JLong /* NO FEE FOR INCOMING PAYMENT */, balanceSnap.toLong: JLong, fiatRateSnap.toJson.compactPrint,
        chainFee.toLong: JLong, 1: java.lang.Integer /* INCOMING = 1 */)
    }

  def paymentSummary: Try[PaymentSummary] = db.select(PaymentTable.selectSummarySql).headTry { rc =>
    PaymentSummary(fees = MilliSatoshi(rc long 0), received = MilliSatoshi(rc long 1), sent = MilliSatoshi(rc long 2), count = rc long 3)
  }

  def relaySummary: Try[RelaySummary] = db.select(RelayTable.selectSummarySql).headTry { rc =>
    RelaySummary(relayed = MilliSatoshi(rc long 0), earned = MilliSatoshi(rc long 1), count = rc long 2)
  }

  def toPaymentInfo(rc: RichCursor): PaymentInfo =
    PaymentInfo(prString = rc string PaymentTable.pr, preimageString = rc string PaymentTable.preimage,
      status = rc string PaymentTable.status, stamp = rc long PaymentTable.stamp, descriptionString = rc string PaymentTable.description,
      actionString = rc string PaymentTable.action, paymentHashString = rc string PaymentTable.hash, received = MilliSatoshi(rc long PaymentTable.receivedMsat),
      sent = MilliSatoshi(rc long PaymentTable.sentMsat), fee = MilliSatoshi(rc long PaymentTable.feeMsat), balanceSnapshot = MilliSatoshi(rc long PaymentTable.balanceMsat),
      fiatRatesString = rc string PaymentTable.fiatRates, chainFee = MilliSatoshi(rc long PaymentTable.chainFee), incoming = rc long PaymentTable.incoming)

  def toRelayedPreimageInfo(rc: RichCursor): RelayedPreimageInfo =
    RelayedPreimageInfo(paymentHashString = rc string RelayTable.hash, preimageString = rc string RelayTable.preimage,
      relayed = MilliSatoshi(rc long RelayTable.relayed), earned = MilliSatoshi(rc long RelayTable.earned), stamp = rc long RelayTable.stamp)
}

abstract class SQlitePaymentBagCached(db: DBInterface, preimageDb: DBInterface) extends SQlitePaymentBag(db, preimageDb) {
  override def addRelayedPreimageInfo(fullTag: FullPaymentTag, preimage: ByteVector32, relayed: MilliSatoshi, earned: MilliSatoshi): Unit = {
    super.addRelayedPreimageInfo(fullTag, preimage, relayed, earned)
    invalidateRelayCache
  }

  override def updOkOutgoing(fulfill: RemoteFulfill, fee: MilliSatoshi): Unit = {
    super.updOkOutgoing(fulfill, fee)
    invalidatePaymentCache
  }

  override def updOkIncoming(receivedAmount: MilliSatoshi, paymentHash: ByteVector32): Unit = {
    super.updOkIncoming(receivedAmount, paymentHash)
    invalidatePaymentCache
  }

  def invalidatePaymentCache: Unit
  def invalidateRelayCache: Unit
}