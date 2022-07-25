package immortan.utils

import scala.util.chaining._
import scala.util.Try
import com.google.common.base.CharMatcher
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Bech32, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.wire.NodeAddress
import immortan.crypto.Tools
import immortan.utils.ImplicitJsonFormats._
import immortan.utils.uri.Uri
import immortan.{LNParams, PaymentAction, RemoteNodeInfo}
import com.softwaremill.quicklens._
import rx.lang.scala.Observable
import scodec.bits.ByteVector
import spray.json._

object LNUrl {
  def fromIdentifier(identifier: String): LNUrl = {
    val (user, domain) = identifier.splitAt(identifier indexOf '@')
    val isOnionDomain: Boolean = domain.endsWith(NodeAddress.onionSuffix)
    if (isOnionDomain) LNUrl(s"http://$domain/.well-known/lnurlp/$user")
    else LNUrl(s"https://$domain/.well-known/lnurlp/$user")
  }

  def fromBech32(bech32url: String): LNUrl = {
    val (_, dataBody, _) = Bech32.decode(bech32url)
    val request = new String(Bech32.five2eight(dataBody), "UTF-8")
    LNUrl(request)
  }

  def checkHost(host: String): Uri = Uri.parse(host) match {
    case uri =>
      val isOnion = host.startsWith("http://") && uri.getHost.endsWith(
        NodeAddress.onionSuffix
      )
      val isSSLPlain = host.startsWith("https://") && !uri.getHost.endsWith(
        NodeAddress.onionSuffix
      )
      require(
        isSSLPlain || isOnion,
        "URI is neither Plain/HTTPS nor Onion/HTTP request"
      )
      uri
  }

  def guardResponse(raw: String): String = {
    val parseAttempt = Try(raw.parseJson.asJsObject.fields)
    val hasErrorDescription =
      parseAttempt.map(_ apply "reason").map(json2String)
    val hasError = parseAttempt
      .map(_ apply "status")
      .map(json2String)
      .filter(_.toUpperCase == "ERROR")
    if (hasErrorDescription.isSuccess)
      throw new Exception(s"Error from vendor: ${hasErrorDescription.get}")
    else if (hasError.isSuccess)
      throw new Exception(s"Error from vendor: no description provided")
    else if (parseAttempt.isFailure)
      throw new Exception(s"Invalid json from vendor: $raw")
    raw
  }

  def level2DataResponse(bld: Uri.Builder): Observable[String] =
    Rx.ioQueue.map { _ =>
      guardResponse(LNParams.connectionProvider.get(bld.build.toString))
    }
}

case class LNUrl(request: String) {
  val uri: Uri = LNUrl.checkHost(request)
  val warnUri: String = uri.getHost.map { char =>
    if (CharMatcher.ascii matches char) char.toString
    else s"<b>[$char]</b>"
  }.mkString

  lazy val k1: Try[String] = Try(uri getQueryParameter "k1")
  lazy val isAuth: Boolean = {
    val authTag =
      Try(uri.getQueryParameter("tag").toLowerCase == "login").getOrElse(false)
    val validK1 = k1
      .flatMap(k1str => Try(ByteVector.fromValidHex(k1str)))
      .map(_.size == 32)
      .getOrElse(false)
    authTag && validK1
  }
  lazy val authAction: String =
    Try(uri.getQueryParameter("action").toLowerCase).getOrElse("login")

  lazy val fastWithdrawAttempt: Try[WithdrawRequest] = Try {
    require(uri.getQueryParameter("tag") equals "withdrawRequest")
    WithdrawRequest(
      uri.getQueryParameter("callback"),
      uri.getQueryParameter("k1"),
      uri.getQueryParameter("maxWithdrawable").toLong,
      uri.getQueryParameter("defaultDescription"),
      Some(uri.getQueryParameter("minWithdrawable").toLong)
    )
  }

  def level1DataResponse: Observable[LNUrlData] = Rx.ioQueue.map { _ =>
    to[LNUrlData](LNParams.connectionProvider.get(uri.toString))
  }
}

sealed trait LNUrlData
sealed trait CallbackLNUrlData extends LNUrlData {
  val callbackUri: Uri = LNUrl.checkHost(callback)
  def callback: String
}

// LNURL-CHANNEL
sealed trait HasRemoteInfo {
  val remoteInfo: RemoteNodeInfo
  def cancel(): Unit = Tools.none
}

case class HasRemoteInfoWrap(remoteInfo: RemoteNodeInfo) extends HasRemoteInfo

