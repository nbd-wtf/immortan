package immortan


trait Table {
  val (id, fts) = "_id" -> "fts4"
  def createStatements: Seq[String]
}

object ChannelTable extends Table {
  val (table, identifier, isRemoved, data) = ("channel", "identifier", "isremoved", "data")
  val selectAllSql = s"SELECT * FROM $table WHERE $isRemoved = 0 ORDER BY $id DESC"
  val newSql = s"INSERT OR IGNORE INTO $table ($identifier, $data) VALUES (?, ?)"
  val hideSql = s"UPDATE $table SET $isRemoved = 1 WHERE $identifier = ?"
  val updSql = s"UPDATE $table SET $data = ? WHERE $identifier = ?"
  val killSql = s"DELETE FROM $table WHERE WHERE $identifier = ?"

  def createStatements: Seq[String] =
    s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $identifier TEXT NOT NULL UNIQUE,
      $isRemoved INTEGER NOT NULL,
      $data BLOB NOT NULL
    )""" :: Nil
}

object HtlcInfoTable extends Table {
  val (table, channelId, commitNumber, paymentHash, cltvExpiry) = ("htlcinfo", "chanid", "commitnumber", "hash", "cltv")
  val selectAllSql = s"SELECT $paymentHash, $cltvExpiry FROM $table WHERE $channelId = ? AND $commitNumber = ?"
  val newSql = s"INSERT INTO $table VALUES (?, ?, ?, ?)"

  def createStatements: Seq[String] = {
    val createTable = s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $channelId BLOB NOT NULL,
      $commitNumber INTEGER NOT NULL, $paymentHash BLOB NOT NULL,
      $cltvExpiry BLOB NOT NULL
    )"""

    // Index can not be unique because for each commit we may have same local or remote number
    val addIndex = s"CREATE INDEX IF NOT EXISTS idx1$table ON $table ($channelId, $commitNumber)"
    createTable :: addIndex :: Nil
  }
}

abstract class ChannelAnnouncementTable(val table: String) extends Table {
  val (features, shortChannelId, nodeId1, nodeId2) = ("features", "shortchannelid", "nodeid1", "nodeid2")
  val newSql = s"INSERT OR IGNORE INTO $table ($features, $shortChannelId, $nodeId1, $nodeId2) VALUES (?, ?, ?, ?)"
  val selectAllSql = s"SELECT * FROM $table"
  val killAllSql = s"DELETE * FROM $table"
  val killNotPresentInChans: String

  def createStatements: Seq[String] =
    s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $features BLOB NOT NULL,
      $shortChannelId INTEGER NOT NULL UNIQUE, $nodeId1 BLOB NOT NULL,
      $nodeId2 BLOB NOT NULL
    )""" :: Nil
}

object NormalChannelAnnouncementTable extends ChannelAnnouncementTable("normal_announcements") {
  private val select = s"SELECT ${NormalChannelUpdateTable.sid} FROM ${NormalChannelUpdateTable.table}"
  val killNotPresentInChans = s"DELETE FROM $table WHERE $shortChannelId NOT IN ($select LIMIT 1000000)"
}

object HostedChannelAnnouncementTable extends ChannelAnnouncementTable("hosted_announcements") {
  private val select = s"SELECT ${HostedChannelUpdateTable.sid} FROM ${HostedChannelUpdateTable.table}"
  val killNotPresentInChans = s"DELETE FROM $table WHERE $shortChannelId NOT IN ($select LIMIT 1000000)"
}

abstract class ChannelUpdateTable(val table: String, val useHeuristics: Boolean) extends Table {
  private val names = ("shortchannelid", "timestamp", "messageflags", "channelflags", "cltvdelta", "htlcminimum", "feebase", "feeproportional", "htlcmaximum", "position", "score", "crc32")
  val (sid, timestamp, msgFlags, chanFlags, cltvExpiryDelta, minMsat, base, proportional, maxMsat, position, score, crc32) = names

  val updScoreSql = s"UPDATE $table SET $score = $score + 1 WHERE $sid = ? AND $position = ?"
  val updSQL = s"UPDATE $table SET $timestamp = ?, $msgFlags = ?, $chanFlags = ?, $cltvExpiryDelta = ?, $minMsat = ?, $base = ?, $proportional = ?, $maxMsat = ?, $crc32 = ? WHERE $sid = ? AND $position = ?"
  val newSql = s"INSERT OR IGNORE INTO $table ($sid, $timestamp, $msgFlags, $chanFlags, $cltvExpiryDelta, $minMsat, $base, $proportional, $maxMsat, $position, $score, $crc32) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
  val selectHavingOneUpdate = s"SELECT $sid FROM $table GROUP BY $sid HAVING COUNT($sid) < 2"
  val selectAllSql = s"SELECT * FROM $table"

  val killSql = s"DELETE FROM $table WHERE $sid = ?"
  val killAllSql = s"DELETE * FROM $table"

  def createStatements: Seq[String] = {
    val createTable = s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $sid INTEGER NOT NULL, $timestamp INTEGER NOT NULL, $msgFlags INTEGER NOT NULL,
      $chanFlags INTEGER NOT NULL, $cltvExpiryDelta INTEGER NOT NULL, $minMsat INTEGER NOT NULL,$base INTEGER NOT NULL,
      $proportional INTEGER NOT NULL, $maxMsat INTEGER NOT NULL, $position INTEGER NOT NULL,
      $score INTEGER NOT NULL, $crc32 INTEGER NOT NULL
    )"""

    // For each channel we have up to two unique updates indexed by nodeId position
    val addIndex = s"CREATE UNIQUE INDEX IF NOT EXISTS idx1$table ON $table ($sid, $position)"
    createTable :: addIndex :: Nil
  }
}

