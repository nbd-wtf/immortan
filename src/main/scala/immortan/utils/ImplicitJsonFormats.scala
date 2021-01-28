package immortan.utils

import immortan._
import spray.json._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, KeyPath}
import immortan.utils.FiatRates.{BitpayItemList, CoinGeckoItemMap}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.WalletReady
import scodec.bits.{BitVector, ByteVector}
import immortan.crypto.Tools.Fiat2Btc
import scodec.Codec


object ImplicitJsonFormats extends DefaultJsonProtocol {
  def to[T : JsonFormat](raw: String): T = raw.parseJson.convertTo[T]
  val json2String: JsValue => String = (_: JsValue).convertTo[String]
  val TAG = "tag"

  def writeExt[T](ext: (String, JsValue), base: JsValue): JsObject = JsObject(base.asJsObject.fields + ext)

  def taggedJsonFmt[T](base: JsonFormat[T], tag: String): JsonFormat[T] = new JsonFormat[T] {
    def write(unserialized: T): JsValue = writeExt(TAG -> JsString(tag), base write unserialized)
    def read(serialized: JsValue): T = base read serialized
  }

  def json2BitVec(json: JsValue): Option[BitVector] = BitVector fromHex json2String(json)

  def sCodecJsonFmt[T](codec: Codec[T] = null): JsonFormat[T] = new JsonFormat[T] {
    def read(serialized: JsValue): T = codec.decode(json2BitVec(serialized).get).require.value
    def write(unserialized: T): JsValue = codec.encode(unserialized).require.toHex.toJson
  }

  implicit val nodeAnnouncementFmt: JsonFormat[NodeAnnouncement] = sCodecJsonFmt(nodeAnnouncementCodec)
  implicit val bytesFmt: JsonFormat[ByteVector] = sCodecJsonFmt(varsizebinarydata)
  implicit val privateKeyFmt: JsonFormat[PrivateKey] = sCodecJsonFmt(privateKey)
  implicit val publicKeyFmt: JsonFormat[PublicKey] = sCodecJsonFmt(publicKey)
  implicit val bytes32Fmt: JsonFormat[ByteVector32] = sCodecJsonFmt(bytes32)
  implicit val satoshiFmt: JsonFormat[Satoshi] = sCodecJsonFmt(satoshi)

  // Wallet keys

  implicit val keyPathFmt: JsonFormat[KeyPath] = jsonFormat[Seq[Long], KeyPath](KeyPath.apply, "path")
  implicit val extendedPrivateKeyFmt: JsonFormat[ExtendedPrivateKey] = jsonFormat[ByteVector32, ByteVector32, Int, KeyPath, Long, ExtendedPrivateKey](ExtendedPrivateKey.apply, "secretkeybytes", "chaincode", "depth", "path", "parent")
  implicit val lightningNodeKeysFmt: JsonFormat[LightningNodeKeys] = jsonFormat[ExtendedPrivateKey, String, PrivateKey, LightningNodeKeys](LightningNodeKeys.apply, "extendedNodeKey", "xpub", "hashingKey")

  implicit object StorageFormatFmt extends JsonFormat[StorageFormat] {
    def write(internal: StorageFormat): JsValue = internal match {
      case mnemonicFormat: MnemonicExtStorageFormat => mnemonicFormat.toJson
      case passwordFormat: PasswordStorageFormat => passwordFormat.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): StorageFormat = raw.asJsObject.fields(TAG) match {
      case JsString("MnemonicExtStorageFormat") => raw.convertTo[MnemonicExtStorageFormat]
      case JsString("PasswordStorageFormat") => raw.convertTo[PasswordStorageFormat]
      case tag => throw new Exception(s"Unknown wallet key format=$tag")
    }
  }

  implicit val mnemonicExtStorageFormatFmt: JsonFormat[MnemonicExtStorageFormat] =
    taggedJsonFmt(jsonFormat[Set[NodeAnnouncement], LightningNodeKeys, Option[ByteVector],
    MnemonicExtStorageFormat](MnemonicExtStorageFormat.apply, "outstandingProviders", "keys",
      "seed"), tag = "MnemonicExtStorageFormat")

  implicit val passwordStorageFormatFmt: JsonFormat[PasswordStorageFormat] =
    taggedJsonFmt(jsonFormat[Set[NodeAnnouncement], LightningNodeKeys, String, Option[String],
    PasswordStorageFormat](PasswordStorageFormat.apply, "outstandingProviders", "keys", "user",
      "password"), tag = "PasswordStorageFormat")

