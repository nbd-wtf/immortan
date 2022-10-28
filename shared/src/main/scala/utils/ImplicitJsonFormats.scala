package immortan.utils

import scala.util.Try
import scodec.bits.BitVector
import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto._
import scoin.Crypto.PublicKey
import scoin.DeterministicWallet.ExtendedPublicKey
import scoin._
import scoin.CommonCodecs._
import scoin.ln.ChannelUpdate
import scoin.ln.LightningMessageCodecs._

import immortan._
import immortan.fsm.SplitInfo
import immortan.electrum.db.{ChainWalletInfo, SigningWallet, WatchingWallet}
import immortan.blockchain.fee._

object ImplicitJsonFormats {
  final val TAG = "tag"

  def encodeWithTag[T](base: Encoder[T], tag: String): Encoder[T] =
    new Encoder[T] {
      final def apply(t: T): Json =
        base(t).asObject.map(_.add("tag", tag.asJson)).get.asJson
    }

  def encodeWithCodec[T](codec: scodec.Codec[T]): Encoder[T] =
    Encoder.encodeString.contramap(codec.encode(_).require.toHex)
  def decodeWithCodec[T](codec: scodec.Codec[T]): Decoder[T] =
    Decoder.decodeString.emapTry(s =>
      Try(codec.decodeValue(BitVector.fromValidHex(s)).require)
    )

  implicit val encodePublicKey: Encoder[PublicKey] = encodeWithCodec(publickey)
  implicit val decodePublicKey: Decoder[PublicKey] = decodeWithCodec(publickey)

  implicit val encodeExtendedPublicKey: Encoder[ExtendedPublicKey] =
    encodeWithCodec(extendedPublicKeyCodec)
  implicit val decodeExtendedPublicKey: Decoder[ExtendedPublicKey] =
    decodeWithCodec(extendedPublicKeyCodec)

  implicit val encodeByteVector32: Encoder[ByteVector32] =
    encodeWithCodec(bytes32)
  implicit val decodeByteVector32: Decoder[ByteVector32] =
    decodeWithCodec(bytes32)

  implicit val encodeChannelUpdate: Encoder[ChannelUpdate] =
    encodeWithCodec(channelUpdateCodec)
  implicit val decodeChannelUpdate: Decoder[ChannelUpdate] =
    decodeWithCodec(channelUpdateCodec)

  implicit val encodeMilliSatoshi: Encoder[MilliSatoshi] =
    Encoder.encodeLong.contramap(_.toLong)
  implicit val decodeMilliSatoshi: Decoder[MilliSatoshi] =
    Decoder.decodeLong.emap(l => Right(MilliSatoshi(l)))

  implicit val encodeSatoshi: Encoder[Satoshi] =
    Encoder.encodeLong.contramap(_.toLong)
  implicit val decodeSatoshi: Decoder[Satoshi] =
    Decoder.decodeLong.emap(l => Right(Satoshi(l)))

  // Chain wallet types
  // implicit val encodeChainWalletInfo: Encoder[ChainWalletInfo] =
  implicit val encodeChainWalletInfo: Encoder[ChainWalletInfo] =
    new Encoder[ChainWalletInfo] {
      final def apply(cwi: ChainWalletInfo): Json = cwi match {
        case wi: WatchingWallet => wi.asJson
        case wi: SigningWallet  => wi.asJson
      }
    }
  implicit val decodeChainWalletInfo: Decoder[ChainWalletInfo] =
    new Decoder[ChainWalletInfo] {
      final def apply(c: HCursor): Decoder.Result[ChainWalletInfo] =
        c.get[String]("tag").flatMap {
          case "WatchingWallet" => c.as[WatchingWallet]
          case "SigningWallet"  => c.as[SigningWallet]
          case t =>
            Left(
              DecodingFailure(
                DecodingFailure.Reason
                  .CustomReason(s"unexpected chain wallet tag $t"),
                c
              )
            )
        }
    }

  implicit val encodeSigningWallet: Encoder[SigningWallet] =
    encodeWithTag(
      deriveEncoder[SigningWallet],
      tag = "SigningWallet"
    )
  implicit val decodeSigningWallet: Decoder[SigningWallet] = deriveDecoder

