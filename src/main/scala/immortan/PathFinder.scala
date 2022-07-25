package immortan

import java.util.concurrent.{Executors, TimeUnit}

import com.google.common.cache.CacheBuilder
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.RouteCalculation.handleRouteRequest
import fr.acinq.eclair.router.Router.{Data, PublicChannel, RouteRequest}
import fr.acinq.eclair.router.{ChannelUpdateExt, Router}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi}
import immortan.PathFinder._
import immortan.crypto.Tools._
import immortan.crypto.{CanBeRepliedTo, StateMachine}
import immortan.fsm.SendMultiPart
import immortan.utils.Rx
import rx.lang.scala.Subscription

import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.Random.shuffle

object PathFinder {
  case object NormalSyncDone

  sealed trait State
  case object Waiting extends State
  case object Operational extends State

  sealed trait PathFinderRequest { val sender: CanBeRepliedTo }
  case class FindRoute(sender: CanBeRepliedTo, request: RouteRequest)
      extends PathFinderRequest
  case class GetExpectedPaymentFees(
      sender: CanBeRepliedTo,
      cmd: SendMultiPart,
      interHops: Int
  ) extends PathFinderRequest
  case class GetExpectedRouteFees(
      sender: CanBeRepliedTo,
      payee: PublicKey,
      interHops: Int
  ) extends PathFinderRequest

  case class ExpectedRouteFees(hops: List[HasRelayFee] = Nil) {
    def %(amount: MilliSatoshi): Double =
      ratio(amount, totalWithFeeReserve(amount) - amount)
    def totalCltvDelta: CltvExpiryDelta =
      hops.map(_.cltvExpiryDelta).reduce(_ + _)

    private def accumulate(
        hasRelayFee: HasRelayFee,
        accumulator: MilliSatoshi
    ): MilliSatoshi = hasRelayFee.relayFee(accumulator) + accumulator
    def totalWithFeeReserve(amount: MilliSatoshi): MilliSatoshi =
      hops.foldRight(amount)(accumulate)
  }
}