  // Tx description

  implicit object TxDescriptionFmt extends JsonFormat[TxDescription] {
    def write(internal: TxDescription): JsValue = internal match {
      case paymentDescription: PlainTxDescription => paymentDescription.toJson
      case paymentDescription: ChanFundingTxDescription => paymentDescription.toJson
      case paymentDescription: CommitClaimTxDescription => paymentDescription.toJson
      case paymentDescription: HtlcClaimTxDescription => paymentDescription.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): TxDescription = raw.asJsObject.fields(TAG) match {
      case JsString("PlainTxDescription") => raw.convertTo[PlainTxDescription]
      case JsString("ChanFundingTxDescription") => raw.convertTo[ChanFundingTxDescription]
      case JsString("CommitClaimTxDescription") => raw.convertTo[CommitClaimTxDescription]
      case JsString("HtlcClaimTxDescription") => raw.convertTo[HtlcClaimTxDescription]
      case tag => throw new Exception(s"Unknown action=$tag")
    }
  }

  implicit val plainTxDescriptionFmt: JsonFormat[PlainTxDescription] = taggedJsonFmt(jsonFormat[String,
    PlainTxDescription](PlainTxDescription.apply, "txid"), tag = "PlainTxDescription")

  implicit val chanFundingTxDescriptionFmt: JsonFormat[ChanFundingTxDescription] = taggedJsonFmt(jsonFormat[String, String, Long,
    ChanFundingTxDescription](ChanFundingTxDescription.apply, "txid", "nodeId", "sid"), tag = "ChanFundingTxDescription")

  implicit val commitClaimTxDescriptionFmt: JsonFormat[CommitClaimTxDescription] = taggedJsonFmt(jsonFormat[String, String, Long,
    CommitClaimTxDescription](CommitClaimTxDescription.apply, "txid", "nodeId", "sid"), tag = "CommitClaimTxDescription")

  implicit val ctlcClaimTxDescriptionFmt: JsonFormat[HtlcClaimTxDescription] = taggedJsonFmt(jsonFormat[String, String, Long,
    HtlcClaimTxDescription](HtlcClaimTxDescription.apply, "txid", "nodeId", "sid"), tag = "HtlcClaimTxDescription")

  // Last seen ready event

  implicit val walletReadyFmt: JsonFormat[WalletReady] = jsonFormat[Satoshi, Satoshi, Long, Long,
    WalletReady](WalletReady.apply, "confirmedBalance", "unconfirmedBalance", "height", "timestamp")

  // Payment description

  implicit object PaymentDescriptionFmt extends JsonFormat[PaymentDescription] {
    def write(internal: PaymentDescription): JsValue = internal match {
      case paymentDescription: PlainDescription => paymentDescription.toJson
      case paymentDescription: PlainMetaDescription => paymentDescription.toJson
      case paymentDescription: SwapInDescription => paymentDescription.toJson
      case paymentDescription: SwapOutDescription => paymentDescription.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): PaymentDescription = raw.asJsObject.fields(TAG) match {
      case JsString("PlainDescription") => raw.convertTo[PlainDescription]
      case JsString("PlainMetaDescription") => raw.convertTo[PlainMetaDescription]
      case JsString("SwapInDescription") => raw.convertTo[SwapInDescription]
      case JsString("SwapOutDescription") => raw.convertTo[SwapOutDescription]
      case tag => throw new Exception(s"Unknown action=$tag")
    }
  }

  implicit val plainDescriptionFmt: JsonFormat[PlainDescription] = taggedJsonFmt(jsonFormat[String,
    PlainDescription](PlainDescription.apply, "invoiceText"), tag = "PlainDescription")

  implicit val plainMetaDescriptionFmt: JsonFormat[PlainMetaDescription] = taggedJsonFmt(jsonFormat[String, String,
    PlainMetaDescription](PlainMetaDescription.apply, "invoiceText", "meta"), tag = "PlainMetaDescription")

  implicit val swapInDescriptionFmt: JsonFormat[SwapInDescription] = taggedJsonFmt(jsonFormat[String, String, Long, PublicKey,
    SwapInDescription](SwapInDescription.apply, "invoiceText", "txid", "internalId", "nodeId"), tag = "SwapInDescription")

