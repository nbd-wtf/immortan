package immortan

import immortan.utils._
import fr.acinq.eclair._
import org.scalatest.funsuite.AnyFunSuite

class InputParserSpec extends AnyFunSuite {
  test("Parse payment requests with splits") {
    val req = "lnbc1psy596qpp5umsexzwdmh2a5uwwjunwq0nnl56l09kjx0ht93h3ys9088h0023sdzq235hqurfdcsxz6m4d4skjem0wfhkgumtdyszsarfwpcxjm3wd4jjj2r" +
      "4xyerqvffcqzpgxqyz5vqsp5dcu2nprdalp7wuglpvnep45mygs6ksq8m7xkvyrzae7lw80ttjds9qyyssq3g5xv50amvyq0526zh3f4wf3z0qhh9vnr0up8uaa948cawjx9n" +
      "rjaz7rlnjtkg4ejapccrrme2jp7za8mdjfmurv7wc9wm8wg0ja4lgqwyv8he"

    val uri1 = s"lightning:$req?splits=100,2000,300000"
    val uri2 = s"lightning:$req?x=1&splits=2000,6000"
    val uri3 = s"lightning:$req?splits="
    val uri4 = s"lightning:$req"

    assert(InputParser.parse(uri3.toUpperCase).asInstanceOf[PaymentRequestExt].splits.isEmpty)
    assert(List(100.msat, 2000.msat, 300000.msat) == PaymentRequestExt.fromUri(uri1).splits)
    assert(List(2000.msat, 6000.msat) == PaymentRequestExt.fromUri(uri2).splits)
    assert(PaymentRequestExt.fromUri(uri3).splits.isEmpty)
    assert(PaymentRequestExt.fromUri(uri4).splits.isEmpty)
  }
}