  implicit val encodeWatchingWallet: Encoder[WatchingWallet] =
    encodeWithTag(
      deriveEncoder[WatchingWallet],
      tag = "WatchingWallet"
    )
  implicit val decodeWatchingWallet: Decoder[WatchingWallet] = deriveDecoder

  // PaymentInfo stuff
  implicit val encodeSemanticOrder: Encoder[SemanticOrder] = deriveEncoder
  implicit val decodeSemanticOrder: Decoder[SemanticOrder] = deriveDecoder

  implicit val encodeLNUrlDescription: Encoder[LNUrlDescription] = deriveEncoder
  implicit val decodeLNUrlDescription: Decoder[LNUrlDescription] = deriveDecoder

  implicit val encodeTxDescription: Encoder[TxDescription] =
    new Encoder[TxDescription] {
      final def apply(cwi: TxDescription): Json = cwi match {
        case txd: PlainTxDescription         => txd.asJson
        case txd: OpReturnTxDescription      => txd.asJson
        case txd: ChanFundingTxDescription   => txd.asJson
        case txd: ChanRefundingTxDescription => txd.asJson
        case txd: HtlcClaimTxDescription     => txd.asJson
        case txd: PenaltyTxDescription       => txd.asJson
      }
    }
  implicit val decodeTxDescription: Decoder[TxDescription] =
    new Decoder[TxDescription] {
      final def apply(c: HCursor): Decoder.Result[TxDescription] =
        c.get[String]("tag").flatMap {
          case "PlainTxDescription"         => c.as[PlainTxDescription]
          case "OpReturnTxDescription"      => c.as[OpReturnTxDescription]
          case "ChanFundingTxDescription"   => c.as[ChanFundingTxDescription]
          case "ChanRefundingTxDescription" => c.as[ChanRefundingTxDescription]
          case "HtlcClaimTxDescription"     => c.as[HtlcClaimTxDescription]
          case "PenaltyTxDescription"       => c.as[PenaltyTxDescription]
          case t =>
            Left(
              DecodingFailure(
                DecodingFailure.Reason
                  .CustomReason(s"unexpected tx description tag $t"),
                c
              )
            )
        }
    }

  implicit val encodeRBFParams: Encoder[RBFParams] = deriveEncoder
  implicit val decodeRBFParams: Decoder[RBFParams] = deriveDecoder

  implicit val encodePlainTxDescription: Encoder[PlainTxDescription] =
    encodeWithTag(
      deriveEncoder[PlainTxDescription],
      tag = "PlainTxDescription"
    )
  implicit val decodePlainTxDescription: Decoder[PlainTxDescription] =
    deriveDecoder

  implicit val encodeOpReturnTxDescription: Encoder[OpReturnTxDescription] =
    encodeWithTag(
      deriveEncoder[OpReturnTxDescription],
      tag = "OpReturnTxDescription"
    )
  implicit val decodeOpReturnTxDescription: Decoder[OpReturnTxDescription] =
    deriveDecoder

  implicit val encodeChanFundingTxDescription
      : Encoder[ChanFundingTxDescription] =
    encodeWithTag(
      deriveEncoder[ChanFundingTxDescription],
      tag = "ChanFundingTxDescription"
    )
  implicit val decodeChanFundingTxDescription
      : Decoder[ChanFundingTxDescription] =
    deriveDecoder

  implicit val encodeChanRefundingTxDescription
      : Encoder[ChanRefundingTxDescription] =
    encodeWithTag(
      deriveEncoder[ChanRefundingTxDescription],
      tag = "ChanRefundingTxDescription"
    )
  implicit val decodeChanRefundingTxDescription
      : Decoder[ChanRefundingTxDescription] =
    deriveDecoder

  implicit val encodeHtlcClaimTxDescription: Encoder[HtlcClaimTxDescription] =
    encodeWithTag(
      deriveEncoder[HtlcClaimTxDescription],
      tag = "HtlcClaimTxDescription"
    )
  implicit val decodeHtlcClaimTxDescription: Decoder[HtlcClaimTxDescription] =
    deriveDecoder

