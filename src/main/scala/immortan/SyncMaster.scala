package immortan

import immortan.SyncMaster._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import scala.concurrent.duration._
import QueryShortChannelIdsTlv.QueryFlagType._
import fr.acinq.eclair.router.{Announcements, Sync}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import immortan.crypto.{CanBeRepliedTo, StateMachine, Tools}
import fr.acinq.eclair.router.Router.Data
import fr.acinq.bitcoin.Crypto.PublicKey
import java.util.concurrent.Executors
import immortan.crypto.Noise.KeyPair
import scala.util.Random.shuffle
import scala.collection.mutable
import scodec.bits.ByteVector
import immortan.utils.Rx


object SyncMaster {
  val WAITING = "state-waiting"
  val SHUT_DOWN = "state-shut-down"
  val SHORT_ID_SYNC = "state-short-id-sync"
  val GOSSIP_SYNC = "state-gossip-sync"
  val PHC_SYNC = "phc-sync"

  val CMDAddSync = "cmd-add-sync"
  val CMDGetGossip = "cmd-get-gossip"
  val CMDShutdown = "cmd-shut-down"

  type ConfirmedBySet = Set[PublicKey]
  type ShortChanIdSet = Set[ShortChannelId]
  type PositionSet = Set[java.lang.Integer]

  val blw: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03144fcc73cea41a002b2865f98190ab90e4ff58a2ce24d3870f5079081e42922d"), NodeAddress.unresolved(9735, host = 5, 9, 83, 143), "BLW Den")
  val lightning: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03baa70886d9200af0ffbd3f9e18d96008331c858456b16e3a9b41e735c6208fef"), NodeAddress.unresolved(9735, host = 45, 20, 67, 1), "LIGHTNING")
  val conductor: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03c436af41160a355fc1ed230a64f6a64bcbd2ae50f12171d1318f9782602be601"), NodeAddress.unresolved(9735, host = 18, 191, 89, 219), "Conductor")
  val cheese: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "0276e09a267592e7451a939c932cf685f0754de382a3ca85d2fb3a864d4c365ad5"), NodeAddress.unresolved(9735, host = 94, 177, 171, 73), "Cheese")
  val acinq: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), NodeAddress.unresolved(9735, host = 34, 239, 230, 56), "ACINQ")

  val hostedChanNodes: Set[NodeAnnouncement] = Set(blw, lightning, acinq) // Trusted nodes which are shown as default ones when user chooses providers
  val hostedSyncNodes: Set[NodeAnnouncement] = Set(blw, lightning, acinq) // Semi-trusted PHC-enabled nodes which can be used as seeds for PHC sync
  val syncNodes: Set[NodeAnnouncement] = Set(lightning, acinq, conductor) // Nodes with extended queries support used as seeds for normal sync

  val maxPHCCapacity: MilliSatoshi = MilliSatoshi(1000000000000000L) // 10 000 BTC
  val minPHCCapacity: MilliSatoshi = MilliSatoshi(50000000000L) // 0.5 BTC
  val minNormalChansForPHC = 5
  val maxPHCPerNode = 2

  val minCapacity: MilliSatoshi = MilliSatoshi(500000000L) // 500k sat
  val maxNodesToSyncFrom = 3 // How many disjoint peers to use for majority sync
  val acceptThreshold = 1 // ShortIds and updates are accepted if confirmed by more than this peers
  val messagesToAsk = 1000 // Ask for this many messages from peer before they say this chunk is done
  val chunksToWait = 4 // Wait for at least this much chunk iterations from any peer before recording results
}

sealed trait SyncWorkerData

case class SyncWorkerShortIdsData(ranges: List[ReplyChannelRange] = Nil, from: Int) extends SyncWorkerData {
  // This class contains a list of shortId ranges collected from a single remote peer, we need to make sure all of them are sound, that is, TLV data is of same size as main data
  def isHolistic: Boolean = ranges.forall(rng => rng.shortChannelIds.array.size == rng.timestamps.timestamps.size && rng.timestamps.timestamps.size == rng.checksums.checksums.size)
  lazy val allShortIds: Seq[ShortChannelId] = ranges.flatMap(_.shortChannelIds.array)
}