object NormalChannelUpdateTable extends ChannelUpdateTable("normal_updates", useHeuristics = true)
object HostedChannelUpdateTable extends ChannelUpdateTable("hosted_updates", useHeuristics = false)

abstract class ExcludedChannelTable(val table: String) extends Table {
  val Tuple2(shortChannelId, until) = ("shortchannelid", "excludeduntilstamp")
  val newSql = s"INSERT OR IGNORE INTO $table ($shortChannelId, $until) VALUES (?, ?)"
  val selectSql = s"SELECT * FROM $table WHERE $until > ? LIMIT 1000000"
  val killOldSql = s"DELETE FROM $table WHERE $until < ?"
  val killPresentInChans: String

  def createStatements: Seq[String] = {
    val createTable = s"CREATE TABLE IF NOT EXISTS $table($id INTEGER PRIMARY KEY AUTOINCREMENT, $shortChannelId INTEGER NOT NULL UNIQUE, $until INTEGER NOT NULL)"
    // Excluded channels expire to give them second chance (e.g. channels with one update, channels without max sendable amount)
    val addIndex = s"CREATE INDEX IF NOT EXISTS idx1$table ON $table ($until)"
    createTable :: addIndex :: Nil
  }
}

object NormalExcludedChannelTable extends ExcludedChannelTable("normal_excluded_updates") {
  private val select = s"SELECT ${NormalChannelUpdateTable.sid} FROM ${NormalChannelUpdateTable.table}"
  val killPresentInChans = s"DELETE FROM $table WHERE $shortChannelId IN ($select LIMIT 1000000)"
}

object HostedExcludedChannelTable extends ExcludedChannelTable("hosted_excluded_updates") {
  private val select = s"SELECT ${HostedChannelUpdateTable.sid} FROM ${HostedChannelUpdateTable.table}"
  val killPresentInChans = s"DELETE FROM $table WHERE $shortChannelId IN ($select LIMIT 1000000)"
}

object PaymentTable extends Table {
  import immortan.PaymentStatus.{HIDDEN, SUCCEEDED}
  private val paymentTableFields = ("search", "payment", "nodeid", "pr", "preimage", "status", "stamp", "desc", "action", "hash", "received", "sent", "fee", "balance", "fiatrates", "feerate", "incoming", "ext")
  val (search, table, nodeId, pr, preimage, status, stamp, description, action, hash, receivedMsat, sentMsat, feeMsat, balanceMsat, fiatRates, feeRateMsat, incoming, ext) = paymentTableFields
  val inserts = s"$nodeId, $pr, $preimage, $status, $stamp, $description, $action, $hash, $receivedMsat, $sentMsat, $feeMsat, $balanceMsat, $fiatRates, $feeRateMsat, $incoming, $ext"
  val newSql = s"INSERT OR IGNORE INTO $table ($inserts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
  val newVirtualSql = s"INSERT INTO $fts$table ($search, $hash) VALUES (?, ?)"
  val deleteSql = s"DELETE FROM $table WHERE $hash = ?"

  // Selecting
  val selectOneSql = s"SELECT * FROM $table WHERE $hash = ?"
  val selectRecentSql = s"SELECT * FROM $table ORDER BY $id DESC LIMIT 10 WHERE ($stamp > 0 AND status <> $HIDDEN)"
  val selectToNodeSummarySql = s"SELECT SUM($feeMsat), SUM($sentMsat), COUNT($id) FROM $table WHERE $nodeId = ? AND $status = $SUCCEEDED"
  val selectBetweenSummarySql = s"SELECT SUM($feeMsat), SUM($receivedMsat), SUM($sentMsat), COUNT($id) FROM $table WHERE ($stamp > ? AND $stamp < ? AND $status = $SUCCEEDED)"
  val searchSql = s"SELECT * FROM $table WHERE $hash IN (SELECT $hash FROM $fts$table WHERE $search MATCH ? LIMIT 25)" // Only successful outgoing payments should be present here

