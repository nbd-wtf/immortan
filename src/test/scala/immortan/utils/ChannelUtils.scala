package immortan.utils

import fr.acinq.eclair._
import immortan.sqlite._
import fr.acinq.eclair.transactions.{CommitmentSpec, RemoteFulfill}
import immortan.fsm.{OutgoingPaymentListener, OutgoingPaymentSenderData}
import fr.acinq.eclair.wire.{InitHostedChannel, LastCrossSignedState, NodeAddress}
import immortan.{ChannelMaster, HostedCommits, PathFinder, RemoteNodeInfo}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ChannelFeatures
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.SatoshiLong
import immortan.crypto.Tools


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
    val features = ChannelFeatures(Features.HostedChannels, Features.ResizeableHostedChannels)
    val initMessage = InitHostedChannel(UInt64(toLocal.underlying + 100000000L), 10.msat, 20, 200000000L.msat, 0.msat, features)

    val lcss: LastCrossSignedState = LastCrossSignedState(isHost = false, refundScriptPubKey = randomBytes(119), initMessage, blockDay = 100, localBalanceMsat = toLocal,
      remoteBalanceMsat = 100000000L.msat, localUpdates = 201, remoteUpdates = 101, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSigOfLocal = ByteVector64.Zeroes,
      localSigOfRemote = ByteVector64.Zeroes)

    HostedCommits(RemoteNodeInfo(nodeId, NodeAddress.unresolved(9735, host = 45, 20, 67, 1), alias),
      CommitmentSpec(feeratePerKw = FeeratePerKw(0.sat), toLocal = toLocal, toRemote = 100000000L.msat),
      lastCrossSignedState = lcss, nextLocalUpdates = Nil, nextRemoteUpdates = Nil, updateOpt = None,
      postErrorOutgoingResolvedIds = Set.empty, localError = None, remoteError = None,
      extParams = List.fill(secureRandom.nextInt(10))(randomBytes(secureRandom.nextInt(1000))),
      startedAt = System.currentTimeMillis)
  }

  def makeChannelMaster: (SQLiteNetwork, SQLiteNetwork, ChannelMaster) = {
    val (normalStore, hostedStore) = SQLiteUtils.getSQLiteNetworkStores
    val essentialInterface = SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, ChannelTable, PreimageTable)
    val notEssentialInterface = SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, PaymentTable, RelayTable, DataTable, ElectrumHeadersTable)
    val payBag = new SQLitePayment(notEssentialInterface, essentialInterface)
    val chanBag = new SQLiteChannel(essentialInterface, null)
    val dataBag = new SQLiteData(notEssentialInterface)

    val pf = makePathFinder(normalStore, hostedStore)
    val cm = new ChannelMaster(payBag, chanBag, dataBag, pf)
    pf.listeners += cm.opm

    (normalStore, hostedStore, cm)
  }

  def makeChannelMasterWithBasicGraph: (SQLiteNetwork, SQLiteNetwork, ChannelMaster) = {
    val (normalStore, hostedStore, cm) = makeChannelMaster
    GraphUtils.fillBasicGraph(normalStore)
    (normalStore, hostedStore, cm)
  }
}
