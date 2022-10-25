package immortan.sqlite

import java.lang.{Long => JLong}
import scala.util.Try
import scoin.{ByteVector32, Crypto, Satoshi, ShortChannelId}
import scoin.CltvExpiry

import immortan.ChannelBag
import immortan.channel.{DirectedHtlc, PersistentChannelData}
import immortan.channel.Codecs.persistentChannelDataCodec

case class ChannelTxFeesSummary(fees: Satoshi, count: Long)

class SQLiteChannel(val db: DBInterface, channelTxFeesDb: DBInterface)
    extends ChannelBag {
  override def put(
      persistentChannelData: PersistentChannelData
  ): PersistentChannelData = db.txWrap {
    val rawContent = persistentChannelDataCodec
      .encode(persistentChannelData)
      .require
      .toByteArray
    db.change(
      ChannelTable.newSql,
      persistentChannelData.channelId.toHex,
      rawContent
    )
    db.change(
      ChannelTable.updSql,
      rawContent,
      persistentChannelData.channelId.toHex
    )
    persistentChannelData
  }

  override def all: Iterable[PersistentChannelData] =
    db.select(ChannelTable.selectAllSql)
      .iterable(_ byteVec ChannelTable.data)
      .map(bits =>
        persistentChannelDataCodec.decode(bits.toBitVector).require.value
      )

  override def delete(channelId: ByteVector32): Unit =
    db.change(ChannelTable.killSql, channelId.toHex)

  // HTLC infos
  override def htlcInfos(
      commitNumer: Long
  ): Iterable[ChannelBag.Hash160AndCltv] =
    db.select(HtlcInfoTable.selectAllSql, commitNumer.toString).iterable { rc =>
      val cltvExpiry = CltvExpiry(rc.int(HtlcInfoTable.cltvExpiry))
      val hash160 = rc.byteVec(HtlcInfoTable.paymentHash160)
      ChannelBag.Hash160AndCltv(hash160, cltvExpiry)
    }

  override def putHtlcInfo(
      sid: Long,
      commitNumber: Long,
      paymentHash: ByteVector32,
      cltvExpiry: CltvExpiry
  ): Unit =
    db.change(
      HtlcInfoTable.newSql,
      sid: JLong,
      commitNumber: JLong,
      Crypto.ripemd160(paymentHash).toArray,
      cltvExpiry.toLong: JLong
    )

  override def putHtlcInfos(
      htlcs: Seq[DirectedHtlc],
      sid: Long,
      commitNumber: Long
  ): Unit = db txWrap {
    for (htlc <- htlcs)
      putHtlcInfo(sid, commitNumber, htlc.add.paymentHash, htlc.add.cltvExpiry)
  }

  override def rmHtlcInfos(scid: ShortChannelId): Unit =
    db.change(HtlcInfoTable.killSql, scid.toLong: JLong)

  // Channel related tx fees
  def channelTxFeesSummary: Try[ChannelTxFeesSummary] =
    channelTxFeesDb.select(ChannelTxFeesTable.selectSummarySql).headTry { rc =>
      ChannelTxFeesSummary(fees = Satoshi(rc.long(0)), count = rc.long(1))
    }

  def addChannelTxFee(feePaid: Satoshi, idenitifer: String, tag: String): Unit =
    channelTxFeesDb.change(
      ChannelTxFeesTable.newSql,
      idenitifer,
      tag,
      feePaid.toLong: JLong
    )
}