  // Updating
  val updOkOutgoingSql = s"UPDATE $table SET $status = $SUCCEEDED, $preimage = ?, $feeMsat = ? WHERE $hash = ? AND ($stamp > 0 AND status <> $SUCCEEDED) AND $incoming = 0"
  val updParamsIncomingSql = s"UPDATE $table SET $status = ?, $receivedMsat = ?, $stamp = ? WHERE $hash = ? AND ($stamp > 0 AND status <> $SUCCEEDED) AND $incoming = 1"
  val updStatusSql = s"UPDATE $table SET $status = ? WHERE $hash = ? AND ($stamp > 0 AND status <> $SUCCEEDED AND status <> $HIDDEN)"

  def createStatements: Seq[String] = {
    val createTable = s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $nodeId TEXT NOT NULL, $pr TEXT NOT NULL, $preimage TEXT NOT NULL,
      $status TEXT NOT NULL, $stamp INTEGER NOT NULL, $description TEXT NOT NULL, $action TEXT NOT NULL, $hash TEXT NOT NULL UNIQUE,
      $receivedMsat INTEGER NOT NULL, $sentMsat INTEGER NOT NULL, $feeMsat INTEGER NOT NULL, $balanceMsat INTEGER NOT NULL,
      $fiatRates TEXT NOT NULL, $feeRateMsat INTEGER NOT NULL, $incoming INTEGER NOT NULL, $ext TEXT NOT NULL
    )"""

    // Once incoming or outgoing payment is settled we can search it by various metadata
    val addIndex1 = s"CREATE VIRTUAL TABLE IF NOT EXISTS $fts$table USING $fts($search, $hash)"
    val addIndex2 = s"CREATE INDEX IF NOT EXISTS idx1$table ON $table ($nodeId, $status)"
    val addIndex3 = s"CREATE INDEX IF NOT EXISTS idx2$table ON $table ($stamp, $status)"
    createTable :: addIndex1 :: addIndex2 :: addIndex3 :: Nil
  }
}

object TxTable extends Table {
  private val paymentTableFields = ("txs", "txid", "depth", "received", "sent", "fee", "seen", "completed", "desc", "balance", "fiatrates", "incoming", "doublespent")
  val (table, txid, depth, receivedMsat, sentMsat, feeMsat, firstSeen, completedAt, description, balanceMsat, fiatRates, incoming, doubleSpent) = paymentTableFields
  val inserts = s"$txid, $depth, $receivedMsat, $sentMsat, $feeMsat, $firstSeen, $completedAt, $description, $balanceMsat, $fiatRates, $incoming, $doubleSpent"
  val newSql = s"INSERT OR IGNORE INTO $table ($inserts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
  val selectRecentSql = s"SELECT * FROM $table ORDER BY $id DESC LIMIT 10"

  val updDoubleSpentSql = s"UPDATE $table SET $doubleSpent = ? WHERE $txid = ?"
  val updCompletedAtSql = s"UPDATE $table SET $completedAt = ? WHERE $txid = ?"
  val updDepthSql = s"UPDATE $table SET $depth = ? WHERE $txid = ?"

  def createStatements: Seq[String] =
    s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $txid TEXT NOT NULL UNIQUE, $depth INTEGER NOT NULL,
      $receivedMsat INTEGER NOT NULL, $sentMsat INTEGER NOT NULL, $feeMsat INTEGER NOT NULL, $firstSeen INTEGER NOT NULL,
      $completedAt INTEGER NOT NULL, $description TEXT NOT NULL, $balanceMsat INTEGER NOT NULL, $fiatRates TEXT NOT NULL,
      $incoming INTEGER NOT NULL, $doubleSpent INTEGER NOT NULL
    )""" :: Nil
}

object ElectrumHeadersTable extends Table {
  val (table, height, blockHash, header) = ("headers", "height", "blockhash", "header")
  val addHeaderSql = s"INSERT OR IGNORE INTO $table VALUES (?, ?, ?)"

  val selectHeaderByHeightSql = s"SELECT $header FROM $table WHERE $height = ?"
  val selectByBlockHashSql = s"SELECT $height, $header FROM $table WHERE $blockHash = ?"
  val selectHeadersSql = s"SELECT $height, $header FROM $table WHERE $height >= ? ORDER BY $height LIMIT ?"
  val selectTipSql = s"SELECT $height, $header FROM $table INNER JOIN (SELECT MAX($height) AS maxHeight FROM $table) t1 ON $height = t1.maxHeight"

  def createStatements: Seq[String] =
    s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $height INTEGER NOT NULL UNIQUE,
      $blockHash BLOB NOT NULL,
      $header BLOB NOT NULL
    )""" :: Nil
}