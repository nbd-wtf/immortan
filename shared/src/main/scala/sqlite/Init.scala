package immortan.sqlite

object DBInit {
  // this is a helper method that exists just to demonstrate how to create the databases
  // extracted from the SBW code that creates tables in response to android events onCreate/onUpgrade etc
  // in practice it must be replaced with something that takes these things (and also migrations etc) into account
  // will eventually be improved to handle all these things nicely

  def createTables(base: DBInterface): Unit = {
    // essential
    ChannelTable.createStatements.foreach(base.change(_))
    HtlcInfoTable.createStatements.foreach(base.change(_))
    PreimageTable.createStatements.foreach(base.change(_))

    // graph
    NormalChannelAnnouncementTable.createStatements.foreach(base.change(_))
    HostedChannelAnnouncementTable.createStatements.foreach(base.change(_))

    NormalExcludedChannelTable.createStatements.foreach(base.change(_))
    HostedExcludedChannelTable.createStatements.foreach(base.change(_))

    NormalChannelUpdateTable.createStatements.foreach(base.change(_))
    HostedChannelUpdateTable.createStatements.foreach(base.change(_))

    // misc
    TxTable.createStatements.foreach(base.change(_))
    ChannelTxFeesTable.createStatements.foreach(base.change(_))
    ElectrumHeadersTable.createStatements.foreach(base.change(_))
    ChainWalletTable.createStatements.foreach(base.change(_))
    LNUrlPayTable.createStatements.foreach(base.change(_))
    PaymentTable.createStatements.foreach(base.change(_))
    RelayTable.createStatements.foreach(base.change(_))
    DataTable.createStatements.foreach(base.change(_))
    LogTable.createStatements.foreach(base.change(_))
  }
}
