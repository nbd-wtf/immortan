package immortan.sqlite

import fr.acinq.eclair.channel.{NormalCommits, PersistentChannelData}
import immortan.{ChannelBag, ChannelTable, HostedCommits}
import fr.acinq.eclair.wire.ChannelCodecs


class SQLiteChannel(db: DBInterface) extends ChannelBag {
  override def delete(commitments: HostedCommits): Unit = db.change(ChannelTable.killSql, commitments.channelId.toHex)

  override def hide(commitments: NormalCommits): Unit = db.change(ChannelTable.hideSql, commitments.channelId.toHex)

  def all: List[PersistentChannelData] =
    db.select(ChannelTable.selectAllSql).iterable(_ bitVec ChannelTable.data).toList
      .map(bits => ChannelCodecs.persistentDataCodec.decode(bits).require.value)

  def put(data: PersistentChannelData): PersistentChannelData = {
    val dataArray = ChannelCodecs.persistentDataCodec.encode(data).require.toByteArray
    db.change(ChannelTable.newSql, data.channelId.toHex, dataArray)
    db.change(ChannelTable.updSql, dataArray, data.channelId.toHex)
    data
  }
}