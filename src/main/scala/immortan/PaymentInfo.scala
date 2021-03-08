package immortan

import immortan.utils.ImplicitJsonFormats._
import immortan.crypto.Tools.{Bytes, Fiat2Btc}
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.bitcoin.Crypto.PublicKey
import scodec.bits.ByteVector
import immortan.utils.uri.Uri
import immortan.utils.LNUrl


object PaymentInfo {
  final val NOT_SENDABLE_LOW_FUNDS = 1
  final val NOT_SENDABLE_IN_FLIGHT = 2
  final val NOT_SENDABLE_SUCCESS = 3
  final val SENDABLE = 0
}

object PaymentStatus {
  final val INIT = "state-init"
  final val PENDING = "state-pending"
  final val ABORTED = "state-aborted"
  final val SUCCEEDED = "state-succeeded"
}

case class PaymentInfo(prString: String, preimageString: String, status: String, stamp: Long, descriptionString: String, actionString: String,
                       paymentHashString: String, received: MilliSatoshi, sent: MilliSatoshi, fee: MilliSatoshi, balanceSnapshot: MilliSatoshi,
                       fiatRatesString: String, chainFee: MilliSatoshi, incoming: Long) {

  val isIncoming: Boolean = 1 == incoming
  lazy val pr: PaymentRequest = PaymentRequest.read(prString)
  lazy val amountOrMin: MilliSatoshi = pr.amount.getOrElse(LNParams.minPayment)
  lazy val preimage: ByteVector32 = ByteVector32(ByteVector fromValidHex preimageString)
  lazy val paymentHash: ByteVector32 = ByteVector32(ByteVector fromValidHex paymentHashString)
  lazy val description: PaymentDescription = to[PaymentDescription](descriptionString)
  lazy val fiatRateSnapshot: Fiat2Btc = to[Fiat2Btc](fiatRatesString)
  lazy val action: PaymentAction = to[PaymentAction](actionString)
}

// Payment actions

sealed trait PaymentAction {
  val domain: Option[String]
  val finalMessage: String
}

case class MessageAction(domain: Option[String], message: String) extends PaymentAction {
  val finalMessage = s"<br>${message take 144}"
}

case class UrlAction(domain: Option[String], description: String, url: String) extends PaymentAction {
  val finalMessage = s"<br>${description take 144}<br><br><font color=#0000FF><tt>$url</tt></font><br>"
  require(domain.forall(url.contains), "Payment action domain mismatch")
  val uri: Uri = LNUrl.checkHost(url)
}

case class AESAction(domain: Option[String], description: String, ciphertext: String, iv: String) extends PaymentAction {
  val ciphertextBytes: Bytes = ByteVector.fromValidBase64(ciphertext).take(1024 * 4).toArray // up to ~2kb of encrypted data
  val ivBytes: Bytes = ByteVector.fromValidBase64(iv).take(24).toArray // 16 bytes
  val finalMessage = s"<br>${description take 144}"
}

// Payment descriptions

sealed trait PaymentDescription { val invoiceText: String }

case class PlainDescription(invoiceText: String) extends PaymentDescription

case class PlainMetaDescription(invoiceText: String, meta: String) extends PaymentDescription

case class SwapInDescription(invoiceText: String, txid: String, internalId: Long, nodeId: PublicKey) extends PaymentDescription

case class SwapOutDescription(invoiceText: String, btcAddress: String, chainFee: Satoshi, nodeId: PublicKey) extends PaymentDescription

// Relayed preimages

case class RelayedPreimageInfo(paymentHashString: String, preimageString: String, relayed: MilliSatoshi, earned: MilliSatoshi, stamp: Long, fast: Long) {
  lazy val paymentHash: ByteVector32 = ByteVector32(ByteVector fromValidHex paymentHashString)
  lazy val preimage: ByteVector32 = ByteVector32(ByteVector fromValidHex preimageString)
  val isFast: Boolean = fast == 1L
}

// Tx descriptions

case class TxInfo(txidString: String, depth: Long, receivedMsat: MilliSatoshi, sentMsat: MilliSatoshi,
                  feeMsat: MilliSatoshi, seenAt: Long, completedAt: Long, descriptionString: String,
                  balanceSnapshot: MilliSatoshi, fiatRatesString: String,
                  incoming: Long, doubleSpent: Long) {

  val isIncoming: Boolean = 1L == incoming
  val isDoubleSpent: Boolean = 1L == doubleSpent
  val isConfirmed: Boolean = depth >= LNParams.minDepthBlocks
  lazy val fiatRateSnapshot: Fiat2Btc = to[Fiat2Btc](fiatRatesString)
  lazy val description: TxDescription = to[TxDescription](descriptionString)
  lazy val txid: ByteVector32 = ByteVector32(ByteVector fromValidHex txidString)
}

sealed trait TxDescription { val txid: String }

case class PlainTxDescription(txid: String) extends TxDescription

sealed trait ChanTxDescription extends TxDescription {
  val remoteNodeId: PublicKey = PublicKey(ByteVector32 fromValidHex nodeId)
  val shortChanId: ShortChannelId = ShortChannelId(sid)
  def nodeId: String
  def sid: Long
}

case class ChanFundingTxDescription(txid: String, nodeId: String, sid: Long) extends TxDescription

case class CommitClaimTxDescription(txid: String, nodeId: String, sid: Long) extends TxDescription

case class HtlcClaimTxDescription(txid: String, nodeId: String, sid: Long) extends TxDescription

case class PenaltyTxDescription(txid: String, nodeId: String, sid: Long) extends TxDescription