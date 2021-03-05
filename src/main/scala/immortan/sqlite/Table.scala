package immortan.sqlite

trait Table {
  val (id, fts) = "_id" -> "fts4"
  def createStatements: Seq[String]
}

object ChannelTable extends Table {
  val (table, channelId, isRemoved, data) = ("channel", "chanid", "isremoved", "data")
  val selectAllSql = s"SELECT * FROM $table WHERE $isRemoved = 0 ORDER BY $id DESC"
  val newSql = s"INSERT OR IGNORE INTO $table ($channelId, $data) VALUES (?, ?)"
  val hideSql = s"UPDATE $table SET $isRemoved = 1 WHERE $channelId = ?"
  val updSql = s"UPDATE $table SET $data = ? WHERE $channelId = ?"
  val killSql = s"DELETE FROM $table WHERE WHERE $channelId = ?"

  def createStatements: Seq[String] =
    s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $channelId TEXT NOT NULL UNIQUE,
      $isRemoved INTEGER NOT NULL,
      $data BLOB NOT NULL
    )""" :: Nil
}

object HtlcInfoTable extends Table {
  val (table, sid, commitNumber, paymentHash160, cltvExpiry) = ("htlcinfo", "sid", "commitnumber", "hash160", "cltv")
  val newSql = s"INSERT INTO $table ($sid, $commitNumber, $paymentHash160, $cltvExpiry) VALUES (?, ?, ?, ?)"
  val selectAllSql = s"SELECT * FROM $table WHERE $commitNumber = ?"
  val killSql = s"DELETE FROM $table WHERE $sid = ?"

  def createStatements: Seq[String] = {
    val createTable = s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $sid INTEGER NOT NULL,
      $commitNumber INTEGER NOT NULL, $paymentHash160 BLOB NOT NULL,
      $cltvExpiry INTEGER NOT NULL
    )"""

    // Index can not be unique because for each commit we may have same local or remote number
    val addIndex1 = s"CREATE INDEX IF NOT EXISTS idx1$table ON $table ($commitNumber)"
    val addIndex2 = s"CREATE INDEX IF NOT EXISTS idx2$table ON $table ($sid)"
    createTable :: addIndex1 :: addIndex2 :: Nil
  }
}

object RelayPreimageTable extends Table {
  val (table, hash, preimage, stamp, relayed, earned) = ("relaypreimage", "hash", "preimage", "stamp", "relayed", "earned")
  val newSql = s"INSERT OR IGNORE INTO $table ($hash, $preimage, $stamp, $relayed, $earned) VALUES (?, ?, ?, ?, ?)"
  val selectSummarySql = s"SELECT SUM($relayed), SUM($earned), COUNT($id) FROM $table"
  val selectRecentSql = s"SELECT * FROM $table ORDER BY $id DESC LIMIT 5"
  val selectByHashSql = s"SELECT * FROM $table WHERE $hash = ?"

  def createStatements: Seq[String] =
    s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $hash TEXT NOT NULL UNIQUE,
      $preimage TEXT NOT NULL, $stamp INTEGER NOT NULL, $relayed INTEGER NOT NULL,
      $earned INTEGER NOT NULL
    )""" :: Nil
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
      $chanFlags INTEGER NOT NULL, $cltvExpiryDelta INTEGER NOT NULL, $minMsat INTEGER NOT NULL, $base INTEGER NOT NULL,
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
  import immortan.PaymentStatus.SUCCEEDED
  private val paymentTableFields = ("search", "payment", "pr", "preimage", "status", "stamp", "desc", "action", "hash", "received", "sent", "fee", "balance", "fiatrates", "chainfee", "incoming")
  val (search, table, pr, preimage, status, stamp, description, action, hash, receivedMsat, sentMsat, feeMsat, balanceMsat, fiatRates, chainFee, incoming) = paymentTableFields
  val inserts = s"$pr, $preimage, $status, $stamp, $description, $action, $hash, $receivedMsat, $sentMsat, $feeMsat, $balanceMsat, $fiatRates, $chainFee, $incoming"
  val newSql = s"INSERT OR IGNORE INTO $table ($inserts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
  val newVirtualSql = s"INSERT INTO $fts$table ($search, $hash) VALUES (?, ?)"
  val deleteSql = s"DELETE FROM $table WHERE $hash = ?"

  // Selecting
  val selectOneSql = s"SELECT * FROM $table WHERE $hash = ?"
  val selectRecentSql = s"SELECT * FROM $table ORDER BY $id DESC LIMIT 10 WHERE $stamp > 0"
  val selectSummarySql = s"SELECT SUM($feeMsat), SUM($receivedMsat), SUM($sentMsat), COUNT($id) FROM $table WHERE $status = $SUCCEEDED"
  val searchSql = s"SELECT * FROM $table WHERE $hash IN (SELECT $hash FROM $fts$table WHERE $search MATCH ? LIMIT 25)"

  // Updating
  val updOkOutgoingSql = s"UPDATE $table SET $status = $SUCCEEDED, $preimage = ?, $feeMsat = ? WHERE $hash = ? AND ($stamp > 0 AND status <> $SUCCEEDED) AND $incoming = 0"
  val updOkIncomingSql = s"UPDATE $table SET $status = $SUCCEEDED, $receivedMsat = ?, $stamp = ? WHERE $hash = ? AND ($stamp > 0 AND status <> $SUCCEEDED) AND $incoming = 1"
  val updStatusSql = s"UPDATE $table SET $status = ? WHERE $hash = ? AND ($stamp > 0 AND status <> $SUCCEEDED)"

  def createStatements: Seq[String] = {
    val createTable = s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $pr TEXT NOT NULL, $preimage TEXT NOT NULL, $status TEXT NOT NULL, $stamp INTEGER NOT NULL,
      $description TEXT NOT NULL, $action TEXT NOT NULL, $hash TEXT NOT NULL UNIQUE,$receivedMsat INTEGER NOT NULL, $sentMsat INTEGER NOT NULL,
      $feeMsat INTEGER NOT NULL, $balanceMsat INTEGER NOT NULL, $fiatRates TEXT NOT NULL, $chainFee INTEGER NOT NULL, $incoming INTEGER NOT NULL
    )"""

    // Once incoming or outgoing payment is settled we can search it by various metadata
    val addIndex1 = s"CREATE VIRTUAL TABLE IF NOT EXISTS $fts$table USING $fts($search, $hash)"
    val addIndex2 = s"CREATE INDEX IF NOT EXISTS idx2$table ON $table ($stamp, $status)"
    createTable :: addIndex1 :: addIndex2 :: Nil
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