case class SyncWorkerGossipData(syncMaster: SyncMaster,
                                queries: Seq[QueryShortChannelIds],
                                updates: Set[ChannelUpdate] = Set.empty,
                                announces: Set[ChannelAnnouncement] = Set.empty,
                                excluded: Set[UpdateCore] = Set.empty) extends SyncWorkerData

case class CMDShortIdsComplete(sync: SyncWorker, data: SyncWorkerShortIdsData)
case class CMDChunkComplete(sync: SyncWorker, data: SyncWorkerGossipData)
case class CMDGossipComplete(sync: SyncWorker)

// This entirely relies on fact that peer sends ChannelAnnouncement messages first, then ChannelUpdate messages

case class SyncWorkerPHCData(phcMaster: PHCSyncMaster,
                             expectedPositions: Map[ShortChannelId, PositionSet] = Map.empty, nodeIdToShortIds: Map[PublicKey, ShortChanIdSet] = Map.empty,
                             updates: Set[ChannelUpdate] = Set.empty, announces: Map[ShortChannelId, ChannelAnnouncement] = Map.empty) extends SyncWorkerData {

  def withNewAnnounce(ann: ChannelAnnouncement): SyncWorkerPHCData = {
    val nodeId1ToShortIds = nodeIdToShortIds.getOrElse(ann.nodeId1, Set.empty) + ann.shortChannelId
    val nodeId2ToShortIds = nodeIdToShortIds.getOrElse(ann.nodeId2, Set.empty) + ann.shortChannelId
    val nodeIdToShortIds1 = nodeIdToShortIds.updated(ann.nodeId1, nodeId1ToShortIds).updated(ann.nodeId2, nodeId2ToShortIds)
    copy(expectedPositions = expectedPositions.updated(ann.shortChannelId, ChannelUpdate.fullSet), announces = announces.updated(ann.shortChannelId, ann), nodeIdToShortIds = nodeIdToShortIds1)
  }

  def withNewUpdate(cu: ChannelUpdate): SyncWorkerPHCData = {
    val oneLessPosition = expectedPositions.getOrElse(cu.shortChannelId, Set.empty) - cu.position
    copy(expectedPositions = expectedPositions.updated(cu.shortChannelId, oneLessPosition), updates = updates + cu)
  }

  def isAcceptable(ann: ChannelAnnouncement): Boolean = {
    val notTooMuchNode1PHCs = nodeIdToShortIds.getOrElse(ann.nodeId1, Set.empty).size < maxPHCPerNode
    val notTooMuchNode2PHCs = nodeIdToShortIds.getOrElse(ann.nodeId2, Set.empty).size < maxPHCPerNode
    val isCorrect = Tools.hostedShortChanId(ann.nodeId1.value, ann.nodeId2.value) == ann.shortChannelId
    ann.isPHC && isCorrect && notTooMuchNode1PHCs && notTooMuchNode2PHCs
  }

  def isUpdateAcceptable(cu: ChannelUpdate): Boolean =
    cu.htlcMaximumMsat.exists(cap => cap >= minPHCCapacity && cap <= maxPHCCapacity) && // Capacity within bounds
      expectedPositions.getOrElse(cu.shortChannelId, Set.empty).contains(cu.position) && // Remote node must not send the same update twice
      announces.get(cu.shortChannelId).map(_ getNodeIdSameSideAs cu).exists(Announcements checkSig cu) // We have received a related announce, signature is valid
}

