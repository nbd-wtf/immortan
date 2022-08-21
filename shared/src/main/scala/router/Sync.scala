package immortan.router

import java.util.zip.CRC32
import scodec.bits.ByteVector
import shapeless.HNil
import scoin.ln._
import immortan.router.Router._

object Sync {
  def shouldRequestUpdate(
      ourTimestamp: Long,
      ourChecksum: Long,
      theirTimestamp: Long,
      theirChecksum: Long
  ): Boolean = {
    // we request their channel_update if all those conditions are met:
    // - it is more recent than ours
    // - it is different from ours, or it is the same but ours is about to be stale
    // - it is not stale
    val theirsIsMoreRecent = ourTimestamp < theirTimestamp
    val areDifferent = ourChecksum != theirChecksum
    val oursIsAlmostStale = StaleChannels.isAlmostStale(ourTimestamp)
    val theirsIsStale = StaleChannels.isStale(theirTimestamp)
    theirsIsMoreRecent && (areDifferent || oursIsAlmostStale) && !theirsIsStale
  }

  def getChannelDigestInfo(channels: Map[Long, PublicChannel])(
      shortChannelId: Long
  ): (ReplyChannelRangeTlv.Timestamps, ReplyChannelRangeTlv.Checksums) = {
    val c = channels(shortChannelId)
    val timestamp1 = c.update1Opt.map(_.update.timestamp).getOrElse(0L)
    val timestamp2 = c.update2Opt.map(_.update.timestamp).getOrElse(0L)
    val checksum1 = c.update1Opt.map(_.crc32).getOrElse(0L)
    val checksum2 = c.update2Opt.map(_.crc32).getOrElse(0L)
    (
      ReplyChannelRangeTlv.Timestamps(
        timestamp1 = timestamp1,
        timestamp2 = timestamp2
      ),
      ReplyChannelRangeTlv.Checksums(
        checksum1 = checksum1,
        checksum2 = checksum2
      )
    )
  }

  def crc32c(data: ByteVector): Long = {
    val digest = new CRC32
    digest.update(data.toArray)
    digest.getValue() & 0xffffffffL
  }

  def getChecksum(u: ChannelUpdate): Long = {
    val data = serializationResult(
      LightningMessageCodecs.channelUpdateChecksumCodec.encode(
        u.chainHash :: u.shortChannelId :: u.messageFlags :: u.channelFlags ::
          u.cltvExpiryDelta :: u.htlcMinimumMsat :: u.feeBaseMsat :: u.feeProportionalMillionths :: u.htlcMaximumMsat :: HNil
      )
    )
    crc32c(data)
  }
}
