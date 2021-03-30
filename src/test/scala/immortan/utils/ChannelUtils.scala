package immortan.utils

import fr.acinq.eclair._
import immortan.sqlite._
import fr.acinq.bitcoin.{ByteVector64, Satoshi}
import fr.acinq.eclair.transactions.{CommitmentSpec, RemoteFulfill}
import immortan.fsm.{OutgoingPaymentEvents, OutgoingPaymentSenderData}
import fr.acinq.eclair.wire.{InitHostedChannel, LastCrossSignedState, NodeAddress}
import immortan.{ChannelMaster, ConnectionListener, HostedCommits, PathFinder, RemoteNodeInfo}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ChannelVersion
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.SatoshiLong
import immortan.crypto.Tools


object ChannelUtils {
  val noopListener: OutgoingPaymentEvents = new OutgoingPaymentEvents {
    override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = Tools.none
    override def preimageRevealed(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill): Unit = Tools.none
  }

  def makePathFinder(normalStore: SQLiteNetwork, hostedStore: SQLiteNetwork): PathFinder =
    new PathFinder(normalStore, hostedStore) {
      def getLastTotalResyncStamp: Long = System.currentTimeMillis // Won't resync
      def getLastNormalResyncStamp: Long = System.currentTimeMillis // Won't resync
      def updateLastTotalResyncStamp(stamp: Long): Unit = Tools.none
      def updateLastNormalResyncStamp(stamp: Long): Unit = Tools.none
      def getExtraNodes: Set[RemoteNodeInfo] = Set.empty
    }

  def makeHostedCommits(nodeId: PublicKey, alias: String, toLocal: MilliSatoshi = 100000000L.msat): HostedCommits = {
    val initMessage = InitHostedChannel(UInt64(toLocal.underlying + 100000000L), 10.msat, 20, 200000000L.msat, 5000, Satoshi(1000000), 0.msat, ChannelVersion.STANDARD)

    val lcss: LastCrossSignedState = LastCrossSignedState(isHost = false, refundScriptPubKey = randomBytes(119), initMessage, blockDay = 100, localBalanceMsat = toLocal,
      remoteBalanceMsat = 100000000L.msat, localUpdates = 201, remoteUpdates = 101, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSigOfLocal = ByteVector64.Zeroes,
      localSigOfRemote = ByteVector64.Zeroes)

    HostedCommits(RemoteNodeInfo(nodeId, NodeAddress.unresolved(9735, host = 45, 20, 67, 1), alias), lastCrossSignedState = lcss, nextLocalUpdates = Nil, nextRemoteUpdates = Nil,
      localSpec = CommitmentSpec(feeratePerKw = FeeratePerKw(0.sat), toLocal = toLocal, toRemote = 100000000L.msat), updateOpt = None, localError = None, remoteError = None,
      startedAt = System.currentTimeMillis)
  }

  def makeChannelMaster: (SQLiteNetwork, SQLiteNetwork, ChannelMaster) = {
    val (normalStore, hostedStore) = SQLiteUtils.getSQLiteNetworkStores
    val essentialInterface = SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, ChannelTable, PreimageTable)
    val notEssentialInterface = SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, PaymentTable, RelayTable, DataTable, ElectrumHeadersTable)
    val payBag = new SQLitePayment(notEssentialInterface, essentialInterface)
    val chanBag = new SQLiteChannel(essentialInterface)
    val dataBag = new SQLiteData(notEssentialInterface)

    val pf = makePathFinder(normalStore, hostedStore)
    val cm = new ChannelMaster(payBag, chanBag, dataBag, pf)

    (normalStore, hostedStore, cm)
  }

  def makeChannelMasterWithBasicGraph: (SQLiteNetwork, SQLiteNetwork, ChannelMaster) = {
    val (normalStore, hostedStore, cm) = makeChannelMaster
    GraphUtils.fillBasicGraph(normalStore)
    (normalStore, hostedStore, cm)
  }
}