case class SyncWorker(master: CanBeRepliedTo, keyPair: KeyPair, ann: NodeAnnouncement, ourInit: Init) extends StateMachine[SyncWorkerData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  val pkap: KeyPairAndPubKey = KeyPairAndPubKey(keyPair, ann.nodeId)

  val listener: ConnectionListener = new ConnectionListener {
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = me process worker
    override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = me process msg

    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      // Remove this listener and remove an object itself from master
      // This disconnect is unexpected, normal shoutdown removes listener
      CommsTower.listeners(worker.pkap) -= listener
      master process me
    }
  }

  become(null, WAITING)
  // Connect and start listening immediately
  CommsTower.listen(Set(listener), pkap, ann, ourInit)

  def doProcess(change: Any): Unit = (change, data, state) match {
    case (data1: SyncWorkerPHCData, null, WAITING) => become(data1, PHC_SYNC)
    case (data1: SyncWorkerShortIdsData, null, WAITING) => become(data1, SHORT_ID_SYNC)
    case (data1: SyncWorkerGossipData, _, WAITING | SHORT_ID_SYNC) => become(data1, GOSSIP_SYNC)

    case (worker: CommsTower.Worker, data: SyncWorkerShortIdsData, SHORT_ID_SYNC) =>
      val tlv = QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL)
      val query = QueryChannelRange(LNParams.chainHash, data.from, Int.MaxValue, TlvStream apply tlv)
      worker.handler process query

    case (reply: ReplyChannelRange, data1: SyncWorkerShortIdsData, SHORT_ID_SYNC) =>
      val updatedData: SyncWorkerShortIdsData = data1.copy(ranges = reply +: data1.ranges)
      if (reply.syncComplete == 1) master process CMDShortIdsComplete(me, updatedData)
      else become(updatedData, SHORT_ID_SYNC)

    // GOSSIP_SYNC

    case (_: CommsTower.Worker, _: SyncWorkerGossipData, GOSSIP_SYNC) =>
      // Remote peer is connected, (re-)start remaining gossip sync
      me process CMDGetGossip

    case (CMDGetGossip, data1: SyncWorkerGossipData, GOSSIP_SYNC) =>
      if (data1.queries.isEmpty) {
        // We have no more queries left
        master process CMDGossipComplete(me)
        me process CMDShutdown
      } else {
        // We still have queries left, send another one to peer
        CommsTower.sendMany(data1.queries.headOption, pkap)
      }

    case (update: ChannelUpdate, d1: SyncWorkerGossipData, GOSSIP_SYNC) if d1.syncMaster.provenAndTooSmallOrNoInfo(update) => become(d1.copy(excluded = d1.excluded + update.core), GOSSIP_SYNC)
    case (update: ChannelUpdate, d1: SyncWorkerGossipData, GOSSIP_SYNC) if d1.syncMaster.provenAndNotExcluded(update.shortChannelId) => become(d1.copy(updates = d1.updates + update.lite), GOSSIP_SYNC)
    case (ann: ChannelAnnouncement, d1: SyncWorkerGossipData, GOSSIP_SYNC) if d1.syncMaster.provenShortIds.contains(ann.shortChannelId) => become(d1.copy(announces = d1.announces + ann.lite), GOSSIP_SYNC)

    case (_: ReplyShortChannelIdsEnd, data1: SyncWorkerGossipData, GOSSIP_SYNC) =>
      // We have completed current chunk, inform master and either continue or complete
      become(SyncWorkerGossipData(data1.syncMaster, data1.queries.tail), GOSSIP_SYNC)
      master process CMDChunkComplete(me, data1)
      me process CMDGetGossip

    // PHC_SYNC

    case (worker: CommsTower.Worker, _: SyncWorkerPHCData, PHC_SYNC) => worker.handler process QueryPublicHostedChannels(LNParams.chainHash)
    case (update: ChannelUpdate, d1: SyncWorkerPHCData, PHC_SYNC) if d1.isUpdateAcceptable(update) => become(d1.withNewUpdate(update.lite), PHC_SYNC)
    case (ann: ChannelAnnouncement, d1: SyncWorkerPHCData, PHC_SYNC) if d1.isAcceptable(ann) && d1.phcMaster.isAcceptable(ann) => become(d1.withNewAnnounce(ann.lite), PHC_SYNC)

    case (_: ReplyPublicHostedChannelsEnd, completeSyncData: SyncWorkerPHCData, PHC_SYNC) =>
      // Peer has informed us that there is no more PHC gossip left, inform master and shut down
      master process completeSyncData
      me process CMDShutdown

    case (CMDShutdown, _, _) =>
      become(freshData = null, SHUT_DOWN)
      CommsTower forget pkap

    case _ =>
  }
}

trait SyncMasterData extends {
  def activeSyncs: Set[SyncWorker]
}

