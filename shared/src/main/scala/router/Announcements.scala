package immortan.router

import scala.concurrent.duration._
import scodec.bits.{BitVector, ByteVector}

import scoin._
import scoin.Crypto.{PrivateKey, PublicKey, sha256, verifySignature}
import scoin.ln._
import scoin.ln.LightningMessageCodecs.{
  channelUpdateCodec,
  nodeAnnouncementCodec
}

object Announcements {
  def isNode1(localNodeId: PublicKey, remoteNodeId: PublicKey): Boolean =
    LexicographicalOrdering.isLessThan(localNodeId.value, remoteNodeId.value)

  def isNode1(channelFlags: Byte): Boolean = (channelFlags & 1) == 0

  def isEnabled(channelFlags: Byte): Boolean = (channelFlags & 2) == 0

  def areSame(u1: ChannelUpdate, u2: ChannelUpdate): Boolean =
    u1.copy(signature = ByteVector64.Zeroes, timestamp = TimestampSecond(0)) ==
      u2.copy(signature = ByteVector64.Zeroes, timestamp = TimestampSecond(0))

  def checkSig(upd: ChannelUpdate)(nodeId: PublicKey): Boolean =
    verifySignature(
      sha256(
        sha256(
          channelUpdateCodec.encode(upd).require.toByteVector.drop(64)
        )
      ),
      upd.signature,
      nodeId
    )

  def checkSig(ann: NodeAnnouncement): Boolean = {
    verifySignature(
      sha256(
        sha256(nodeAnnouncementCodec.encode(ann).require.toByteVector.drop(64))
      ),
      ann.signature,
      ann.nodeId
    )
  }
}
