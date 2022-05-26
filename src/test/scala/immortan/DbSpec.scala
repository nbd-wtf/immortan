package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.transactions.IncomingHtlc
import fr.acinq.eclair.wire.{OnionRoutingPacket, UpdateAddHtlc}
import immortan.sqlite._
import immortan.utils.SQLiteUtils
import utest._

object DbSpec extends TestSuite {
  val tests = Tests {
    test("Two transactions in a row") {
      val sameConnection = SQLiteUtils.getConnection
      SQLiteUtils.interfaceWithTables(sameConnection, DataTable)
      SQLiteUtils.interfaceWithTables(sameConnection, ChannelTable, TxTable)
    }

    test("Insert, then select twice") {
      val interface =
        SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, DataTable)
      val sqLiteData = new SQLiteData(interface)
      val data = randomBytes(32)

      sqLiteData.put("test", data.toArray)
      assert(sqLiteData.tryGet("test").get == data)
      assert(sqLiteData.tryGet("test").get == data)
    }

    test("Handle collections") {
      val onion = OnionRoutingPacket(1, randomKey.publicKey.value, null, null)
      val inserts =
        for (n <- 0L until 100L)
          yield IncomingHtlc(
            UpdateAddHtlc(
              null,
              n,
              100L.msat,
              randomBytes32,
              CltvExpiry(n),
              onion
            )
          )
      val interface =
        SQLiteUtils.interfaceWithTables(
          SQLiteUtils.getConnection,
          HtlcInfoTable
        )
      val sqLiteChannel = new SQLiteChannel(interface, null)

      sqLiteChannel.putHtlcInfos(inserts, sid = 100L, commitNumber = 100)
      assert(
        sqLiteChannel.htlcInfos(commitNumer = 100).map(_.cltvExpiry) == inserts
          .map(_.add.cltvExpiry)
      )

      sqLiteChannel.rmHtlcInfos(sid = 100L)
      assert(sqLiteChannel.htlcInfos(commitNumer = 100).isEmpty)
    }
  }
}