trait GetNewSyncMachine extends CanBeRepliedTo { me =>
  def getNewSync(data1: SyncMasterData, allNodes: Set[NodeAnnouncement], ourInit: Init): SyncWorker = {
    val goodAnnounces: Set[NodeAnnouncement] = allNodes -- data1.activeSyncs.map(_.ann)
    SyncWorker(me, randomKeyPair, shuffle(goodAnnounces.toList).head, ourInit)
  }
}

case class PureRoutingData(announces: Set[ChannelAnnouncement], updates: Set[ChannelUpdate], excluded: Set[UpdateCore] = Set.empty)
case class SyncMasterShortIdData(activeSyncs: Set[SyncWorker], collectedRanges: Map[PublicKey, SyncWorkerShortIdsData] = Map.empty) extends SyncMasterData
case class SyncMasterGossipData(activeSyncs: Set[SyncWorker], chunksLeft: Int) extends SyncMasterData

case class UpdateConifrmState(liteUpdOpt: Option[ChannelUpdate], confirmedBy: ConfirmedBySet) {
  def add(cu: ChannelUpdate, from: PublicKey): UpdateConifrmState = copy(liteUpdOpt = Some(cu), confirmedBy = confirmedBy + from)
}

abstract class SyncMaster(extraNodes: Set[NodeAnnouncement], excluded: Set[Long], routerData: Data) extends StateMachine[SyncMasterData] with GetNewSyncMachine { me =>
  val confirmedChanUpdates: mutable.Map[UpdateCore, UpdateConifrmState] = mutable.Map.empty withDefaultValue UpdateConifrmState(None, Set.empty)
  val confirmedChanAnnounces: mutable.Map[ChannelAnnouncement, ConfirmedBySet] = mutable.Map.empty withDefaultValue Set.empty
  var newExcludedChanUpdates: Set[UpdateCore] = Set.empty
  var provenShortIds: ShortChanIdSet = Set.empty

  def onChunkSyncComplete(pure: PureRoutingData): Unit
  def onTotalSyncComplete: Unit

  def provenAndTooSmallOrNoInfo(update: ChannelUpdate): Boolean = provenShortIds.contains(update.shortChannelId) && update.htlcMaximumMsat.forall(_ < minCapacity)
  def provenAndNotExcluded(shortId: ShortChannelId): Boolean = provenShortIds.contains(shortId) && !excluded.contains(shortId.id)

  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(SyncMasterShortIdData(Set.empty, Map.empty), SHORT_ID_SYNC)
  for (_ <- 0 until maxNodesToSyncFrom) me process CMDAddSync

  def doProcess(change: Any): Unit = (change, data, state) match {
    case (CMDAddSync, data1: SyncMasterShortIdData, SHORT_ID_SYNC) if data1.activeSyncs.size < maxNodesToSyncFrom =>
      // Turns out we don't have enough workers, create one with unused remote nodeId and track its progress
      val newSyncWorker = getNewSync(data1, syncNodes ++ extraNodes, LNParams.normInit)

      // Worker is connecting, tell it to get shortIds once connection is there
      become(data1.copy(activeSyncs = data1.activeSyncs + newSyncWorker), SHORT_ID_SYNC)
      newSyncWorker process SyncWorkerShortIdsData(ranges = Nil, from = 0)

    case (sync: SyncWorker, SyncMasterShortIdData(activeSyncs, ranges), SHORT_ID_SYNC) =>
      // Sync has disconnected, stop tracking it and try to connect to another one with delay
      val data1 = SyncMasterShortIdData(activeSyncs - sync, ranges - sync.pkap.them)
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process CMDAddSync)
      become(data1, SHORT_ID_SYNC)

    case (CMDShortIdsComplete(sync, ranges1), SyncMasterShortIdData(currentSyncs, ranges), SHORT_ID_SYNC) =>
      val data1 = SyncMasterShortIdData(collectedRanges = ranges + (sync.pkap.them -> ranges1), activeSyncs = currentSyncs)
      become(data1, SHORT_ID_SYNC)

