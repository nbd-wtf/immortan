package immortan.utils

import java.sql.{Connection, DriverManager}
import immortan.sqlite.{DBInterface, DBInterfaceSqliteGeneral, Table}


object SQLiteUtils {
  def getConnection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

  def interfaceWithTables(con: Connection, tables: Table*): DBInterface = {
    val interface = DBInterfaceSqliteGeneral(con)

    interface txWrap {
      val preparedStatement = interface.connection.createStatement
      tables.flatMap(_.createStatements).foreach(preparedStatement.executeUpdate)
    }

    interface
  }
}
