package immortan.utils

import scoin.Crypto.PublicKey
import scoin.{FeeratePerKw, ByteVector32, ByteVector64, SatoshiLong}
import scoin.ln._
import scoin.ln.transactions.{CommitmentSpec, RemoteFulfill}

import immortan.{none, ChannelMaster, HostedCommits, PathFinder, RemoteNodeInfo}
import immortan.fsm.{OutgoingPaymentListener, OutgoingPaymentSenderData}
import immortan.sqlite._

object ChannelUtils {
  val noopListener: OutgoingPaymentListener = new OutgoingPaymentListener {
    override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit =
      none
    override def gotFirstPreimage(
        data: OutgoingPaymentSenderData,
        fulfill: RemoteFulfill
    ): Unit = none
  }

  def makePathFinder(
      normalStore: SQLiteNetwork,
      hostedStore: SQLiteNetwork
  ): PathFinder =
    new PathFinder(normalStore, hostedStore) {
      def getLastTotalResyncStamp: Long =
        System.currentTimeMillis // Won't resync
      def getLastNormalResyncStamp: Long =
        System.currentTimeMillis // Won't resync
      def updateLastTotalResyncStamp(stamp: Long): Unit = none
      def updateLastNormalResyncStamp(stamp: Long): Unit = none
      def getPHCExtraNodes: Set[RemoteNodeInfo] = Set.empty
      def getExtraNodes: Set[RemoteNodeInfo] = Set.empty
    }

  def makeHostedCommits(
      nodeId: PublicKey,
      alias: String,
      toLocal: MilliSatoshi = MilliSatoshi(100000000L)
  ): HostedCommits = {
    val features = List(
      Features.HostedChannels.mandatory,
      Features.ResizeableHostedChannels.mandatory
    )
    val initMessage = InitHostedChannel(
      UInt64(toLocal.underlying + 100000000L),
      MilliSatoshi(10),
      20,
      MilliSatoshi(200000000L),
      MilliSatoshi(0),
      features
    )
    val remoteBalance = initMessage.channelCapacityMsat - toLocal

    val lcss: LastCrossSignedState = LastCrossSignedState(
      isHost = false,
      refundScriptPubKey = randomBytes(119),
      initMessage,
      blockDay = 100,
      localBalanceMsat = toLocal,
      remoteBalanceMsat = remoteBalance,
      localUpdates = 201,
      remoteUpdates = 101,
      incomingHtlcs = Nil,
      outgoingHtlcs = Nil,
      remoteSigOfLocal = ByteVector64.Zeroes,
      localSigOfRemote = ByteVector64.Zeroes
    )

    HostedCommits(
      RemoteNodeInfo(
        nodeId,
        NodeAddress.unresolved(9735, host = 45, 20, 67, 1),
        alias
      ),
      CommitmentSpec(
        feeratePerKw = FeeratePerKw(Satoshi(0)),
        toLocal = toLocal,
        toRemote = remoteBalance
      ),
      lastCrossSignedState = lcss,
      nextLocalUpdates = Nil,
      nextRemoteUpdates = Nil,
      updateOpt = None,
      postErrorOutgoingResolvedIds = Set.empty,
      localError = None,
      remoteError = None,
      extParams = Nil,
      startedAt = System.currentTimeMillis
    )
  }

  def makeChannelMaster(
      secrets: Iterable[ByteVector32] = Nil
  ): (SQLiteNetwork, SQLiteNetwork, ChannelMaster) = {
    val (normalStore, hostedStore) = SQLiteUtils.getSQLiteNetworkStores
    val essentialInterface = SQLiteUtils.interfaceWithTables(
      SQLiteUtils.getConnection,
      ChannelTable,
      PreimageTable
    )
    val notEssentialInterface = SQLiteUtils.interfaceWithTables(
      SQLiteUtils.getConnection,
      PaymentTable,
      RelayTable,
      DataTable,
      ElectrumHeadersTable
    )
    val payBag: SQLitePayment =
      new SQLitePayment(notEssentialInterface, essentialInterface) {
        override def listPendingSecrets: Set[ByteVector32] = secrets.toSet
      }
    val chanBag = new SQLiteChannel(essentialInterface, null)
    val dataBag = new SQLiteData(notEssentialInterface)

    val pf = makePathFinder(normalStore, hostedStore)
    val cm = new ChannelMaster(payBag, chanBag, dataBag, pf)
    pf.listeners += cm.opm

    (normalStore, hostedStore, cm)
  }

  def makeChannelMasterWithBasicGraph(
      secrets: Iterable[ByteVector32] = Nil
  ): (SQLiteNetwork, SQLiteNetwork, Iterable[ByteVector32], ChannelMaster) = {
    val (normalStore, hostedStore, cm) = makeChannelMaster(secrets)
    GraphUtils.fillBasicGraph(normalStore)
    (normalStore, hostedStore, secrets, cm)
  }
}