      if (data1.collectedRanges.size == maxNodesToSyncFrom) {
        // We have collected enough channel ranges to start gossip
        val goodRanges = data1.collectedRanges.values.filter(_.isHolistic)
        val accum = mutable.Map.empty[ShortChannelId, Int] withDefaultValue 0
        goodRanges.flatMap(_.allShortIds).foreach(shortId => accum(shortId) += 1)
        provenShortIds = accum.collect { case (shortId, confs) if confs > acceptThreshold => shortId }.toSet
        val queries = goodRanges.maxBy(_.allShortIds.size).ranges.par.flatMap(reply2Query).toList

        // Transfer every worker into gossip syncing state
        become(SyncMasterGossipData(currentSyncs, chunksLeft = chunksToWait), GOSSIP_SYNC)
        for (currentSync <- currentSyncs) currentSync process SyncWorkerGossipData(me, queries)
        for (currentSync <- currentSyncs) currentSync process CMDGetGossip
      }

    // GOSSIP_SYNC

    case (workerData: SyncWorkerGossipData, data1: SyncMasterGossipData, GOSSIP_SYNC) if data1.activeSyncs.size < maxNodesToSyncFrom =>
      // Turns out one of the workers has disconnected while getting gossip, create one with unused remote nodeId and track its progress
      val newSyncWorker = getNewSync(data1, syncNodes ++ extraNodes, LNParams.normInit)

      // Worker is connecting, tell it to get the rest of gossip once connection is there
      become(data1.copy(activeSyncs = data1.activeSyncs + newSyncWorker), GOSSIP_SYNC)
      newSyncWorker process SyncWorkerGossipData(me, workerData.queries)

    case (sync: SyncWorker, data1: SyncMasterGossipData, GOSSIP_SYNC) =>
      become(data1.copy(activeSyncs = data1.activeSyncs - sync), GOSSIP_SYNC)
      // Sync has disconnected, stop tracking it and try to connect to another one
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process sync.data)

    case (CMDChunkComplete(sync, workerData), data1: SyncMasterGossipData, GOSSIP_SYNC) =>
      for (liteAnnounce <- workerData.announces) confirmedChanAnnounces(liteAnnounce) = confirmedChanAnnounces(liteAnnounce) + sync.pkap.them
      for (liteUpdate <- workerData.updates) confirmedChanUpdates(liteUpdate.core) = confirmedChanUpdates(liteUpdate.core).add(liteUpdate, sync.pkap.them)
      newExcludedChanUpdates ++= workerData.excluded

      if (data1.chunksLeft > 0) {
        // We batch multiple chunks to have less upstream db calls
        val nextData = data1.copy(chunksLeft = data1.chunksLeft - 1)
        become(nextData, GOSSIP_SYNC)
      } else {
        // Batch is ready, send it out and start a new one
        become(data1.copy(chunksLeft = chunksToWait), GOSSIP_SYNC)
        sendPureNormalNetworkData
      }

    case (CMDGossipComplete(sync), data1: SyncMasterGossipData, GOSSIP_SYNC) =>
      val nextData = data1.copy(activeSyncs = data1.activeSyncs - sync)

      if (nextData.activeSyncs.nonEmpty) {
        become(nextData, GOSSIP_SYNC)
      } else {
        become(null, SHUT_DOWN)
        sendPureNormalNetworkData
        onTotalSyncComplete
      }

    case _ =>
  }

  def sendPureNormalNetworkData: Unit = {
    val goodAnnounces = confirmedChanAnnounces.collect { case (announce, confirmedByNodes) if confirmedByNodes.size > acceptThreshold => announce }.toSet
    val goodUpdates = confirmedChanUpdates.values.collect { case UpdateConifrmState(Some(update), confs) if confs.size > acceptThreshold => update }.toSet
    me onChunkSyncComplete PureRoutingData(goodAnnounces, goodUpdates, newExcludedChanUpdates)
    for (announce <- goodAnnounces) confirmedChanAnnounces -= announce
    for (update <- goodUpdates) confirmedChanUpdates -= update.core
    newExcludedChanUpdates = Set.empty
  }

  def reply2Query(reply: ReplyChannelRange): Iterator[QueryShortChannelIds] = {
    val stack = (reply.shortChannelIds.array, reply.timestamps.timestamps, reply.checksums.checksums)

    val shortIdFlagSeq = for {
      (shortId, theirTimestamps, theirChecksums) <- stack.zipped if provenAndNotExcluded(shortId)
      finalFlag = computeFlag(shortId, theirTimestamps, theirChecksums) if finalFlag != 0
    } yield (shortId, finalFlag)

    for {
      requestChunk <- shortIdFlagSeq.toList.grouped(messagesToAsk)
      Tuple2(chunkShortIds, chunkRequestFlags) = requestChunk.unzip
      shortChannelIds = EncodedShortChannelIds(reply.shortChannelIds.encoding, chunkShortIds)
      tlv = QueryShortChannelIdsTlv.EncodedQueryFlags(reply.shortChannelIds.encoding, chunkRequestFlags)
    } yield QueryShortChannelIds(LNParams.chainHash, shortChannelIds, TlvStream apply tlv)
  }

  private def computeFlag(shortlId: ShortChannelId,
                          theirTimestamps: ReplyChannelRangeTlv.Timestamps,
                          theirChecksums: ReplyChannelRangeTlv.Checksums) = {

    if (routerData.channels contains shortlId) {
      val (stamps: ReplyChannelRangeTlv.Timestamps, checksums: ReplyChannelRangeTlv.Checksums) = Sync.getChannelDigestInfo(routerData.channels)(shortlId)
      val shouldRequestUpdate1 = Sync.shouldRequestUpdate(stamps.timestamp1, checksums.checksum1, theirTimestamps.timestamp1, theirChecksums.checksum1)
      val shouldRequestUpdate2 = Sync.shouldRequestUpdate(stamps.timestamp2, checksums.checksum2, theirTimestamps.timestamp2, theirChecksums.checksum2)

      val flagUpdate1 = if (shouldRequestUpdate1) INCLUDE_CHANNEL_UPDATE_1 else 0
      val flagUpdate2 = if (shouldRequestUpdate2) INCLUDE_CHANNEL_UPDATE_2 else 0
      0 | flagUpdate1 | flagUpdate2
    } else {
      INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2
    }
  }
}

