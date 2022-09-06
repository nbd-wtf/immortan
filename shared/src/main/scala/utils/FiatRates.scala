package immortan.utils

import scoin.Crypto

import immortan._
import immortan.utils.ImplicitJsonFormats._

object FiatRates {
  type BlockchainInfoItemMap = Map[String, BlockchainInfoItem]
  type CoinGeckoItemMap = Map[String, CoinGeckoItem]
  type BitpayItemList = List[BitpayItem]
}

class FiatRates(bag: DataBag) extends CanBeShutDown {
  override def becomeShutDown(): Unit = listeners = Set.empty

  val customFiatSymbols: Map[String, String] = Map(
    "rub" -> "\u20BD",
    "usd" -> "$",
    "inr" -> "₹",
    "gbp" -> "£",
    "cny" -> "CN¥",
    "jpy" -> "¥",
    "brl" -> "R$",
    "eur" -> "€",
    "krw" -> "₩"
  )

  val universallySupportedSymbols: Map[String, String] = Map(
    "usd" -> "US Dollar",
    "eur" -> "Euro",
    "jpy" -> "Japanese Yen",
    "cny" -> "Chinese Yuan",
    "inr" -> "Indian Rupee",
    "cad" -> "Canadian Dollar",
    "rub" -> "Русский Рубль",
    "brl" -> "Real Brasileiro",
    "czk" -> "Česká Koruna",
    "gbp" -> "Pound Sterling",
    "aud" -> "Australian Dollar",
    "try" -> "Turkish Lira",
    "nzd" -> "New Zealand Dollar",
    "thb" -> "Thai Baht",
    "twd" -> "New Taiwan Dollar",
    "krw" -> "South Korean won",
    "clp" -> "Chilean Peso",
    "sgd" -> "Singapore Dollar",
    "hkd" -> "Hong Kong Dollar",
    "pln" -> "Polish złoty",
    "dkk" -> "Danish Krone",
    "sek" -> "Swedish Krona",
    "chf" -> "Swiss franc",
    "huf" -> "Hungarian forint"
  )

  def reloadData: Fiat2Btc =
    (Crypto.randomBytes(1).toInt(signed = false) % 3) match {
      case 0 =>
        to[CoinGecko](
          LNParams.connectionProvider.get(
            "https://api.coingecko.com/api/v3/exchange_rates"
          )
        ).rates.map { case (code, item) => code.toLowerCase -> item.value }
      case 1 =>
        to[FiatRates.BlockchainInfoItemMap](
          LNParams.connectionProvider.get("https://blockchain.info/ticker")
        ).map { case (code, item) => code.toLowerCase -> item.last }
      case _ =>
        to[Bitpay](
          LNParams.connectionProvider.get("https://bitpay.com/rates")
        ).data.map { case BitpayItem(code, rate) =>
          code.toLowerCase -> rate
        }.toMap
    }

  def updateInfo(newRates: Fiat2Btc): Unit = {
    info = FiatRatesInfo(newRates, info.rates, System.currentTimeMillis)
    for (lst <- listeners) lst.onFiatRates(info)
  }

  var listeners: Set[FiatRatesListener] = Set.empty
  var info: FiatRatesInfo = bag.tryGetFiatRatesInfo getOrElse {
    FiatRatesInfo(rates = Map.empty, oldRates = Map.empty, stamp = 0L)
  }
}

trait FiatRatesListener {
  def onFiatRates(rates: FiatRatesInfo): Unit
}

case class CoinGeckoItem(value: Double)
case class BlockchainInfoItem(last: Double)
case class BitpayItem(code: String, rate: Double)

case class Bitpay(data: FiatRates.BitpayItemList)
case class CoinGecko(rates: FiatRates.CoinGeckoItemMap)

case class FiatRatesInfo(
    rates: Fiat2Btc,
    oldRates: Fiat2Btc,
    stamp: Long
)
