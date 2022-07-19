package immortan.sqlite

import java.lang.{Double => JDouble, Integer => JInt, Long => JLong}
import java.sql.PreparedStatement

trait PreparedQuery {
  def bound(params: Array[Object]): PreparedQuery
  def bound(params: Array[String]): PreparedQuery =
    bound(params.asInstanceOf[Array[Object]])
  def executeQuery(): RichCursor
  def executeUpdate(): Unit
  def close(): Unit
}

case class PreparedQuerySQLiteGeneral(stmt: PreparedStatement)
    extends PreparedQuery {
  def bound(params: Array[Object]): PreparedQuery = {
    var i = 1
    params.foreach { param =>
      param match {
        case v: JDouble     => stmt.setDouble(i, v)
        case v: String      => stmt.setString(i, v)
        case v: Array[Byte] => stmt.setBytes(i, v)
        case v: JLong       => stmt.setLong(i, v)
        case v: JInt        => stmt.setInt(i, v)
        case _              => throw new RuntimeException
      }
      i += 1
    }
    this
  }

  override def bound(params: Array[String]): PreparedQuery = {
    var i = 1
    params.foreach { param =>
      stmt.setString(i, param)
      i += 1
    }
    this
  }

  def executeQuery(): RichCursor = RichCursorSQLiteGeneral(stmt.executeQuery)
  def executeUpdate(): Unit = stmt.executeUpdate()
  def close(): Unit = stmt.close()
}