abstract class PathFinder(val normalBag: NetworkBag, val hostedBag: NetworkBag)
    extends StateMachine[Data, PathFinder.State] { me =>
  def initialState = PathFinder.Waiting

  private val extraEdgesCache = CacheBuilder.newBuilder
    .expireAfterWrite(1, TimeUnit.DAYS)
    .maximumSize(500)
    .build[java.lang.Long, GraphEdge]
  val extraEdges: mutable.Map[java.lang.Long, GraphEdge] =
    extraEdgesCache.asMap.asScala

  var listeners: Set[CanBeRepliedTo] = Set.empty
  var subscription: Option[Subscription] = None
  var syncMaster: Option[SyncMaster] = None

  implicit val context: ExecutionContextExecutor =
    ExecutionContext fromExecutor Executors.newSingleThreadExecutor

  def process(changeMessage: Any): Unit =
    scala.concurrent.Future(me doProcess changeMessage)

  private val RESYNC_PERIOD: Long = 1000L * 3600 * 24 * 4
  // We don't load routing data on every startup but when user (or system) actually needs it
  become(
    Data(channels = Map.empty, hostedChannels = Map.empty, DirectedGraph.empty),
    PathFinder.Waiting
  )

  def isIncompleteGraph: Boolean =
    me.data.channels.isEmpty || syncMaster.isDefined

  def getLastTotalResyncStamp: Long
  def getLastNormalResyncStamp: Long

  def updateLastTotalResyncStamp(stamp: Long): Unit
  def updateLastNormalResyncStamp(stamp: Long): Unit

  def getPHCExtraNodes: Set[RemoteNodeInfo]
  def getExtraNodes: Set[RemoteNodeInfo]

  def doProcess(change: Any): Unit = (change, state) match {
    case (calc: GetExpectedRouteFees, PathFinder.Operational) =>
      calc.sender process calcExpectedFees(calc.payee, calc.interHops)

    case (calc: GetExpectedPaymentFees, PathFinder.Operational) =>
      calc.sender process calc.cmd.copy(expectedRouteFees =
        Some(calcExpectedFees(calc.cmd.targetNodeId, calc.interHops))
      )

    case (fr: FindRoute, PathFinder.Operational) =>
      fr.sender process handleRouteRequest(
        data.graph replaceEdge fr.request.localEdge,
        fr.request
      )

    case (request: PathFinderRequest, PathFinder.Waiting) => {
      // We need a loaded routing data to process these requests
      // load that data before proceeding if it's absent
      context.execute(new Runnable { def run(): Unit = { loadGraph() } })
      me process request
    }

    case (phcPure: CompleteHostedRoutingData, PathFinder.Operational) =>
      // First, completely replace PHC data with obtained one
      hostedBag.processCompleteHostedData(phcPure)

      // Then reconstruct graph with new PHC data
      val hostedShortIdToPubChan = hostedBag.getRoutingData
      val searchGraph = DirectedGraph
        .makeGraph(data.channels ++ hostedShortIdToPubChan)
        .addEdges(extraEdges.values)
      become(
        Data(data.channels, hostedShortIdToPubChan, searchGraph),
        PathFinder.Operational
      )
      updateLastTotalResyncStamp(System.currentTimeMillis)
      listeners.foreach(_ process phcPure)

    case (pure: PureRoutingData, PathFinder.Operational) =>
      // Notify listener about graph sync progress here
      // Update db here to not overload SyncMaster
      listeners.foreach(_ process pure)
      normalBag.processPureData(pure)

    case (sync: SyncMaster, PathFinder.Operational) =>
      // Get rid of channels that peers know nothing about
      val normalShortIdToPubChan = normalBag.getRoutingData
      val oneSideShortIds = normalBag.listChannelsWithOneUpdate
      val ghostIds = normalShortIdToPubChan.keySet.diff(sync.provenShortIds)
      val normalShortIdToPubChan1 =
        normalShortIdToPubChan -- ghostIds -- oneSideShortIds
      val searchGraph = DirectedGraph
        .makeGraph(normalShortIdToPubChan1 ++ data.hostedChannels)
        .addEdges(extraEdges.values)
      become(
        Data(normalShortIdToPubChan1, data.hostedChannels, searchGraph),
        PathFinder.Operational
      )
      // Update normal checkpoint, if PHC sync fails this time we'll jump to it next time
      updateLastNormalResyncStamp(System.currentTimeMillis)

      // Perform database cleaning in a different thread since it's slow and we are operational
      Rx.ioQueue.foreach(
        _ => normalBag.removeGhostChannels(ghostIds, oneSideShortIds),
        none
      )
      // Remove by now useless reference, this may be used to define if sync is on
      syncMaster = None

      // Notify that normal graph sync is complete
      listeners.foreach(_.process(PathFinder.NormalSyncDone))
      attemptPHCSync()

    // We always accept and store disabled channels:
    // - to reduce subsequent sync traffic if channel remains disabled
    // - to account for the case when channel suddenly becomes enabled but we don't know
    // - if channel stays disabled for a long time it will be pruned by peers and then by us

    case (cu: ChannelUpdate, PathFinder.Operational)
        if data.channels.contains(cu.shortChannelId) =>
      become(
        resolve(data.channels(cu.shortChannelId), cu, normalBag),
        PathFinder.Operational
      )

    case (cu: ChannelUpdate, PathFinder.Operational)
        if data.hostedChannels.contains(cu.shortChannelId) =>
      become(
        resolve(data.hostedChannels(cu.shortChannelId), cu, hostedBag),
        PathFinder.Operational
      )

    case (cu: ChannelUpdate, PathFinder.Operational) =>
      extraEdges.get(cu.shortChannelId).foreach { extEdge =>
        // Last chance: not a known public update, maybe it's a private one
        become(
          resolveKnownDesc(
            storeOpt = None,
            extEdge.copy(updExt = extEdge.updExt withNewUpdate cu)
          ),
          PathFinder.Operational
        )
      }

    case (edge: GraphEdge, PathFinder.Waiting | PathFinder.Operational)
        if !data.channels.contains(edge.desc.shortChannelId) =>
      // We add assisted routes to graph as if they are normal channels, also rememeber them to refill later if graph gets reloaded
      // these edges will be private most of the time, but they also may be public but yet not visible to us for some reason
      extraEdgesCache.put(edge.updExt.update.shortChannelId, edge)
      become(
        data.copy(graph = data.graph replaceEdge edge),
        state
      )

    case _ =>
  }

  def startPeriodicResync(): Unit = {
    if (subscription.isEmpty) {
      val repeat =
        Rx.repeat(Rx.ioQueue, Rx.incHour, times = 97 to Int.MaxValue by 97)
      // Resync every RESYNC_PERIOD hours + 1 hour to trigger a full resync, not just PHC resync
      val delay = Rx.initDelay(
        repeat,
        getLastTotalResyncStamp,
        RESYNC_PERIOD,
        preStartMsec = 500
      )
      subscription = Some(delay.subscribe(_ => resync()))
    }
  }

  def resync(): Unit = state match {
    case PathFinder.Waiting => {
      // We need a loaded routing data to sync properly
      // load that data before proceeding if it's absent
      context.execute(new Runnable { def run(): Unit = { loadGraph() } })
      context.execute(new Runnable { def run(): Unit = { resync() } })
    }
    case PathFinder.Operational
        if System.currentTimeMillis - getLastNormalResyncStamp > RESYNC_PERIOD => {
      val setupData = SyncMasterShortIdData(
        LNParams.syncParams.syncNodes,
        getExtraNodes,
        Set.empty,
        Map.empty
      )

      val requestNodeAnnounceForChan = for {
        info <- getExtraNodes ++ getPHCExtraNodes
        edges <- data.graph.vertices.get(info.nodeId)
      } yield shuffle(edges).head.desc.shortChannelId

      val normalSync = new SyncMaster(
        normalBag.listExcludedChannels,
        requestNodeAnnounceForChan,
        data,
        LNParams.syncParams.maxNodesToSyncFrom
      ) { self =>
        override def onNodeAnnouncement(
            nodeAnnouncement: NodeAnnouncement
        ): Unit = listeners.foreach(_ process nodeAnnouncement)
        override def onChunkSyncComplete(
            pureRoutingData: PureRoutingData
        ): Unit = me process pureRoutingData
        override def onTotalSyncComplete(): Unit = me process self
      }

      syncMaster = Some(normalSync)
      listeners.foreach(_ =>
        context.execute(new Runnable {
          def run(): Unit = { resync() }
        })
      )
      normalSync.process(setupData)
    }
    case PathFinder.Operational
        if System.currentTimeMillis - getLastTotalResyncStamp > RESYNC_PERIOD =>
      // Normal resync has happened recently, but PHC resync is outdated
      //   (PHC failed last time due to running out of attempts)
      // in this case we skip normal sync and start directly with PHC sync to save time
      //   and increase PHC sync success chances
      attemptPHCSync()
    case PathFinder.Operational =>
  }

  def loadGraph(): Unit = {
    if (state == PathFinder.Waiting) {
      val normalShortIdToPubChan = normalBag.getRoutingData
      val hostedShortIdToPubChan = hostedBag.getRoutingData
      become(
        Data(
          normalShortIdToPubChan,
          hostedShortIdToPubChan,
          DirectedGraph
            .makeGraph(normalShortIdToPubChan ++ hostedShortIdToPubChan)
            .addEdges(extraEdges.values)
        ),
        PathFinder.Operational
      )
    }
  }

  def resolve(
      pubChan: PublicChannel,
      upd: ChannelUpdate,
      store: NetworkBag
  ): Data = {
    // Resoving normal/hosted public channel updates we get while trying to route payments
    val desc = Router.getDesc(upd, pubChan.ann)

    pubChan.getChannelUpdateSameSideAs(upd) match {
      case Some(oldExt) if oldExt.update.timestamp < upd.timestamp =>
        // We have an old updateExt and obtained one is newer, this is fine
        val edge = GraphEdge(desc, oldExt withNewUpdate upd)
        resolveKnownDesc(storeOpt = Some(store), edge)

      case None =>
        // Somehow we don't have an old updateExt, create a new one
        val edge = GraphEdge(desc, ChannelUpdateExt fromUpdate upd)
        resolveKnownDesc(storeOpt = Some(store), edge)

      case _ =>
        // Our updateExt is newer
        data
    }
  }

  def resolveKnownDesc(storeOpt: Option[NetworkBag], edge: GraphEdge): Data =
    storeOpt match {
      // Resolves channel updates which we extract from remote node errors while trying to route payments
      // store is optional to make sure private normal/hosted channel updates never make it to our database

      case Some(store) if edge.updExt.update.htlcMaximumMsat.isEmpty =>
        // Will be queried on next sync and will most likely be excluded
        store.removeChannelUpdate(edge.updExt.update.shortChannelId)
        data.copy(graph = data.graph removeEdge edge.desc)

      case Some(store) =>
        // This is a legitimate public update, refresh everywhere
        store.addChannelUpdateByPosition(edge.updExt.update)
        data.copy(graph = data.graph replaceEdge edge)

      case None =>
        // This is a legitimate private/unknown-public update
        extraEdgesCache.put(edge.updExt.update.shortChannelId, edge)
        // Don't save this in DB but update runtime graph
        data.copy(graph = data.graph replaceEdge edge)
    }

  def nodeIdFromUpdate(cu: ChannelUpdate): Option[Crypto.PublicKey] =
    data.channels
      .get(cu.shortChannelId)
      .map(_.ann getNodeIdSameSideAs cu) orElse
      data.hostedChannels
        .get(cu.shortChannelId)
        .map(_.ann getNodeIdSameSideAs cu) orElse
      extraEdges.get(cu.shortChannelId).map(_.desc.from)

  def attemptPHCSync(): Unit = {
    if (LNParams.syncParams.phcSyncNodes.nonEmpty) {
      val master = new PHCSyncMaster(data) {
        override def onSyncComplete(pure: CompleteHostedRoutingData): Unit =
          me process pure
      }
      master process SyncMasterPHCData(
        LNParams.syncParams.phcSyncNodes,
        getPHCExtraNodes,
        activeSyncs = Set.empty
      )
    } else updateLastTotalResyncStamp(System.currentTimeMillis)
  }

  def calcExpectedFees(nodeId: PublicKey, hopsNum: Int): ExpectedRouteFees = {
    val payeeHops =
      data.graph.vertices.getOrElse(nodeId, default = Nil).map(_.updExt)
    val lastFees =
      if (payeeHops.isEmpty) data.avgHopParams
      else Router.getAvgHopParams(payeeHops)
    ExpectedRouteFees(List.fill(hopsNum)(data.avgHopParams) :+ lastFees)
  }
}
