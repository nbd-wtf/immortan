package immortan.sqlite

import scala.util.Try
import scodec.bits.ByteVector

trait DBInterface {
  def txWrap[T](run: => T): T
  def change(sql: String, params: Object*): Unit
  def change(prepared: PreparedQuery, params: Object*): Unit
  def select(sql: String, params: String*): RichCursor
  def select(prepared: PreparedQuery, params: String*): RichCursor
  def makePreparedQuery(sql: String): PreparedQuery
  def search(sqlSelectQuery: String, rawQuery: String): RichCursor =
    select(sqlSelectQuery, s"${rawQuery.replaceAll("'", "\\'").trim}*")
}

trait PreparedQuery {
  def bound(params: Object*): PreparedQuery
  def executeQuery: RichCursor
  def executeUpdate(): Unit
  def close(): Unit
}

trait RichCursor extends Iterable[RichCursor] {
  def byteVec(key: String): ByteVector = ByteVector view bytes(key)
  def iterable[T](transform: RichCursor => T): Iterable[T]
  def set[T](transform: RichCursor => T): Set[T]
  def headTry[T](fun: RichCursor => T): Try[T]
  def bytes(key: String): Array[Byte]
  def string(key: String): String
  def long(key: String): Long
  def long(pos: Int): Long
  def int(key: String): Int
  def int(pos: Int): Int
}
