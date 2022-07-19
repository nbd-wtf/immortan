package immortan.sqlite

trait DBInterface {
  def txWrap[T](run: => T): T
  def change(sql: String, params: Array[Object]): Unit
  def change(prepared: PreparedQuery, params: Array[Object]): Unit
  def select(sql: String, params: Array[String] = Array.empty): RichCursor
  def search(sqlSelectQuery: String, rawQuery: String): RichCursor =
    select(sqlSelectQuery, Array(s"${rawQuery.replaceAll("'", "\\'").trim}*"))
  def makePreparedQuery(sql: String): PreparedQuery
}

case class DBInterfaceSQLiteGeneral(connection: java.sql.Connection)
    extends DBInterface {
  def change(sql: String, params: Array[Object]): Unit = {
    val stmt = makePreparedQuery(sql)
    stmt.bound(params).executeUpdate()
    stmt.close()
  }

  override def change(prepared: PreparedQuery, params: Array[Object]): Unit = {
    prepared.bound(params).executeUpdate()
  }

  def select(sql: String, params: Array[String]): RichCursor = {
    val stmt = makePreparedQuery(sql)
    val res = stmt.bound(params).executeQuery()
    stmt.close()
    res
  }

  def makePreparedQuery(sql: String): PreparedQuery =
    PreparedQuerySQLiteGeneral(connection.prepareStatement(sql))

  def txWrap[T](run: => T): T = {
    val old = connection.getAutoCommit
    connection.setAutoCommit(false)

    try {
      val runResult = run
      connection.commit
      runResult
    } catch {
      case error: Throwable =>
        connection.rollback
        throw error
    } finally {
      connection.setAutoCommit(old)
    }
  }
}
