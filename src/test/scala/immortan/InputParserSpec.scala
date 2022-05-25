package immortan

import fr.acinq.bitcoin.{Block, SatoshiLong}
import fr.acinq.eclair._
import immortan.utils._
import org.scalatest.funsuite.AnyFunSuite

class InputParserSpec extends AnyFunSuite {
  val req: String =
    "lnbc1psy596qpp5umsexzwdmh2a5uwwjunwq0nnl56l09kjx0ht93h3ys9088h0023sdzq235hqurfdcsxz6m4d4skjem0wfhkgumtdyszsarfwpcxjm3wd4jjj2r" +
      "4xyerqvffcqzpgxqyz5vqsp5dcu2nprdalp7wuglpvnep45mygs6ksq8m7xkvyrzae7lw80ttjds9qyyssq3g5xv50amvyq0526zh3f4wf3z0qhh9vnr0up8uaa948cawjx9n" +
      "rjaz7rlnjtkg4ejapccrrme2jp7za8mdjfmurv7wc9wm8wg0ja4lgqwyv8he"

  test("Parse payment requests with splits") {
    val uri1 = s"lightning:$req?splits=100,2000,300000"
    val uri2 = s"lightning://$req?x=1&splits=2000,6000&else=12"
    val uri3 = s"lightning:$req?splits="
    val uri4 = s"lightning:$req"

    assert(
      InputParser
        .parse(uri3.toUpperCase)
        .asInstanceOf[PaymentRequestExt]
        .splits
        .isEmpty
    )
    assert(
      List(100.msat, 2000.msat, 300000.msat) == PaymentRequestExt
        .fromUri(PaymentRequestExt.removePrefix(uri1))
        .splits
    )
    assert(
      List(2000.msat, 6000.msat) == PaymentRequestExt
        .fromUri(PaymentRequestExt.removePrefix(uri2))
        .splits
    )
    assert(
      PaymentRequestExt
        .fromUri(PaymentRequestExt.removePrefix(uri3))
        .splits
        .isEmpty
    )
    assert(
      PaymentRequestExt
        .fromUri(PaymentRequestExt.removePrefix(uri4))
        .splits
        .isEmpty
    )
  }

  test("Parse chain uri") {
    LNParams.chainHash = Block.LivenetGenesisBlock.hash
    val raw1 =
      "bitcoin://mjSk1Ny9spzU2fouzYgLqGUD8U41iR35QN?amount=0.01&label=Example+Merchant&message=Order+of+flowers+%26+chocolates"
    val uri1 = BitcoinUri.fromRaw(raw1)
    assert(uri1.address == "mjSk1Ny9spzU2fouzYgLqGUD8U41iR35QN")
    assert(uri1.message contains "Order of flowers & chocolates")
    assert(uri1.label contains "Example Merchant")
    assert(uri1.amount contains 1000000000L.msat)
    assert(uri1.prExt.isEmpty)

    val raw2 =
      "bitcoin:bc1qssm5quvrc6v7a9zy97yqxzm5v7s32an2ma9fh2?amount=0.02&label=  "
    val uri2 = BitcoinUri.fromRaw(raw2)
    assert(uri2.address == "bc1qssm5quvrc6v7a9zy97yqxzm5v7s32an2ma9fh2")
    assert(uri2.amount contains 2000000000L.msat)
    assert(uri2.label.isEmpty)

    val raw3 =
      s"bitcoin:bc1qssm5quvrc6v7a9zy97yqxzm5v7s32an2ma9fh2?amount=0.02&lightning=$req"
    val uri3 = BitcoinUri.fromRaw(raw3)
    assert(uri3.prExt.isDefined)
  }

