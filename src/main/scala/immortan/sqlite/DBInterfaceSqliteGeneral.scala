package immortan.sqlite

import java.lang.{Double => JDouble, Integer => JInt, Long => JLong}
import immortan.crypto.Tools.Bytes
import java.sql.Connection


case class DBInterfaceSqliteGeneral(connection: Connection) extends DBInterface {

  def change(sql: String, params: Object*): Unit = {
    val prepStatement = connection.prepareStatement(sql)

    params.zipWithIndex.foreach {
      case (data: JDouble, idx) => prepStatement.setDouble(idx + 1, data)
      case (data: String, idx) => prepStatement.setString(idx + 1, data)
      case (data: Bytes, idx) => prepStatement.setBytes(idx + 1, data)
      case (data: JLong, idx) => prepStatement.setLong(idx + 1, data)
      case (data: JInt, idx) => prepStatement.setInt(idx + 1, data)
      case _ => throw new RuntimeException
    }

    prepStatement.executeUpdate
  }

  def select(sql: String, params: String*): RichCursor = {
    val prepStatement = connection.prepareStatement(sql)

    params.zipWithIndex.foreach {
      case (data, idx) => prepStatement.setString(idx + 1, data)
    }

    RichCursorSqliteGeneral(prepStatement.executeQuery)
  }

  def txWrap[T](exec: => T): T = {
    val old = connection.getAutoCommit
    connection.setAutoCommit(false)

    try {
      exec
    } catch {
      case error: Throwable =>
        connection.rollback
        throw error
    } finally {
      connection.commit
      connection.setAutoCommit(old)
    }
  }
}