  implicit val encodePenaltyTxDescription: Encoder[PenaltyTxDescription] =
    encodeWithTag(
      deriveEncoder[PenaltyTxDescription],
      tag = "PenaltyTxDescription"
    )
  implicit val decodePenaltyTxDescription: Decoder[PenaltyTxDescription] =
    deriveDecoder

  implicit val encodeSplitInfo: Encoder[SplitInfo] = deriveEncoder
  implicit val decodeSplitInfo: Decoder[SplitInfo] = deriveDecoder

  implicit val encodePaymentDesc: Encoder[PaymentDescription] = deriveEncoder
  implicit val decodePaymentDesc: Decoder[PaymentDescription] = deriveDecoder

  // LNURL Payment action
  implicit val encodePaymentAction: Encoder[PaymentAction] =
    new Encoder[PaymentAction] {
      final def apply(cwi: PaymentAction): Json = cwi match {
        case pa: MessageAction => pa.asJson
        case pa: UrlAction     => pa.asJson
        case pa: AESAction     => pa.asJson
      }
    }
  implicit val decodePaymentAction: Decoder[PaymentAction] =
    new Decoder[PaymentAction] {
      final def apply(c: HCursor): Decoder.Result[PaymentAction] =
        c.get[String]("tag").flatMap {
          case "message" => c.as[MessageAction]
          case "aes"     => c.as[AESAction]
          case "url"     => c.as[UrlAction]
          case t =>
            Left(
              DecodingFailure(
                DecodingFailure.Reason
                  .CustomReason(s"unexpected payment action tag $t"),
                c
              )
            )
        }
    }

  implicit val encodeAESAction: Encoder[AESAction] =
    encodeWithTag(deriveEncoder[AESAction], tag = "aes")
  implicit val decodeAESAction: Decoder[AESAction] = deriveDecoder

  implicit val encodeMessageAction: Encoder[MessageAction] =
    encodeWithTag(deriveEncoder[MessageAction], tag = "message")
  implicit val decodeMessageAction: Decoder[MessageAction] = deriveDecoder

  implicit val encodeUrlAction: Encoder[UrlAction] =
    encodeWithTag(deriveEncoder[UrlAction], tag = "url")
  implicit val decodeUrlAction: Decoder[UrlAction] = deriveDecoder

  // LNURL
  implicit val decodeLNUrlData: Decoder[LNUrlData] =
    new Decoder[LNUrlData] {
      final def apply(c: HCursor): Decoder.Result[LNUrlData] =
        c.get[String]("tag").flatMap {
          case "channelRequest"       => c.as[NormalChannelRequest]
          case "payRequest"           => c.as[PayRequest]
          case "withdrawRequest"      => c.as[WithdrawRequest]
          case "hostedChannelRequest" => c.as[HostedChannelRequest]
          case t =>
            Left(
              DecodingFailure(
                DecodingFailure.Reason
                  .CustomReason(s"unexpected lnurl tag $t"),
                c
              )
            )
        }
    }

  // Note: tag on these MUST start with lower case because it is defined that way on protocol level
  implicit val encodeNormalChannelRequest: Encoder[NormalChannelRequest] =
    encodeWithTag(deriveEncoder[NormalChannelRequest], tag = "channelRequest")
  implicit val decodeNormalChannelRequest: Decoder[NormalChannelRequest] =
    deriveDecoder

  implicit val encodeHostedChannelRequest: Encoder[HostedChannelRequest] =
    encodeWithTag(
      deriveEncoder[HostedChannelRequest],
      tag = "hostedChannelRequest"
    )
  implicit val decodeHostedChannelRequest: Decoder[HostedChannelRequest] =
    deriveDecoder

  implicit val encodeWithdrawRequest: Encoder[WithdrawRequest] =
    encodeWithTag(deriveEncoder[WithdrawRequest], tag = "withdrawRequest")
  implicit val decodeWithdrawRequest: Decoder[WithdrawRequest] = deriveDecoder

