package immortan.utils

import scala.util.matching.{Regex, UnanchoredRegex}
import scala.util.parsing.combinator.RegexParsers
import scala.util.{Failure, Success, Try}
import scodec.bits.ByteVector
import scoin.Crypto.PublicKey
import scoin._
import scoin.ln._

import immortan.{LNParams, RemoteNodeInfo, trimmed}
import immortan.router.Graph.GraphStructure
import immortan.router.RouteCalculation
import immortan.utils.InputParser._
import immortan.utils.uri.Uri

object InputParser {
  var value: Any = new String
  case object DoNotEraseRecordedValue
  type Checker = PartialFunction[Any, Any]

  def checkAndMaybeErase(fun: Checker): Unit = fun(value) match {
    case DoNotEraseRecordedValue => // Do nothing, value is retained
    case _                       => value = null // Erase recorded value
  }

  private[this] val prefixes = Bolt11Invoice.prefixes.values mkString "|"

  private[this] val lnUrl = "(?im).*?(lnurl)([0-9]+[a-z0-9]+)".r.unanchored

  private[this] val lud17 =
    "(lnurlw|lnurlp|lnurlc|keyauth|https?)://(.*)".r.unanchored

  private[this] val shortNodeLink =
    "([a-fA-F0-9]{66})@([a-zA-Z0-9:.\\-_]+)".r.unanchored

  val nodeLink: UnanchoredRegex =
    "([a-fA-F0-9]{66})@([a-zA-Z0-9:.\\-_]+):([0-9]+)".r.unanchored

  val lnPayReq: UnanchoredRegex =
    s"(?im).*?($prefixes)([0-9]{1,}[a-z0-9]+){1}".r.unanchored

  val identifier: Regex =
    "^([a-zA-Z0-9][a-zA-Z0-9\\-_.]*)?[a-zA-Z0-9]@([a-zA-Z0-9][a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9]\\.)+[a-zA-Z0-9][a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9]$".r

  val lightning: String = "lightning:"
  val bitcoin: String = "bitcoin:"

  def recordValue(raw: String): Unit = value = parse(raw)

  def parse(rawInput: String): Any = rawInput take 2880 match {
    case lnUrl(prefix, data) => LNUrl.fromBech32(s"$prefix$data")
    case nodeLink(key, host, port) =>
      RemoteNodeInfo(
        PublicKey.fromBin(ByteVector fromValidHex key),
        NodeAddress.fromParts(host, port.toInt).get,
        host
      )
    case shortNodeLink(key, host) =>
      RemoteNodeInfo(
        PublicKey.fromBin(ByteVector fromValidHex key),
        NodeAddress.fromParts(host, port = 9735).get,
        host
      )
    case lud17(prefix, rest) => {
      val actualPrefix =
        if (rest.endsWith(".onion")) "http" else "https"

      prefix match {
        case "lnurlw"  => LNUrl(s"$actualPrefix://$rest")
        case "lnurlp"  => LNUrl(s"$actualPrefix://$rest")
        case "lnurlc"  => LNUrl(s"$actualPrefix://$rest")
        case "keyauth" => LNUrl(s"$actualPrefix://$rest")
        case "http"    => LNUrl(s"$actualPrefix://$rest")
        case "https"   => LNUrl(s"$actualPrefix://$rest")
      }
    }
    case _ =>
      val withoutSlashes = PaymentRequestExt.removePrefix(rawInput).trim
      val isLightningInvoice = lnPayReq.findFirstMatchIn(rawInput).isDefined
      val isIdentifier = identifier.findFirstMatchIn(withoutSlashes).isDefined
      val addressToAmount =
        MultiAddressParser.parseAll(MultiAddressParser.parse, rawInput)

      if (isIdentifier) LNUrl.fromIdentifier(withoutSlashes)
      else if (isLightningInvoice)
        PaymentRequestExt.fromUri(withoutSlashes.toLowerCase)
      else
        addressToAmount getOrElse BitcoinUri.fromRaw(s"$bitcoin$withoutSlashes")
  }
}

object PaymentRequestExt {
  def removePrefix(raw: String): String = raw.split(':').toList match {
    case prefix :: content if lightning.startsWith(prefix.toLowerCase) =>
      content.mkString.replace("//", "")
    case prefix :: content if bitcoin.startsWith(prefix.toLowerCase) =>
      content.mkString.replace("//", "")
    case _ => raw
  }

