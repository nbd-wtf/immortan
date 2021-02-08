package immortan.sqlite

import immortan.{ChannelBag, ChannelTable, HostedCommits, HtlcInfoTable}
import fr.acinq.eclair.channel.{NormalCommits, PersistentChannelData}
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.wire.ChannelCodecs
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.CltvExpiry


class SQLiteChannel(db: DBInterface) extends ChannelBag {
  override def put(data: PersistentChannelData): PersistentChannelData = {
    val raw = ChannelCodecs.persistentDataCodec.encode(data).require.toByteArray
    db.change(ChannelTable.newSql, data.channelId.toHex, raw)
    db.change(ChannelTable.updSql, raw, data.channelId.toHex)
    data
  }

  override def all: List[PersistentChannelData] =
    db.select(ChannelTable.selectAllSql).iterable(_ byteVec ChannelTable.data).toList
      .map(bits => ChannelCodecs.persistentDataCodec.decode(bits.toBitVector).require.value)

  override def delete(commitments: HostedCommits): Unit =
    db.change(ChannelTable.killSql, commitments.channelId.toHex)

  override def hide(commitments: NormalCommits): Unit =
    db.change(ChannelTable.hideSql, commitments.channelId.toHex)

  // HTLC infos

  override def htlcInfosForChan(channelId: ByteVector32, commitNumer: Long): Iterable[ChannelBag.CltvAndPaymentHash] =
    db.select(HtlcInfoTable.selectAllSql, channelId.toHex, commitNumer.toString) iterable { rc =>
      val paymentHash = ByteVector32(rc byteVec HtlcInfoTable.paymentHash)
      val cltvExpiry = CltvExpiry(rc int HtlcInfoTable.cltvExpiry)
      ChannelBag.CltvAndPaymentHash(paymentHash, cltvExpiry)
    }

  override def putHtlcInfo(channelId: ByteVector32, commitNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit =
    db.change(HtlcInfoTable.newSql, channelId.toHex, commitNumber: java.lang.Long, paymentHash.toArray, cltvExpiry.toLong: java.lang.Long)

  override def putHtlcInfos(htlcs: Seq[DirectedHtlc], channelId: ByteVector32, commitNumber: Long): Unit = db txWrap {
    for (htlc <- htlcs) putHtlcInfo(channelId, commitNumber, htlc.add.paymentHash, htlc.add.cltvExpiry)
  }
}