  implicit val encodePDSEntry: Encoder[PayerDataSpecEntry] = deriveEncoder
  implicit val decodePDSEntry: Decoder[PayerDataSpecEntry] = deriveDecoder

  implicit val encodeAPDSEntry: Encoder[AuthPayerDataSpecEntry] = deriveEncoder
  implicit val decodeAPDSEntry: Decoder[AuthPayerDataSpecEntry] = deriveDecoder

  implicit val encodePayerDataSpec: Encoder[PayerDataSpec] = deriveEncoder
  implicit val decodePayerDataSpec: Decoder[PayerDataSpec] = deriveDecoder

  implicit val encodeLNUrlAuthData: Encoder[LNUrlAuthData] = deriveEncoder
  implicit val decodeLNUrlAuthData: Decoder[LNUrlAuthData] = deriveDecoder

  implicit val encodePayerData: Encoder[PayerData] = deriveEncoder
  implicit val decodePayerData: Decoder[PayerData] = deriveDecoder

  implicit val encodePayRequest: Encoder[PayRequest] = encodeWithTag(
    deriveEncoder[PayRequest],
    tag = "payRequest"
  )
  implicit val decodePayRequest: Decoder[PayRequest] = deriveDecoder

  implicit val encodePayRequestFinal: Encoder[PayRequestFinal] = deriveEncoder
  implicit val decodePayRequestFinal: Decoder[PayRequestFinal] = deriveDecoder

  // Fiat feerates
  implicit val encodeBCIItem: Encoder[BlockchainInfoItem] = deriveEncoder
  implicit val decodeBCIItem: Decoder[BlockchainInfoItem] = deriveDecoder

  implicit val encodeCoinGeckoItem: Encoder[CoinGeckoItem] = deriveEncoder
  implicit val decodeCoinGeckoItem: Decoder[CoinGeckoItem] = deriveDecoder

  implicit val encodeCoinGecko: Encoder[CoinGecko] = deriveEncoder
  implicit val decodeCoinGecko: Decoder[CoinGecko] = deriveDecoder

  implicit val encodeBitpayItem: Encoder[BitpayItem] = deriveEncoder
  implicit val decodeBitpayItem: Decoder[BitpayItem] = deriveDecoder

  implicit val encodeBitpay: Encoder[Bitpay] = deriveEncoder
  implicit val decodeBitpay: Decoder[Bitpay] = deriveDecoder

  implicit val encodeFiatRatesInfo: Encoder[FiatRatesInfo] = deriveEncoder
  implicit val decodeFiatRatesInfo: Decoder[FiatRatesInfo] = deriveDecoder

  // Chain feerates
  implicit val encodeBitGoFeeRateStructure: Encoder[BitGoFeeRateStructure] =
    deriveEncoder
  implicit val decodeBitGoFeeRateStructure: Decoder[BitGoFeeRateStructure] =
    deriveDecoder

  implicit val encodeFeeratePerKB: Encoder[FeeratePerKB] =
    encodeSatoshi.contramap(_.feerate)
  implicit val decodeFeeratePerKB: Decoder[FeeratePerKB] =
    decodeSatoshi.emap(s => Right(FeeratePerKB(s)))

  implicit val encodeFeeratesPerKB: Encoder[FeeratesPerKB] = deriveEncoder
  implicit val decodeFeeratesPerKB: Decoder[FeeratesPerKB] = deriveDecoder

  implicit val encodeFeeratePerKw: Encoder[FeeratePerKw] =
    encodeSatoshi.contramap(_.feerate)
  implicit val decodeFeeratePerKw: Decoder[FeeratePerKw] =
    decodeSatoshi.emap(s => Right(FeeratePerKw(s)))

  implicit val encodeFeeratesPerKw: Encoder[FeeratesPerKw] = deriveEncoder
  implicit val decodeFeeratesPerKw: Decoder[FeeratesPerKw] = deriveDecoder

  implicit val encodeFeeratesInfo: Encoder[FeeRatesInfo] = deriveEncoder
  implicit val decodeFeeratesInfo: Decoder[FeeRatesInfo] = deriveDecoder
}