  implicit val swapOutDescriptionFmt: JsonFormat[SwapOutDescription] = taggedJsonFmt(jsonFormat[String, String, Satoshi, PublicKey,
    SwapOutDescription](SwapOutDescription.apply, "invoiceText", "btcAddress", "chainFee", "nodeId"), tag = "SwapOutDescription")

  // Payment action

  implicit object PaymentActionFmt extends JsonFormat[PaymentAction] {
    def write(internal: PaymentAction): JsValue = internal match {
      case paymentAction: MessageAction => paymentAction.toJson
      case paymentAction: UrlAction => paymentAction.toJson
      case paymentAction: AESAction => paymentAction.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): PaymentAction = raw.asJsObject.fields(TAG) match {
      case JsString("message") => raw.convertTo[MessageAction]
      case JsString("aes") => raw.convertTo[AESAction]
      case JsString("url") => raw.convertTo[UrlAction]
      case tag => throw new Exception(s"Unknown action=$tag")
    }
  }

  implicit val aesActionFmt: JsonFormat[AESAction] = taggedJsonFmt(jsonFormat[Option[String], String, String, String, AESAction](AESAction.apply, "domain", "description", "ciphertext", "iv"), tag = "aes")
  implicit val messageActionFmt: JsonFormat[MessageAction] = taggedJsonFmt(jsonFormat[Option[String], String, MessageAction](MessageAction.apply, "domain", "message"), tag = "message")
  implicit val urlActionFmt: JsonFormat[UrlAction] = taggedJsonFmt(jsonFormat[Option[String], String, String, UrlAction](UrlAction.apply, "domain", "description", "url"), tag = "url")

  // LNURL

  implicit object LNUrlDataFmt extends JsonFormat[LNUrlData] {
    def write(internal: LNUrlData): JsValue = throw new RuntimeException

    def read(raw: JsValue): LNUrlData = raw.asJsObject.fields(TAG) match {
      case JsString("withdrawRequest") => raw.convertTo[WithdrawRequest]
      case JsString("payRequest") => raw.convertTo[PayRequest]
      case tag => throw new Exception(s"Unknown lnurl=$tag")
    }
  }

  // Note: tag on these MUST start with lower case because it is defined that way on protocol level
  implicit val withdrawRequestFmt: JsonFormat[WithdrawRequest] = taggedJsonFmt(jsonFormat[String, String, Long, String, Option[Long],
    WithdrawRequest](WithdrawRequest.apply, "callback", "k1", "maxWithdrawable", "defaultDescription", "minWithdrawable"), tag = "withdrawRequest")

  implicit val payRequestFmt: JsonFormat[PayRequest] = taggedJsonFmt(jsonFormat[String, Long, Long, String, Option[Int],
    PayRequest](PayRequest.apply, "callback", "maxSendable", "minSendable", "metadata", "commentAllowed"), tag = "payRequest")

  implicit val payRequestFinalFmt: JsonFormat[PayRequestFinal] = jsonFormat[Option[PaymentAction], Option[Boolean], List[String], String,
    PayRequestFinal](PayRequestFinal.apply, "successAction", "disposable", "routes", "pr")

  // Fiat feerates

  implicit val ratesInfoFmt: JsonFormat[RatesInfo] = jsonFormat[Fiat2Btc, Fiat2Btc, Long, RatesInfo](RatesInfo.apply, "rates", "oldRates", "stamp")
  implicit val blockchainInfoItemFmt: JsonFormat[BlockchainInfoItem] = jsonFormat[Double, BlockchainInfoItem](BlockchainInfoItem.apply, "last")
  implicit val bitpayItemFmt: JsonFormat[BitpayItem] = jsonFormat[String, Double, BitpayItem](BitpayItem.apply, "code", "rate")
  implicit val coinGeckoItemFmt: JsonFormat[CoinGeckoItem] = jsonFormat[Double, CoinGeckoItem](CoinGeckoItem.apply, "value")
  implicit val coinGeckoFmt: JsonFormat[CoinGecko] = jsonFormat[CoinGeckoItemMap, CoinGecko](CoinGecko.apply, "rates")
  implicit val bitpayFmt: JsonFormat[Bitpay] = jsonFormat[BitpayItemList, Bitpay](Bitpay.apply, "data")
}
