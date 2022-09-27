package immortan

import java.util.concurrent.Executors

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.electrum._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteReject}
import fr.acinq.eclair.wire.LightningMessage
import immortan.Channel.channelContext
import immortan.crypto.Tools._
import immortan.crypto.{CanBeRepliedTo, StateMachine}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Failure

object Channel {
  sealed trait State
  case object Initial extends State
  case object WaitForInit extends State
  case object WaitForAccept extends State
  case object WaitFundingDone extends State
  case object Sleeping extends State
  case object Closing extends State
  case object Open extends State

  // Single stacking thread for all channels, must be used when asking channels for pending payments to avoid race conditions
  implicit val channelContext: ExecutionContextExecutor =
    scala.concurrent.ExecutionContext fromExecutor Executors.newSingleThreadExecutor

  def load(
      listeners: Set[ChannelListener],
      bag: ChannelBag
  ): Map[ByteVector32, Channel] = bag.all.map {
    case data: HasNormalCommitments =>
      data.channelId -> ChannelNormal.make(listeners, data, bag)
    case data: HostedCommits =>
      data.channelId -> ChannelHosted.make(listeners, data, bag)
    case _ => throw new RuntimeException
  }.toMap

  def chanAndCommitsOpt(chan: Channel): Option[ChanAndCommits] =
    chan.data match {
      case data: HasNormalCommitments =>
        Some(ChanAndCommits(chan, data.commitments))
      case data: HostedCommits => Some(ChanAndCommits(chan, data))
      case _                   => None
    }

  def isOperational(chan: Channel): Boolean = chan.data match {
    case data: DATA_NORMAL =>
      data.localShutdown.isEmpty && data.remoteShutdown.isEmpty
    case hostedCommits: HostedCommits => hostedCommits.error.isEmpty
    case _                            => false
  }

  def isErrored(chan: Channel): Boolean = chan.data match {
    case hostedCommits: HostedCommits if !hostedCommits.error.isEmpty => true
    case _                                                            => false
  }

  def isWaiting(chan: Channel): Boolean =
    chan.data match {
      case _: DATA_WAIT_FOR_FUNDING_CONFIRMED => true
      case _: DATA_WAIT_FOR_FUNDING_LOCKED    => true
      case _                                  => false
    }

  def isOperationalOrWaiting(chan: Channel): Boolean =
    isOperational(chan) || isWaiting(chan)

  def isOperationalAndOpen(chan: Channel): Boolean =
    isOperational(chan) && chan.state == Open

  def isOperationalAndSleeping(chan: Channel): Boolean =
    isOperational(chan) && chan.state == Sleeping

  def totalBalance(chans: Iterable[Channel] = Nil): MilliSatoshi =
    chans.filter(isOperationalOrWaiting).map(_.data.ourBalance).sum
}

trait Channel
    extends StateMachine[ChannelData, Channel.State]
    with CanBeRepliedTo {
  def initialState = Channel.Initial

  def process(changeMsg: Any): Unit = {
    Future(doProcess(changeMsg)).onComplete {
      case Failure(reason) => events.onException((reason, this, data))
      case _               => // Do nothing
    }
  }

  def SEND(msg: LightningMessage*): Unit

  def STORE(data: PersistentChannelData): PersistentChannelData

  def BECOME(newData: ChannelData, newState: Channel.State): Unit = {
    // Transition must be defined before vars are updated
    val trans = (this, data, newData, state, newState)
    super.become(newData, newState)
    events.onBecome(trans)
  }

  def StoreBecomeSend(
      newData: PersistentChannelData,
      newState: Channel.State,
      lnMessage: LightningMessage*
  ): Unit = {
    // Storing first to ensure we retain an updated data before revealing it if anything goes wrong
    STORE(newData)
    BECOME(newData, newState)
    SEND(lnMessage: _*)
  }

  var listeners = Set.empty[ChannelListener]

  val events: ChannelListener = new ChannelListener {
    override def onException
        : PartialFunction[ChannelListener.Malfunction, Unit] = { case tuple =>
      for (lst <- listeners if lst.onException isDefinedAt tuple)
        lst onException tuple
    }
    override def onBecome: PartialFunction[ChannelListener.Transition, Unit] = {
      case tuple =>
        for (lst <- listeners if lst.onBecome isDefinedAt tuple)
          lst onBecome tuple
    }
    override def addRejectedRemotely(reason: RemoteReject): Unit = for (
      lst <- listeners
    ) lst.addRejectedRemotely(reason)
    override def addRejectedLocally(reason: LocalReject): Unit = for (
      lst <- listeners
    ) lst.addRejectedLocally(reason)
    override def fulfillReceived(fulfill: RemoteFulfill): Unit = for (
      lst <- listeners
    ) lst.fulfillReceived(fulfill)
    override def addReceived(add: UpdateAddHtlcExt): Unit = for (
      lst <- listeners
    ) lst.addReceived(add)
    override def notifyResolvers(): Unit = for (lst <- listeners)
      lst.notifyResolvers()
  }

  val receiver = new castor.SimpleActor[Any]()(
    castor.Context.Simple.global
  ) { self =>
    EventStream.subscribe { case c: CurrentBlockCount =>
      self.send(c)
    }

    var lastSeenBlockCount: Option[CurrentBlockCount] = None
    var useDelay = true

    def run(msg: Any): Unit = msg match {
      case currentBlockCount: CurrentBlockCount
          if lastSeenBlockCount.isEmpty && useDelay => {
        val t = new java.util.Timer()
        val task = new java.util.TimerTask {
          def run() = {
            // Propagate subsequent block counts right away
            useDelay = false
            lastSeenBlockCount = None
            // Popagate the last delayed block count
            lastSeenBlockCount.foreach(process)

          }
        }
        t.schedule(task, 10000L)
        lastSeenBlockCount = Some(currentBlockCount)
        useDelay = true
      }

      case currentBlockCount: CurrentBlockCount
          if lastSeenBlockCount.isDefined && useDelay => {
        // We may get another chain tip while delaying a current one: store a new one then
        lastSeenBlockCount = Some(currentBlockCount)
        useDelay = true
      }

      case message =>
        process(message)
    }
  }
}

object ChannelListener {
  type Malfunction = (Throwable, Channel, ChannelData)
  type Transition =
    (Channel, ChannelData, ChannelData, Channel.State, Channel.State)
}

trait ChannelListener {
  def onException: PartialFunction[ChannelListener.Malfunction, Unit] = none
  def onBecome: PartialFunction[ChannelListener.Transition, Unit] = none
  def addRejectedRemotely(reason: RemoteReject): Unit = none
  def addRejectedLocally(reason: LocalReject): Unit = none
  def fulfillReceived(fulfill: RemoteFulfill): Unit = none
  def addReceived(add: UpdateAddHtlcExt): Unit = none
  def notifyResolvers(): Unit = none
}

case class ChanAndCommits(chan: Channel, commits: Commitments)
case class CommitsAndMax(
    commits: Seq[ChanAndCommits],
    maxReceivable: MilliSatoshi
)