case class NormalChannelRequest(uri: String, callback: String, k1: String)
    extends CallbackLNUrlData
    with HasRemoteInfo {

  def requestChannel: Observable[String] = LNUrl.level2DataResponse {
    callbackUri.buildUpon
      .appendQueryParameter("k1", k1)
      .appendQueryParameter("private", "1")
      .appendQueryParameter("remoteid", remoteInfo.nodeSpecificPubKey.toString)
  }

  override def cancel(): Unit = LNUrl
    .level2DataResponse {
      callbackUri.buildUpon
        .appendQueryParameter("k1", k1)
        .appendQueryParameter("cancel", "1")
        .appendQueryParameter(
          "remoteid",
          remoteInfo.nodeSpecificPubKey.toString
        )
    }
    .foreach(Tools.none, Tools.none)

  val InputParser.nodeLink(nodeKey, hostAddress, portNumber) = uri
  val pubKey: PublicKey = PublicKey.fromBin(ByteVector fromValidHex nodeKey)
  val address: NodeAddress =
    NodeAddress.fromParts(hostAddress, portNumber.toInt)
  val remoteInfo: RemoteNodeInfo = RemoteNodeInfo(pubKey, address, hostAddress)
}

case class HostedChannelRequest(uri: String, alias: Option[String], k1: String)
    extends LNUrlData
    with HasRemoteInfo {

  val secret: ByteVector32 = ByteVector32.fromValidHex(k1)
  val InputParser.nodeLink(nodeKey, hostAddress, portNumber) = uri
  val pubKey: PublicKey = PublicKey(ByteVector fromValidHex nodeKey)
  val address: NodeAddress =
    NodeAddress.fromParts(hostAddress, portNumber.toInt)
  val remoteInfo: RemoteNodeInfo = RemoteNodeInfo(pubKey, address, hostAddress)
}

// LNURL-WITHDRAW
case class WithdrawRequest(
    callback: String,
    k1: String,
    maxWithdrawable: Long,
    defaultDescription: String,
    minWithdrawable: Option[Long],
    balance: Option[Long] = None,
    balanceCheck: Option[String] = None,
    payLink: Option[String] = None
) extends CallbackLNUrlData { me =>
  def requestWithdraw(ext: PaymentRequestExt): Observable[String] =
    LNUrl.level2DataResponse {
      callbackUri.buildUpon
        .appendQueryParameter("pr", ext.raw)
        .appendQueryParameter("k1", k1)
    }

  val minCanReceive: MilliSatoshi = minWithdrawable
    .map(_.msat)
    .getOrElse(LNParams.minPayment)
    .max(LNParams.minPayment)

  val nextWithdrawRequestOpt: Option[LNUrl] = balanceCheck.map(LNUrl.apply)

  val relatedPayLinkOpt: Option[LNUrl] = payLink.map(LNUrl.apply)

  val descriptionOpt: Option[String] =
    Some(defaultDescription).map(Tools.trimmed).filter(_.nonEmpty)

  require(
    minCanReceive <= maxWithdrawable.msat,
    s"$maxWithdrawable is less than min $minCanReceive"
  )
}

// LNURL-PAY
object PayRequest {
  type TagAndContent = Vector[JsValue]
}

case class PayRequestMeta(records: PayRequest.TagAndContent) {
  val text: Option[String] = records.collectFirst {
    case JsArray(JsString("text/plain") +: JsString(txt) +: _) => txt
  }
  val longDesc: Option[String] = records.collectFirst {
    case JsArray(JsString("text/long-desc") +: JsString(txt) +: _) => txt
  }
  val email: Option[String] = records.collectFirst {
    case JsArray(JsString("text/email") +: JsString(email) +: _) => email
  }
  val identity: Option[String] = records.collectFirst {
    case JsArray(JsString("text/identifier") +: JsString(identifier) +: _) =>
      identifier
  }
  val textFull: Option[String] = longDesc.orElse(text)
  val textShort: Option[String] = text.map(Tools.trimmed(_))
  val imageBase64: Option[String] = records.collectFirst {
    case JsArray(
          JsString("image/png;base64" | "image/jpeg;base64") +: JsString(
            image
          ) +: _
        ) =>
      image
  }

  def queryText(domain: String): String = {
    val id = email.orElse(identity).getOrElse("")
    val tokenizedDomain = domain.replace('.', ' ')
    s"$id $textShort $tokenizedDomain"
  }
}

