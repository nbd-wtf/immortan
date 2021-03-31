package immortan.sqlite


trait DBInterface {
  def txWrap[T](run: => T): T

  def change(sql: String, params: Object*): Unit

  def change(prepared: PreparedQuery, params: Object*): Unit

  def select(sql: String, params: String*): RichCursor

  def select(prepared: PreparedQuery, params: String*): RichCursor

  def makePreparedQuery(sql: String): PreparedQuery

  def search(sqlSelectQuery: String, rawQuery: String): RichCursor = {
    val purified = rawQuery.replaceAll("[^ a-zA-Z0-9]", "").trim
    select(sqlSelectQuery, s"$purified*")
  }
}
