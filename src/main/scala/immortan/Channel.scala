package immortan

import immortan.crypto.Tools._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.{LightningMessage, UpdateFulfillHtlc}
import scala.concurrent.ExecutionContextExecutor
import immortan.Channel.channelContext
import java.util.concurrent.Executors
import immortan.crypto.StateMachine
import fr.acinq.eclair.MilliSatoshi
import immortan.crypto.Tools.none
import scala.concurrent.Future
import scala.util.Failure


object Channel {
  val WAIT_FOR_INIT = "WAIT-FOR-INIT"
  val WAIT_FOR_ACCEPT = "WAIT-FOR-ACCEPT"
  // All states below are persisted
  val SUSPENDED = "SUSPENDED"
  val SLEEPING = "SLEEPING"
  val OPEN = "OPEN"

  // Single stacking thread for all channels, must be used when asking channels for pending payments to avoid race conditions
  implicit val channelContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext fromExecutor Executors.newSingleThreadExecutor

  def make(initListeners: Set[ChannelListener], chanData: ChannelData, bag: ChannelBag): Channel = chanData match {
    case hasNormalCommits: HasNormalCommitments => NormalChannel.make(initListeners, hasNormalCommits, bag)
    case hostedCommits: HostedCommits => HostedChannel.make(initListeners, hostedCommits, bag)
    case otherwise => throw new RuntimeException(s"Could not process $otherwise")
  }

  def chanAndCommitsOpt(chan: Channel): Option[ChanAndCommits] = chan.data match {
    case hasNormalCommits: HasNormalCommitments => ChanAndCommits(chan, hasNormalCommits.commitments).toSome
    case hostedCommits: HostedCommits => ChanAndCommits(chan, hostedCommits).toSome
    case _ => None
  }

  def isOperational(chan: Channel): Boolean = chan.data match {
    case data: DATA_NORMAL => data.localShutdown.isEmpty && data.remoteShutdown.isEmpty
    case hostedCommits: HostedCommits => hostedCommits.getError.isEmpty
    case _ => false
  }

  def isOpening(chan: Channel): Boolean = chan.data match {
    case _: DATA_WAIT_FOR_FUNDING_CONFIRMED => true
    case _: DATA_WAIT_FOR_FUNDING_LOCKED => true
    case _ => false
  }

  def isOperationalAndOpen(chan: Channel): Boolean = isOperational(chan) && OPEN == chan.state
  def isOpeningOrOperational(chan: Channel): Boolean = isOpening(chan) || isOperational(chan)
}

trait Channel extends StateMachine[ChannelData] { me =>
  def process(change: Any): Unit = Future(me doProcess change) onComplete {
    case Failure(failureReason) => events.onException(me -> failureReason)
    case _ => // Do nothing
  }

  def SEND(msg: LightningMessage *): Unit
  def STORE(data: ChannelData): ChannelData

  def BECOME(data1: ChannelData, state1: String): Unit = {
    // Transition must be defined before vars are updated
    val trans = (me, data, data1, state, state1)
    super.become(data1, state1)
    events.onBecome(trans)
  }

  def STORE_BECOME_SEND(data1: ChannelData, state1: String, lnMessage: LightningMessage *): Unit = {
    // Storing goes first to ensure we retain an updated data before revealing it if anything goes wrong

    STORE(data1)
    SEND(lnMessage:_*)
    BECOME(data1, state1)
  }

  var listeners = Set.empty[ChannelListener]
  val events: ChannelListener = new ChannelListener {
    override def onProcessSuccess: PartialFunction[ChannelListener.Incoming, Unit] = { case ps => for (lst <- listeners if lst.onProcessSuccess isDefinedAt ps) lst onProcessSuccess ps }
    override def onException: PartialFunction[ChannelListener.Malfunction, Unit] = { case failure => for (lst <- listeners if lst.onException isDefinedAt failure) lst onException failure }
    override def onBecome: PartialFunction[ChannelListener.Transition, Unit] = { case transition => for (lst <- listeners if lst.onBecome isDefinedAt transition) lst onBecome transition }
    override def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = for (lst <- listeners) lst fulfillReceived fulfill
    override def stateUpdated(hc: HostedCommits): Unit = for (lst <- listeners) lst stateUpdated hc
  }
}

object ChannelListener {
  type Malfunction = (Channel, Throwable)
  type Incoming = (Channel, ChannelData, Any)
  type Transition = (Channel, ChannelData, ChannelData, String, String)
}

trait ChannelListener {
  def onProcessSuccess: PartialFunction[ChannelListener.Incoming, Unit] = none
  def onException: PartialFunction[ChannelListener.Malfunction, Unit] = none
  def onBecome: PartialFunction[ChannelListener.Transition, Unit] = none
  def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = none
  def stateUpdated(hc: HostedCommits): Unit = none
}

case class ChanAndCommits(chan: Channel, commits: Commitments)

case class CommitsAndMax(commits: List[ChanAndCommits], maxReceivable: MilliSatoshi)