case class PayRequest(
    callback: String,
    maxSendable: Long,
    minSendable: Long,
    metadata: String,
    commentAllowed: Option[Int] = None,
    payerData: Option[PayerDataSpec] = None
) extends CallbackLNUrlData {
  val meta: PayRequestMeta = PayRequestMeta(
    metadata.parseJson.asInstanceOf[JsArray].elements
  )

  private[this] val identifiers = meta.email ++ meta.identity
  require(
    identifiers.forall(id =>
      InputParser.identifier.findFirstMatchIn(id).isDefined
    ),
    "text/email or text/identity format is wrong"
  )
  meta.imageBase64.foreach { image =>
    require(
      image.length <= 136536,
      s"Image is too big, length=${image.length}, max=136536"
    )
  }
  require(
    minSendable <= maxSendable,
    s"max=$maxSendable while min=$minSendable"
  )

  def metadataHash(rawPayerData: String): ByteVector32 =
    Crypto.sha256(ByteVector.view(metadata.getBytes ++ rawPayerData.getBytes()))

  def getFinal(
      amount: MilliSatoshi,
      comment: Option[String],

      // LUD-18
      name: Option[String] = None,
      randomKey: Option[Crypto.PublicKey] = None,
      authKeyHost: Option[String] = None // None means never include auth key
  ): Observable[PayRequestFinal] = {
    val rawPayerdata: Option[String] = this.payerData
      .map(spec =>
        PayerData(
          name = if (spec.name.isDefined) name else None,
          pubkey =
            if (spec.pubkey.isDefined) randomKey.map(_.toString) else None,
          auth = for {
            authSpec <- spec.auth
            host <- authKeyHost
            authData <- Try(
              LNUrlAuther.make(host, authSpec.k1)
            ).toOption
          } yield authData
        )
      )
      .flatMap(d => if (d == PayerData()) None else Some(d))
      .map(_.toJson.compactPrint)

    val url = this.callbackUri.buildUpon
      .appendQueryParameter("amount", amount.toLong.toString)
      .pipe(base =>
        (this.commentAllowed.getOrElse(0) > 0, comment) match {
          case (true, Some(c)) => base.appendQueryParameter("comment", c)
          case _               => base
        }
      )
      .pipe(base =>
        rawPayerdata match {
          case Some(r) => base.appendQueryParameter("payerdata", r)
          case _       => base
        }
      )

    LNUrl
      .level2DataResponse(url)
      .map(to[PayRequestFinal](_))
      .map { payRequestFinal =>
        val descriptionHashOpt =
          payRequestFinal.prExt.pr.description.toOption
        val expectedHash = this.metadataHash(rawPayerdata.getOrElse(""))
        require(
          descriptionHashOpt == Some(expectedHash),
          s"Metadata hash mismatch, expected=${expectedHash}, provided in invoice=$descriptionHashOpt"
        )
        require(
          payRequestFinal.prExt.pr.amountOpt == Some(amount),
          s"Payment amount mismatch, requested by wallet=$amount, provided in invoice=${payRequestFinal.prExt.pr.amountOpt}"
        )
        payRequestFinal
          .modify(_.successAction.each.domain)
          .setTo(Some(this.callbackUri.getHost))
      }
  }
}

case class PayerDataSpec(
    name: Option[PayerDataSpecEntry] = None,
    pubkey: Option[PayerDataSpecEntry] = None,
    auth: Option[AuthPayerDataSpecEntry] = None
)
case class PayerDataSpecEntry(mandatory: Boolean = false)
case class AuthPayerDataSpecEntry(k1: String, mandatory: Boolean = false)
case class PayerData(
    name: Option[String] = None,
    pubkey: Option[String] = None,
    auth: Option[LNUrlAuthData] = None
)
case class LNUrlAuthData(
    key: String,
    k1: String,
    sig: String
)

// LNURL-AUTH
object LNUrlAuther {
  def make(host: String, k1: String): LNUrlAuthData = {
    val linkingPrivKey: Crypto.PrivateKey =
      LNParams.secret.keys.makeLinkingKey(host)
    val linkingPubKey: String = linkingPrivKey.publicKey.toString
    val signature: ByteVector64 =
      Crypto.sign(ByteVector.fromValidHex(k1), linkingPrivKey)
    val derSignatureHex: String = Crypto.compact2der(signature).toHex

    LNUrlAuthData(
      k1 = k1,
      key = linkingPubKey.toString,
      sig = derSignatureHex
    )
  }
}

case class PayRequestFinal(
    successAction: Option[PaymentAction],
    disposable: Option[Boolean],
    pr: String
) extends LNUrlData {
  lazy val prExt: PaymentRequestExt = PaymentRequestExt.fromUri(pr)
  val isThrowAway: Boolean = disposable.getOrElse(true)
}
