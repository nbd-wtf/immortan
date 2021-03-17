package immortan.sqlite

import java.sql.PreparedStatement


trait DBInterface {
  def txWrap[T](run: => T): T

  def change(sql: String, params: Object*): Unit

  def change(stmt: PreparedStatement, params: Object*): Unit

  def select(sql: String, params: String*): RichCursor

  def select(stmt: PreparedStatement, params: String*): RichCursor

  def search(sqlSelectQuery: String, rawQuery: String): RichCursor = {
    val purified = rawQuery.replaceAll("[^ a-zA-Z0-9]", "").trim
    select(sqlSelectQuery, s"$purified*")
  }
}