object DataTable extends Table {
  val (table, label, content) = ("data", "label", "content")
  val newSql = s"INSERT OR IGNORE INTO $table ($label, $content) VALUES (?, ?)"
  val updSql = s"UPDATE $table SET $content = ? WHERE $label = ?"
  val selectSql = s"SELECT * FROM $table WHERE $label = ?"
  val killSql = s"DELETE FROM $table WHERE $label = ?"

  def createStatements: Seq[String] =
    s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $label TEXT NOT NULL UNIQUE,
      $content BLOB NOT NULL
    )""" :: Nil
}

object ElectrumHeadersTable extends Table {
  val (table, height, blockHash, header) = ("headers", "height", "blockhash", "header")
  val addHeaderSql = s"INSERT OR IGNORE INTO $table ($height, $blockHash, $header) VALUES (?, ?, ?)"

  val selectHeaderByHeightSql = s"SELECT * FROM $table WHERE $height = ?"
  val selectByBlockHashSql = s"SELECT * FROM $table WHERE $blockHash = ?"
  val selectHeadersSql = s"SELECT * FROM $table WHERE $height >= ? ORDER BY $height LIMIT ?"
  val selectTipSql = s"SELECT * FROM $table INNER JOIN (SELECT MAX($height) AS maxHeight FROM $table) t1 ON $height = t1.maxHeight"

  def createStatements: Seq[String] =
    s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $height INTEGER NOT NULL UNIQUE,
      $blockHash TEXT NOT NULL,
      $header BLOB NOT NULL
    )""" :: Nil
}

object PayMarketTable extends Table {
  val (table, search, lnurl, text, lastMsat, lastDate, hash, image) = ("paymarket", "search", "lnurl", "text", "lastmsat", "lastdate", "hash", "image")
  val newSql = s"INSERT OR IGNORE INTO $table ($lnurl, $text, $lastMsat, $lastDate, $hash, $image) VALUES (?, ?, ?, ?, ?, ?)"
  val newVirtualSql = s"INSERT INTO $fts$table ($search, $lnurl) VALUES (?, ?)"

  val selectRecentSql = s"SELECT * FROM $table ORDER BY $lastDate DESC LIMIT 50"
  val searchSql = s"SELECT * FROM $table WHERE $lnurl IN (SELECT $lnurl FROM $fts$table WHERE $search MATCH ?) LIMIT 100"
  val updInfoSql = s"UPDATE $table SET $text = ?, $lastMsat = ?, $lastDate = ?, $hash = ?, $image = ? WHERE $lnurl = ?"
  val killSql = s"DELETE FROM $table WHERE $lnurl = ?"

  def createStatements: Seq[String] = {
    val createTable = s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $lnurl STRING NOT NULL UNIQUE,
      $text STRING NOT NULL, $lastMsat INTEGER NOT NULL, $lastDate INTEGER NOT NULL,
      $hash STRING NOT NULL, $image STRING NOT NULL
    )"""

    // Payment links are searchable by their text descriptions (text metadata + domain name)
    val addIndex1 = s"CREATE VIRTUAL TABLE IF NOT EXISTS $fts$table USING $fts($search, $lnurl)"
    val addIndex2 = s"CREATE INDEX IF NOT EXISTS idx1$table ON $table ($lastDate)"
    createTable :: addIndex1 :: addIndex2 :: Nil
  }
}