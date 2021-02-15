package immortan.utils

import immortan.{LNParams, PaymentRequestExt}
import fr.acinq.eclair.payment.PaymentRequest
import scala.util.matching.UnanchoredRegex
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.NodeAddress
import org.bitcoinj.uri.BitcoinURI
import scodec.bits.ByteVector
import immortan.crypto.Tools


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
  private[this] val lnPayReq = s"(?im).*?($prefixes)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
  private[this] val shortNodeLink = "([a-fA-F0-9]{66})@([a-zA-Z0-9:.\\-_]+)".r.unanchored
  val nodeLink: UnanchoredRegex = "([a-fA-F0-9]{66})@([a-zA-Z0-9:.\\-_]+):([0-9]+)".r.unanchored

  def bitcoinUri(bitcoinUriLink: String): BitcoinURI = {
    val bitcoinURI = new BitcoinURI(LNParams.jParams, bitcoinUriLink)
    require(null != bitcoinURI.getAddress, "No address detected")
    bitcoinURI
  }

  def parse(rawInput: String): Any = rawInput take 2880 match {
    case bitcoinUriLink if bitcoinUriLink startsWith "bitcoin" => bitcoinUri(bitcoinUriLink)
    case bitcoinUriLink if bitcoinUriLink startsWith "BITCOIN" => bitcoinUri(bitcoinUriLink.toLowerCase)
    case nodeLink(key, host, port) => Tools.mkNodeAnnouncement(PublicKey.fromBin(ByteVector fromValidHex key), NodeAddress.fromParts(host, port.toInt), key take 16 grouped 4 mkString " ")
    case shortNodeLink(key, host) => Tools.mkNodeAnnouncement(PublicKey.fromBin(ByteVector fromValidHex key), NodeAddress.fromParts(host, port = 9735), key take 16 grouped 4 mkString " ")
    case lnPayReq(prefix, data) => PaymentRequestExt(pr = PaymentRequest.read(s"$prefix$data"), raw = s"$prefix$data")
    case lnUrl(prefix, data) => LNUrl.fromBech32(s"$prefix$data")
    case _ => bitcoinUri(s"bitcoin:$rawInput")
  }
}
