package immortan

import scoin.ln.{Feature, NodeFeature, InitFeature}

case object PrivateRouting extends Feature with NodeFeature with InitFeature {
  val rfcName = "Private routing"
  val mandatory = 33174
}
