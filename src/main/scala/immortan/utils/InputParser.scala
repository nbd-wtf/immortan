package immortan.utils

import scala.util.{Failure, Try}
import immortan.{LNParams, RemoteNodeInfo}
import immortan.utils.InputParser.{lightning, lnPayReq}
import fr.acinq.eclair.payment.PaymentRequest
import scala.util.matching.UnanchoredRegex
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.NodeAddress
import fr.acinq.eclair.MilliSatoshi
import org.bitcoinj.uri.BitcoinURI
import scodec.bits.ByteVector
import immortan.utils.uri.Uri


object InputParser {
  var value: Any = new String
  case object DoNotEraseRecordedValue
  type Checker = PartialFunction[Any, Any]

  def checkAndMaybeErase(fun: Checker): Unit = fun(value) match {
    case DoNotEraseRecordedValue => // Do nothing, value is retained
    case _ => value = null // Erase recorded value
  }

  private[this] val prefixes = PaymentRequest.prefixes.values mkString "|"
  private[this] val lnUrl = s"(?im).*?(lnurl)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
  private[this] val shortNodeLink = "([a-fA-F0-9]{66})@([a-zA-Z0-9:.\\-_]+)".r.unanchored
  val nodeLink: UnanchoredRegex = "([a-fA-F0-9]{66})@([a-zA-Z0-9:.\\-_]+):([0-9]+)".r.unanchored
  val lnPayReq: UnanchoredRegex = s"(?im).*?($prefixes)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
  val lightning: String = "lightning:"
  val bitcoin: String = "bitcoin:"

  def bitcoinUri(bitcoinUriLink: String): BitcoinURI = {
    val bitcoinURI = new BitcoinURI(LNParams.jParams, bitcoinUriLink)
    require(null != bitcoinURI.getAddress, "No address detected")
    bitcoinURI
  }

  def parse(rawInput: String): Any = rawInput take 2880 match {
    case uriLink if uriLink.startsWith(bitcoin) => bitcoinUri(uriLink)
    case uriLink if uriLink.startsWith(lightning) => PaymentRequestExt.fromUri(uriLink)
    case uriLink if uriLink.startsWith(bitcoin.toUpperCase) => bitcoinUri(uriLink.toLowerCase)
    case uriLink if uriLink.startsWith(lightning.toUpperCase) => PaymentRequestExt.fromUri(uriLink.toLowerCase)
    case nodeLink(key, host, port) => RemoteNodeInfo(PublicKey.fromBin(ByteVector fromValidHex key), NodeAddress.fromParts(host, port.toInt), key take 16 grouped 4 mkString "-")
    case shortNodeLink(key, host) => RemoteNodeInfo(PublicKey.fromBin(ByteVector fromValidHex key), NodeAddress.fromParts(host, port = 9735), key take 16 grouped 4 mkString "-")
    case lnPayReq(prefix, data) => PaymentRequestExt(uri = Failure(new RuntimeException), PaymentRequest.read(s"$prefix$data"), s"$prefix$data")
    case lnUrl(prefix, data) => LNUrl.fromBech32(s"$prefix$data")
    case _ => bitcoinUri(s"bitcoin:$rawInput")
  }
}

object PaymentRequestExt {
  def fromUri(raw: String): PaymentRequestExt = {
    val invoiceWithoutPrefix = raw.split(':').drop(1).mkString
    val lnPayReq(invoicePrefix, invoiceData) = invoiceWithoutPrefix
    val uri = Try(Uri parse s"$lightning//$invoiceWithoutPrefix")
    val pr = PaymentRequest.read(s"$invoicePrefix$invoiceData")
    PaymentRequestExt(uri, pr, s"$invoicePrefix$invoiceData")
  }
}

case class PaymentRequestExt(uri: Try[Uri], pr: PaymentRequest, raw: String) {
  lazy val splits: List[MilliSatoshi] = uri.map(_.getQueryParameter("splits").split(',').toList.map(_.toLong) map MilliSatoshi.apply) getOrElse Nil
}