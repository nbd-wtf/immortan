package immortan.utils

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.{CommitmentSpec, RemoteFulfill}
import fr.acinq.eclair.wire.{InitHostedChannel, LastCrossSignedState, NodeAddress}
import immortan.crypto.Tools
import immortan.fsm.{OutgoingPaymentListener, OutgoingPaymentSenderData}
import immortan.sqlite._
import immortan.{ChannelMaster, HostedCommits, PathFinder, RemoteNodeInfo}


object ChannelUtils {
  val noopListener: OutgoingPaymentListener = new OutgoingPaymentListener {
    override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = Tools.none
    override def gotFirstPreimage(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill): Unit = Tools.none
  }

  def makePathFinder(normalStore: SQLiteNetwork, hostedStore: SQLiteNetwork): PathFinder =
    new PathFinder(normalStore, hostedStore) {
      def getLastTotalResyncStamp: Long = System.currentTimeMillis // Won't resync
      def getLastNormalResyncStamp: Long = System.currentTimeMillis // Won't resync
      def updateLastTotalResyncStamp(stamp: Long): Unit = Tools.none
      def updateLastNormalResyncStamp(stamp: Long): Unit = Tools.none
      def getPHCExtraNodes: Set[RemoteNodeInfo] = Set.empty
      def getExtraNodes: Set[RemoteNodeInfo] = Set.empty
    }

  def makeHostedCommits(nodeId: PublicKey, alias: String, toLocal: MilliSatoshi = 100000000L.msat): HostedCommits = {
    val features = List(Features.HostedChannels.mandatory, Features.ResizeableHostedChannels.mandatory)
    val initMessage = InitHostedChannel(UInt64(toLocal.underlying + 100000000L), 10.msat, 20, 200000000L.msat, 0.msat, features)
    val remoteBalance = initMessage.channelCapacityMsat - toLocal

    val lcss: LastCrossSignedState = LastCrossSignedState(isHost = false, refundScriptPubKey = randomBytes(119), initMessage, blockDay = 100, localBalanceMsat = toLocal,
      remoteBalanceMsat = remoteBalance, localUpdates = 201, remoteUpdates = 101, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSigOfLocal = ByteVector64.Zeroes,
      localSigOfRemote = ByteVector64.Zeroes)

    HostedCommits(RemoteNodeInfo(nodeId, NodeAddress.unresolved(9735, host = 45, 20, 67, 1), alias),
      CommitmentSpec(feeratePerKw = FeeratePerKw(0.sat), toLocal = toLocal, toRemote = remoteBalance),
      lastCrossSignedState = lcss, nextLocalUpdates = Nil, nextRemoteUpdates = Nil, updateOpt = None,
      postErrorOutgoingResolvedIds = Set.empty, localError = None, remoteError = None,
      extParams = Nil, startedAt = System.currentTimeMillis)
  }

  def makeChannelMaster(secrets: Iterable[ByteVector32] = Nil): (SQLiteNetwork, SQLiteNetwork, ChannelMaster) = {
    val (normalStore, hostedStore) = SQLiteUtils.getSQLiteNetworkStores
    val essentialInterface = SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, ChannelTable, PreimageTable)
    val notEssentialInterface = SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, PaymentTable, RelayTable, DataTable, ElectrumHeadersTable)
    val payBag: SQLitePayment = new SQLitePayment(notEssentialInterface, essentialInterface) {
      override def listPendingSecrets: Set[ByteVector32] = secrets.toSet
    }
    val chanBag = new SQLiteChannel(essentialInterface, null)
    val dataBag = new SQLiteData(notEssentialInterface)

    val pf = makePathFinder(normalStore, hostedStore)
    val cm = new ChannelMaster(payBag, chanBag, dataBag, pf)
    pf.listeners += cm.opm

    (normalStore, hostedStore, cm)
  }

  def makeChannelMasterWithBasicGraph(secrets: Iterable[ByteVector32] = Nil): (SQLiteNetwork, SQLiteNetwork, Iterable[ByteVector32], ChannelMaster) = {
    val (normalStore, hostedStore, cm) = makeChannelMaster(secrets)
    GraphUtils.fillBasicGraph(normalStore)
    (normalStore, hostedStore, secrets, cm)
  }
}
