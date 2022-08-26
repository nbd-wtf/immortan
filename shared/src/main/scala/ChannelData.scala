package immortan.channel

import scoin._
import scoin.ln._

sealed trait HtlcResult
object HtlcResult {
  sealed trait Fulfill extends HtlcResult { def paymentPreimage: ByteVector32 }
  case class RemoteFulfill(fulfill: UpdateFulfillHtlc) extends Fulfill {
    override val paymentPreimage = fulfill.paymentPreimage
  }
  case class OnChainFulfill(paymentPreimage: ByteVector32) extends Fulfill
  sealed trait Fail extends HtlcResult
  case class RemoteFail(fail: UpdateFailHtlc) extends Fail
  case class RemoteFailMalformed(fail: UpdateFailMalformedHtlc) extends Fail
  case class OnChainFail(cause: Throwable) extends Fail
  case object ChannelFailureBeforeSigned extends Fail
  case class DisconnectedBeforeSigned(channelUpdate: ChannelUpdate)
      extends Fail {
    require(
      !channelUpdate.channelFlags.isEnabled,
      "channel update must have disabled flag set"
    )
  }
}

trait RemoteReject { val ourAdd: UpdateAddHtlc }
case class RemoteUpdateFail(fail: UpdateFailHtlc, ourAdd: UpdateAddHtlc)
    extends RemoteReject
case class RemoteUpdateMalform(
    malform: UpdateFailMalformedHtlc,
    ourAdd: UpdateAddHtlc
) extends RemoteReject

case class RemoteFulfill(ourAdd: UpdateAddHtlc, theirPreimage: ByteVector32)
case class LocalFulfill(theirAdd: UpdateAddHtlc, ourPreimage: ByteVector32)
