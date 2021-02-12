package immortan.sqlite

import java.lang.{Long => JLong}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import immortan.{ChannelBag, ChannelTable, HostedCommits, HtlcInfoTable}
import fr.acinq.eclair.channel.{NormalCommits, PersistentChannelData}
import fr.acinq.eclair.{CltvExpiry, ShortChannelId}
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.wire.ChannelCodecs


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

  override def delete(commitments: HostedCommits): Unit = db.change(ChannelTable.killSql, commitments.channelId.toHex)

  override def hide(commitments: NormalCommits): Unit = db.change(ChannelTable.hideSql, commitments.channelId.toHex)

  // HTLC infos

  override def htlcInfos(commitNumer: Long): Iterable[ChannelBag.CltvAndHash160] =
    db.select(HtlcInfoTable.selectAllSql, commitNumer.toString) iterable { rc =>
      val cltvExpiry = CltvExpiry(rc int HtlcInfoTable.cltvExpiry)
      val hash160 = rc byteVec HtlcInfoTable.paymentHash160
      ChannelBag.CltvAndHash160(hash160, cltvExpiry)
    }

  override def putHtlcInfo(sid: ShortChannelId, commitNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit =
    db.change(HtlcInfoTable.newSql, sid.toLong: JLong, commitNumber: JLong, Crypto.ripemd160(paymentHash).toArray, cltvExpiry.toLong: JLong)

  override def putHtlcInfos(htlcs: Seq[DirectedHtlc], sid: ShortChannelId, commitNumber: Long): Unit = db txWrap {
    for (htlc <- htlcs) putHtlcInfo(sid, commitNumber, htlc.add.paymentHash, htlc.add.cltvExpiry)
  }

  override def rmHtlcInfos(sid: ShortChannelId): Unit = db.change(HtlcInfoTable.killSql, sid.toLong: JLong)
}