  test("Internet identifiers") {
    val wrong1 = "abc@a"
    val wrong2 = "abc@9"
    val wrong3 = "-fsdf@sdfds.com"
    val wrong4 = "-a@abc.com"
    val wrong5 = "a-@abc.com"

    val correct1 = "user@website.com"
    val correct2 = "us_er.na_me@website.com"
    val correct3 = "user-name.name@web-site.com"
    val correct4 = "100rub-123123123123@lnurl-pay.me"
    val correct5 = "123123123123@cards.lnurl-pay.to.me"
    val correct6 = "a@abc.com"
    val correct7 = "ab@abc.com"

    assert(InputParser.identifier.findFirstMatchIn(wrong1).isEmpty)
    assert(InputParser.identifier.findFirstMatchIn(wrong2).isEmpty)
    assert(InputParser.identifier.findFirstMatchIn(wrong3).isEmpty)
    assert(InputParser.identifier.findFirstMatchIn(wrong4).isEmpty)
    assert(InputParser.identifier.findFirstMatchIn(wrong5).isEmpty)

    assert(InputParser.identifier.findFirstMatchIn(correct1).isDefined)
    assert(InputParser.identifier.findFirstMatchIn(correct2).isDefined)
    assert(InputParser.identifier.findFirstMatchIn(correct3).isDefined)
    assert(InputParser.identifier.findFirstMatchIn(correct4).isDefined)
    assert(InputParser.identifier.findFirstMatchIn(correct5).isDefined)
    assert(InputParser.identifier.findFirstMatchIn(correct6).isDefined)
    assert(InputParser.identifier.findFirstMatchIn(correct7).isDefined)
  }

  test("Detect non-ascii chars") {
    val allAscii = "https://www.website.com.ua"
    val nonAscii = "https://www.websוte.com.ua"

    assert(LNUrl(allAscii).warnUri == LNUrl(allAscii).uri.getHost)
    assert(LNUrl(nonAscii).warnUri == "www.webs<b>[ו]</b>te.com.ua")
  }

  test("Multi-address parser") {
    val expected = Seq(
      "bc1qssm5quvrc6v7a9zy97yqxzm5v7s32an2ma9fh2" -> 150000000.sat,
      "181KcAjdvesSkfFQfPmoKm9W4UHFXAcxkj" -> 400000.sat,
      "3LShakamn25Bk6Mgjh8G4CKCBsXCynE2Yx" -> 19000000.sat
    )

    val valid1 =
      "bc1qssm5quvrc6v7a9zy97yqxzm5v7s32an2ma9fh2 150,000,000; 181KcAjdvesSkfFQfPmoKm9W4UHFXAcxkj  400000; 3LShakamn25Bk6Mgjh8G4CKCBsXCynE2Yx  .19"

    val valid2 =
      """
         bc1qssm5quvrc6v7a9zy97yqxzm5v7s32an2ma9fh2     150,000000

        181KcAjdvesSkfFQfPmoKm9W4UHFXAcxkj 400,,,000      ;

        3LShakamn25Bk6Mgjh8G4CKCBsXCynE2Yx  0.19

        """

    assert(
      MultiAddressParser
        .parseAll(MultiAddressParser.parse, valid1)
        .get
        .values == expected
    )
    assert(
      MultiAddressParser
        .parseAll(MultiAddressParser.parse, valid2)
        .get
        .values == expected
    )

    val invalid1 =
      "bc1qssm5quvrc6v7a9zy97yqxzm5v7s32an2ma9fh2 150,000.000 hello  400000 3LShakamn25Bk6Mgjh8G4CKCBsXCynE2Yx  0.19"

    val invalid2 =
      "bc1qssm5quvrc6v7a9zy97yqxzm5v7s32an2ma9fh2 150,000,000; 181KcAjdvesSkfFQfPmoKm9W4UHFXAcxkj  400000; 3LShakamn25Bk6Mgjh8G4CKCBsXCynE2Yx  0.19 ??!?"

    assert(
      !MultiAddressParser
        .parseAll(MultiAddressParser.parse, invalid1)
        .successful
    )
    assert(
      !MultiAddressParser
        .parseAll(MultiAddressParser.parse, invalid2)
        .successful
    )
  }
}
