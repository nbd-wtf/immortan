package immortan.router

import scodec.bits.{crc, ByteVector}
import scoin._
import scoin.ln._
import scoin.ln.LightningMessageCodecs.channelUpdateChecksumCodec

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

  def getChannelDigestInfo(channels: Map[ShortChannelId, PublicChannel])(
      scid: ShortChannelId
  ): (ReplyChannelRangeTlv.Timestamps, ReplyChannelRangeTlv.Checksums) = {
    val c = channels(scid)
    val timestamp1 =
      c.update1Opt.map(_.update.timestamp).getOrElse(TimestampSecond(0L))
    val timestamp2 =
      c.update2Opt.map(_.update.timestamp).getOrElse(TimestampSecond(0L))
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

  def getChecksum(u: ChannelUpdate): Long =
    crc
      .crc32c(
        channelUpdateChecksumCodec
          .encode(
            ChannelUpdate.Checksum(
              u.chainHash,
              u.shortChannelId,
              u.channelFlags,
              u.cltvExpiryDelta,
              u.htlcMinimumMsat,
              u.feeBaseMsat,
              u.feeProportionalMillionths,
              u.htlcMaximumMsat
            )
          )
          .require
      )
      .toLong()
}