case class CompleteHostedRoutingData(announces: Set[ChannelAnnouncement], updates: Set[ChannelUpdate] = Set.empty)
case class SyncMasterPHCData(activeSyncs: Set[SyncWorker], attemptsLeft: Int) extends SyncMasterData { final val maxSyncs: Int = 1 }
abstract class PHCSyncMaster(extraNodes: Set[NodeAnnouncement], routerData: Data) extends StateMachine[SyncMasterPHCData] with GetNewSyncMachine { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(SyncMasterPHCData(Set.empty, attemptsLeft = 12), PHC_SYNC)
  me process CMDAddSync

  // These checks require graph
  def isAcceptable(ann: ChannelAnnouncement): Boolean = {
    val node1HasEnoughIncomingChans = routerData.graph.vertices.getOrElse(ann.nodeId1, Nil).size >= minNormalChansForPHC
    val node2HasEnoughIncomingChans = routerData.graph.vertices.getOrElse(ann.nodeId2, Nil).size >= minNormalChansForPHC
    node1HasEnoughIncomingChans && node2HasEnoughIncomingChans
  }

  def onSyncComplete(pure: CompleteHostedRoutingData): Unit

  def doProcess(change: Any): Unit = (change, state) match {
    case (CMDAddSync, PHC_SYNC) if data.activeSyncs.size < data.maxSyncs =>
      val newSyncWorker = getNewSync(data, hostedSyncNodes ++ extraNodes, LNParams.phcSyncInit)
      become(data.copy(activeSyncs = data.activeSyncs + newSyncWorker), PHC_SYNC)
      newSyncWorker process SyncWorkerPHCData(me)

    case (sync: SyncWorker, PHC_SYNC) if data.attemptsLeft > 0 =>
      // Sync has disconnected, stop tracking it and try to connect to another one with delay
      become(data.copy(data.activeSyncs - sync, attemptsLeft = data.attemptsLeft - 1), PHC_SYNC)
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process CMDAddSync)

    case (_: SyncWorker, PHC_SYNC) =>
      // No more reconnect attempts left
      become(null, SHUT_DOWN)

    case (d1: SyncWorkerPHCData, PHC_SYNC) =>
      // Worker has informed us that PHC sync is complete, shut down
      val pure = CompleteHostedRoutingData(d1.announces.values.toSet, d1.updates)
      become(null, SHUT_DOWN)
      onSyncComplete(pure)
  }
}