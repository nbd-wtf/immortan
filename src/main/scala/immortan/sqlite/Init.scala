package immortan.sqlite

import java.sql.Connection

object DBInit {
  // this is a helper method that exists just to demonstrate how to create the databases
  // extracted from the SBW code that creates tables in response to android events onCreate/onUpgrade etc
  // in practice it must be replaced with something that takes these things (and also migrations etc) into account
  // will eventually be improved to handle all these things nicely

  def createTables(base: Connection): Unit = {
    // essential
    ChannelTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    HtlcInfoTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    PreimageTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )

    // graph
    NormalChannelAnnouncementTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    HostedChannelAnnouncementTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )

    NormalExcludedChannelTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    HostedExcludedChannelTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )

    NormalChannelUpdateTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    HostedChannelUpdateTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )

    // misc
    TxTable.createStatements.foreach(s => base.prepareStatement(s).execute())
    ChannelTxFeesTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    ElectrumHeadersTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    ChainWalletTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    LNUrlPayTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    PaymentTable.createStatements.foreach(s =>
      base.prepareStatement(s).execute()
    )
    RelayTable.createStatements.foreach(s => base.prepareStatement(s).execute())
    DataTable.createStatements.foreach(s => base.prepareStatement(s).execute())
    LogTable.createStatements.foreach(s => base.prepareStatement(s).execute())
  }
}
