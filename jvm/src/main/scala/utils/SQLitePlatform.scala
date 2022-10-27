package immortan.utils

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import java.lang.{Double => JDouble, Integer => JInt, Long => JLong}
import scala.util.Try
import immortan.sqlite.{DBInterface, PreparedQuery, RichCursor}

case class JavaDBInterface(connection: Connection) extends DBInterface {
  def change(sql: String, params: Object*): Unit =
    change(makePreparedQuery(sql), params: _*)

  def change(stmt: PreparedQuery, params: Object*): Unit =
    stmt.bound(params: _*).executeUpdate()

  def select(sql: String, params: String*): RichCursor =
    select(makePreparedQuery(sql), params: _*)

  def select(stmt: PreparedQuery, params: String*): RichCursor =
    stmt.bound(params: _*).executeQuery

  def makePreparedQuery(sql: String): PreparedQuery =
    JavaPreparedQuery(connection.prepareStatement(sql))

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

case class JavaPreparedQuery(stmt: PreparedStatement) extends PreparedQuery {
  def bound(params: Object*): PreparedQuery = {
    // Mutable, but local and saves one iteration
    var positionIndex = 1

    for (queryParameter <- params) {
      queryParameter match {
        case queryParameter: JDouble =>
          stmt.setDouble(positionIndex, queryParameter)
        case queryParameter: String =>
          stmt.setString(positionIndex, queryParameter)
        case queryParameter: Array[Byte] =>
          stmt.setBytes(positionIndex, queryParameter)
        case queryParameter: JLong =>
          stmt.setLong(positionIndex, queryParameter)
        case queryParameter: JInt => stmt.setInt(positionIndex, queryParameter)
        case _                    => throw new RuntimeException
      }

      positionIndex += 1
    }

    this
  }

  def executeQuery: RichCursor = JavaRichCursor(stmt.executeQuery)
  def executeUpdate(): Unit = stmt.executeUpdate
  def close(): Unit = stmt.close
}

case class JavaRichCursor(rs: ResultSet) extends RichCursor { self =>
  def iterable[T](transform: RichCursor => T): Iterable[T] = try map(transform)
  finally rs.close

  def set[T](transform: RichCursor => T): Set[T] = try map(transform).toSet
  finally rs.close

  def headTry[T](fun: RichCursor => T): Try[T] = try Try(fun apply head)
  finally rs.close

  def bytes(key: String): Array[Byte] = rs.getBytes(key)
  def string(key: String): String = rs.getString(key)
  def long(key: String): Long = rs.getLong(key)
  def long(pos: Int): Long = rs.getLong(pos + 1)
  def int(key: String): Int = rs.getInt(key)
  def int(pos: Int): Int = rs.getInt(pos + 1)

  def iterator: Iterator[RichCursor] =
    new Iterator[RichCursor] {
      def hasNext: Boolean = rs.next
      def next: RichCursor = self
    }
}
