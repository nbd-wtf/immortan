package immortan.sqlite

import java.lang.{Integer => JInt, Long => JLong}
import scodec.bits.{ByteVector, BitVector}
import scoin._
import scoin.Crypto.PublicKey
import scoin.ln._
import scoin.ln.LightningMessageCodecs.channelFlagsCodec
import scoin.ln.{ChannelAnnouncement, ChannelUpdate}

import immortan.router.Router.PublicChannel
import immortan.router.{ChannelUpdateExt, Sync}
import immortan._

class SQLiteNetwork(
    val db: DBInterface,
    val updateTable: ChannelUpdateTable,
    val announceTable: ChannelAnnouncementTable,
    val excludedTable: ExcludedChannelTable
) extends NetworkBag {
  def addChannelAnnouncement(
      ca: ChannelAnnouncement,
      newSqlPQ: PreparedQuery
  ): Unit = db.change(
    newSqlPQ,
    Array.emptyByteArray,
    ca.shortChannelId.toLong: JLong,
    ca.nodeId1.value.toArray,
    ca.nodeId2.value.toArray
  )

  def addExcludedChannel(
      scid: ShortChannelId,
      untilStamp: Long,
      newSqlPQ: PreparedQuery
  ): Unit = db.change(
    newSqlPQ,
    scid.toLong: JLong,
    System.currentTimeMillis + untilStamp: JLong
  )

  def listExcludedChannels: Set[ShortChannelId] = db
    .select(excludedTable.selectSql, System.currentTimeMillis.toString)
    .set(rc => ShortChannelId(rc.long(excludedTable.shortChannelId)))

  def listChannelsWithOneUpdate: Set[ShortChannelId] =
    db.select(updateTable.selectHavingOneUpdate)
      .set(rc => ShortChannelId(rc.long(updateTable.sid)))

  def incrementScore(cu: ChannelUpdateExt): Unit =
    db.change(updateTable.updScoreSql, cu.update.shortChannelId.toLong: JLong)

  def removeChannelUpdate(
      scid: ShortChannelId,
      killSqlPQ: PreparedQuery
  ): Unit =
    db.change(killSqlPQ, scid.toLong: JLong)

  def addChannelUpdateByPosition(
      cu: ChannelUpdate,
      newSqlPQ: PreparedQuery,
      updSqlPQ: PreparedQuery
  ): Unit = {
    val feeProportionalMillionths: JLong = cu.feeProportionalMillionths
    val cltvExpiryDelta: JInt = cu.cltvExpiryDelta.toInt
    val htlcMinimumMsat: JLong = cu.htlcMinimumMsat.toLong
    val htlcMaxMsat: JLong = cu.htlcMaximumMsat.toLong
    val messageFlags: JInt = 1 // ByteVector.fromHex("01").toByte.toInt
    val channelFlags: JInt =
      channelFlagsCodec.encode(cu.channelFlags).require.bytes.head.toInt
    val feeBaseMsat: JLong = cu.feeBaseMsat.toLong
    val timestamp: JLong = cu.timestamp.toLong

    val crc32: JLong = Sync.getChecksum(cu)
    db.change(
      newSqlPQ,
      cu.shortChannelId.toLong: JLong,
      timestamp,
      messageFlags,
      channelFlags,
      cltvExpiryDelta,
      htlcMinimumMsat,
      feeBaseMsat,
      feeProportionalMillionths,
      htlcMaxMsat,
      cu.position: JInt,
      1L: JLong,
      crc32
    )
    db.change(
      updSqlPQ,
      timestamp,
      messageFlags,
      channelFlags,
      cltvExpiryDelta,
      htlcMinimumMsat,
      feeBaseMsat,
      feeProportionalMillionths,
      htlcMaxMsat,
      crc32,
      cu.shortChannelId.toLong: JLong,
      cu.position: JInt
    )
  }

  def removeChannelUpdate(scid: ShortChannelId): Unit = {
    val removeChannelUpdateNewSqlPQ = db.makePreparedQuery(updateTable.killSql)
    removeChannelUpdate(scid, removeChannelUpdateNewSqlPQ)
    removeChannelUpdateNewSqlPQ.close()
  }

  def addChannelUpdateByPosition(cu: ChannelUpdate): Unit = {
    val addChannelUpdateByPositionNewSqlPQ =
      db.makePreparedQuery(updateTable.newSql)
    val addChannelUpdateByPositionUpdSqlPQ =
      db.makePreparedQuery(updateTable.updSQL)
    addChannelUpdateByPosition(
      cu,
      addChannelUpdateByPositionNewSqlPQ,
      addChannelUpdateByPositionUpdSqlPQ
    )
    addChannelUpdateByPositionNewSqlPQ.close()
    addChannelUpdateByPositionUpdSqlPQ.close()
  }

  def listChannelAnnouncements: Iterable[ChannelAnnouncement] =
    db.select(announceTable.selectAllSql).iterable { rc =>
      ChannelAnnouncement(
        nodeSignature1 = ByteVector64.Zeroes,
        nodeSignature2 = ByteVector64.Zeroes,
        bitcoinSignature1 = ByteVector64.Zeroes,
        bitcoinSignature2 = ByteVector64.Zeroes,
        features = Features.empty,
        chainHash = LNParams.chainHash,
        shortChannelId = ShortChannelId(rc long announceTable.shortChannelId),
        nodeId1 = PublicKey(rc byteVec announceTable.nodeId1),
        nodeId2 = PublicKey(rc byteVec announceTable.nodeId2),
        bitcoinKey1 = invalidPubKey,
        bitcoinKey2 = invalidPubKey
      )
    }

  def listChannelUpdates: Iterable[ChannelUpdateExt] =
    db.select(updateTable.selectAllSql).iterable { rc =>
      val htlcMaximumMsat: MilliSatoshi = MilliSatoshi(rc long 9)
      ChannelUpdateExt(
        ChannelUpdate(
          signature = ByteVector64.Zeroes,
          chainHash = LNParams.chainHash,
          shortChannelId = ShortChannelId(rc long 1),
          timestamp = TimestampSecond(rc long 2),
          // messageFlags = (rc int 3).toByte,
          channelFlags = channelFlagsCodec
            .decode(BitVector(Array((rc int 4).toByte)))
            .require
            .value,
          cltvExpiryDelta = CltvExpiryDelta(rc int 5),
          htlcMinimumMsat = MilliSatoshi(rc long 6),
          feeBaseMsat = MilliSatoshi(rc long 7),
          feeProportionalMillionths = rc long 8,
          htlcMaximumMsat = htlcMaximumMsat
        ),
        crc32 = rc long 12,
        score = rc long 11,
        updateTable.useHeuristics
      )
    }

  def getRoutingData: Map[ShortChannelId, PublicChannel] = {
    val updatesByScid = listChannelUpdates.groupBy(_.update.shortChannelId)

    val tuples = listChannelAnnouncements.flatMap { ann =>
      updatesByScid.get(ann.shortChannelId).collectFirst {
        case List(u1, u2) if 1 == u1.update.position =>
          ann.shortChannelId -> PublicChannel(Some(u1), Some(u2), ann)
        case List(u2, u1) if 2 == u2.update.position =>
          ann.shortChannelId -> PublicChannel(Some(u1), Some(u2), ann)
        case List(u1) if 1 == u1.update.position =>
          ann.shortChannelId -> PublicChannel(Some(u1), None, ann)
        case List(u2) if 2 == u2.update.position =>
          ann.shortChannelId -> PublicChannel(None, Some(u2), ann)
      }
    }

    tuples.toMap
  }

  def removeGhostChannels(
      ghostScids: Set[ShortChannelId],
      oneSideScids: Set[ShortChannelId]
  ): Unit = db txWrap {
    val addExcludedChannelNewSqlPQ = db.makePreparedQuery(excludedTable.newSql)
    val removeChannelUpdateNewSqlPQ = db.makePreparedQuery(updateTable.killSql)

    for (scid <- oneSideScids)
      addExcludedChannel(
        scid,
        1000L * 3600 * 24 * 14,
        addExcludedChannelNewSqlPQ
      ) // Exclude for two weeks, maybe second update will show up later
    for (scid <- ghostScids ++ oneSideScids)
      removeChannelUpdate(
        scid,
        removeChannelUpdateNewSqlPQ
      ) // Make sure we only have known channels with both updates

    addExcludedChannelNewSqlPQ.close()
    removeChannelUpdateNewSqlPQ.close()

    db.change(
      excludedTable.killPresentInChans
    ) // Remove from excluded if present in channels (minority says it's bad, majority says it's good)
    db.change(
      announceTable.killNotPresentInChans
    ) // Remove from announces if not present in channels (announce for excluded channel)
    db.change(
      excludedTable.killOldSql,
      System.currentTimeMillis: JLong
    ) // Give old excluded channels a second chance
  }

  def processPureData(pure: PureRoutingData): Unit = db txWrap {
    val addChannelAnnouncementNewSqlPQ =
      db.makePreparedQuery(announceTable.newSql)
    val addChannelUpdateByPositionNewSqlPQ =
      db.makePreparedQuery(updateTable.newSql)
    val addChannelUpdateByPositionUpdSqlPQ =
      db.makePreparedQuery(updateTable.updSQL)
    val addExcludedChannelNewSqlPQ = db.makePreparedQuery(excludedTable.newSql)

    for (announce <- pure.announces)
      addChannelAnnouncement(announce, addChannelAnnouncementNewSqlPQ)
    for (update <- pure.updates)
      addChannelUpdateByPosition(
        update,
        addChannelUpdateByPositionNewSqlPQ,
        addChannelUpdateByPositionUpdSqlPQ
      )
    for (core <- pure.excluded)
      addExcludedChannel(
        core.shortChannelId,
        1000L * 3600 * 24 * 3650,
        addExcludedChannelNewSqlPQ
      )

    addChannelAnnouncementNewSqlPQ.close()
    addChannelUpdateByPositionNewSqlPQ.close()
    addChannelUpdateByPositionUpdSqlPQ.close()
    addExcludedChannelNewSqlPQ.close()
  }

  def processCompleteHostedData(pure: CompleteHostedRoutingData): Unit =
    db txWrap {
      // Unlike normal channels here we allow one-sided-update channels to be used for now
      // First, clear out everything in hosted channel databases
      clearDataTables()

      val addChannelAnnouncementNewSqlPQ =
        db.makePreparedQuery(announceTable.newSql)
      val addChannelUpdateByPositionNewSqlPQ =
        db.makePreparedQuery(updateTable.newSql)
      val addChannelUpdateByPositionUpdSqlPQ =
        db.makePreparedQuery(updateTable.updSQL)

      // Then insert new data
      for (announce <- pure.announces)
        addChannelAnnouncement(announce, addChannelAnnouncementNewSqlPQ)
      for (update <- pure.updates)
        addChannelUpdateByPosition(
          update,
          addChannelUpdateByPositionNewSqlPQ,
          addChannelUpdateByPositionUpdSqlPQ
        )

      addChannelAnnouncementNewSqlPQ.close()
      addChannelUpdateByPositionNewSqlPQ.close()
      addChannelUpdateByPositionUpdSqlPQ.close()

      // And finally remove announces without any updates
      db.change(announceTable.killNotPresentInChans)
    }

  def clearDataTables(): Unit = {
    db.change(announceTable.killAllSql)
    db.change(updateTable.killAllSql)
  }
}