  def withoutSlashes(prefix: String, uri: Uri): String =
    prefix + removePrefix(uri.toString)

  def fromUri(invoiceWithoutSlashes: String): PaymentRequestExt =
    invoiceWithoutSlashes match {
      case lnPayReq(invoicePrefix, invoiceData) =>
        val pr = Bolt11Invoice.fromString(s"$invoicePrefix$invoiceData").get
        val uri = Try(Uri parse s"$lightning//$invoiceWithoutSlashes")
        PaymentRequestExt(uri, pr, s"$invoicePrefix$invoiceData")
    }

  def from(pr: Bolt11Invoice): PaymentRequestExt = {
    val noUri: Try[Uri] = Failure(new RuntimeException)
    PaymentRequestExt(noUri, pr, pr.toString)
  }
}

case class PaymentRequestExt(uri: Try[Uri], pr: Bolt11Invoice, raw: String) {
  def isEnough(collected: MilliSatoshi): Boolean =
    pr.amount_opt.exists(requested => collected >= requested)
  def withNewSplit(anotherPart: MilliSatoshi): String =
    s"$lightning$raw?splits=" + (anotherPart :: splits)
      .map(_.toLong)
      .mkString(",")
  lazy val extraEdges: Set[GraphStructure.GraphEdge] =
    RouteCalculation.makeExtraEdges(pr.routingInfo, pr.nodeId)

  val splits: List[MilliSatoshi] = uri
    .map(
      _.getQueryParameter("splits")
        .split(',')
        .toList
        .map(_.toLong) map MilliSatoshi.apply
    )
    .getOrElse(Nil)
  val hasSplitIssue: Boolean = pr.amount_opt.exists(
    _ < (splits.fold(MilliSatoshi(0))(_ + _) + LNParams.minPayment)
  ) || (pr.amount_opt.isEmpty && splits.nonEmpty)
  val splitLeftover: MilliSatoshi =
    pr.amount_opt
      .map(amt => amt - splits.fold(MilliSatoshi(0))(_ + _))
      .getOrElse(MilliSatoshi(0L))

  val descriptionOpt: Option[String] =
    pr.description.left.toOption.map(trimmed).filter(_.nonEmpty)
  val brDescription: String =
    descriptionOpt.map(desc => s"<br><br>$desc").getOrElse(new String)
}

object BitcoinUri {
  def fromRaw(raw: String): BitcoinUri = {
    val dataWithoutPrefix = PaymentRequestExt.removePrefix(raw)
    val uri = Uri.parse(s"$bitcoin//$dataWithoutPrefix")
    BitcoinUri(Success(uri), uri.getHost)
  }
}

case class BitcoinUri(uri: Try[Uri], address: String) {
  val amount: Option[MilliSatoshi] = uri
    .map(_ getQueryParameter "amount")
    .map(BigDecimal.apply)
    .map(Denomination.btcBigDecimal2MSat)
    .toOption
  val prExt: Option[PaymentRequestExt] = uri
    .map(_ getQueryParameter "lightning")
    .map(PaymentRequestExt.fromUri)
    .toOption
  val message: Option[String] = uri
    .map(_ getQueryParameter "message")
    .map(trimmed)
    .filter(_.nonEmpty)
    .toOption
  val label: Option[String] = uri
    .map(_ getQueryParameter "label")
    .map(trimmed)
    .filter(_.nonEmpty)
    .toOption
}

object MultiAddressParser extends RegexParsers {

  type AddressToAmountItem = (String, Satoshi)

  case class AddressToAmount(values: Seq[AddressToAmountItem] = Nil)

  private[this] val longSat = "[0-9,]+".r ^^ (_.replace(",", "").toLong.sat)

  private[this] val decimalSat = "[0-9]*\\.[0-9]+".r ^^ (raw =>
    (BigDecimal(raw) * BtcAmount.Coin).toLong.sat
  )

  private[this] val item = "\\w+".r ~ (decimalSat | longSat) ^^ {
    case address ~ sat => address -> sat
  }

  private[this] val separator = opt(";")

  val parse: Parser[AddressToAmount] =
    repsep(item, separator).map(AddressToAmount(_))
}