package immortan

import immortan.crypto.Tools._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteReject}
import fr.acinq.eclair.wire.{LightningMessage, UpdateAddHtlc}
import scala.concurrent.ExecutionContextExecutor
import immortan.Channel.channelContext
import java.util.concurrent.Executors
import immortan.crypto.StateMachine
import fr.acinq.eclair.MilliSatoshi
import immortan.crypto.Tools.none
import scala.concurrent.Future
import scala.util.Failure
import akka.actor.Actor


object Channel {
  val WAIT_FOR_INIT = "WAIT-FOR-INIT"
  val WAIT_FOR_ACCEPT = "WAIT-FOR-ACCEPT"
  val WAIT_FUNDING_DONE = "WAIT-FUNDING-DONE"
  val SUSPENDED = "SUSPENDED"
  val SLEEPING = "SLEEPING"
  val CLOSING = "CLOSING"
  val OPEN = "OPEN"

  // Single stacking thread for all channels, must be used when asking channels for pending payments to avoid race conditions
  implicit val channelContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext fromExecutor Executors.newSingleThreadExecutor

  def chanAndCommitsOpt(chan: Channel): Option[ChanAndCommits] = chan.data match {
    case commits: HasNormalCommitments => ChanAndCommits(chan, commits.commitments).toSome
    case commits: HostedCommits => ChanAndCommits(chan, commits).toSome
    case _ => None
  }

  def isOperational(chan: Channel): Boolean = chan.data match {
    case data: DATA_NORMAL => data.localShutdown.isEmpty && data.remoteShutdown.isEmpty
    case hostedCommits: HostedCommits => hostedCommits.getError.isEmpty
    case _ => false
  }

  def isWaiting(chan: Channel): Boolean = chan.data match {
    case _: DATA_WAIT_FOR_FUNDING_CONFIRMED => true
    case _: DATA_WAIT_FOR_FUNDING_LOCKED => true
    case _ => false
  }

  def isOperationalOrWaiting(chan: Channel): Boolean = isOperational(chan) || isWaiting(chan)

  def isOperationalAndOpen(chan: Channel): Boolean = isOperational(chan) && OPEN == chan.state
}

trait Channel extends StateMachine[ChannelData] { me =>
  def process(change: Any): Unit = Future(me doProcess change) onComplete {
    case Failure(reason) => events.onException(me -> reason)
    case _ => // Do nothing
  }

  def SEND(msg: LightningMessage*): Unit

  def STORE(data: PersistentChannelData): PersistentChannelData

  def BECOME(data1: ChannelData, state1: String): Unit = {
    // Transition must be defined before vars are updated
    val trans = (me, data, data1, state, state1)
    super.become(data1, state1)
    events.onBecome(trans)
  }

  def StoreBecomeSend(data1: PersistentChannelData, state1: String, lnMessage: LightningMessage*): Unit = {
    // Storing goes first to ensure we retain an updated data before revealing it if anything goes wrong

    STORE(data1)
    SEND(lnMessage:_*)
    BECOME(data1, state1)
  }

  var listeners = Set.empty[ChannelListener]

  val events: ChannelListener = new ChannelListener {
    override def onException: PartialFunction[ChannelListener.Malfunction, Unit] = { case failure => for (lst <- listeners if lst.onException isDefinedAt failure) lst onException failure }
    override def onBecome: PartialFunction[ChannelListener.Transition, Unit] = { case transition => for (lst <- listeners if lst.onBecome isDefinedAt transition) lst onBecome transition }
    override def stateUpdated(rejects: Seq[RemoteReject] = Nil): Unit = for (lst <- listeners) lst.stateUpdated(rejects)
    override def fulfillReceived(fulfill: RemoteFulfill): Unit = for (lst <- listeners) lst.fulfillReceived(fulfill)
    override def addReceived(remoteAdd: UpdateAddHtlc): Unit = for (lst <- listeners) lst.addReceived(remoteAdd)
  }

  class Receiver extends Actor {
    override def receive: Receive = {
      case message => me process message
    }
  }
}

object ChannelListener {
  type Malfunction = (Channel, Throwable)
  type Transition = (Channel, ChannelData, ChannelData, String, String)
}

trait ChannelListener {
  def onException: PartialFunction[ChannelListener.Malfunction, Unit] = none
  def onBecome: PartialFunction[ChannelListener.Transition, Unit] = none
  def stateUpdated(rejects: Seq[RemoteReject] = Nil): Unit = none
  def fulfillReceived(fulfill: RemoteFulfill): Unit = none
  def addReceived(remoteAdd: UpdateAddHtlc): Unit = none
}

case class ChanAndCommits(chan: Channel, commits: Commitments)

case class CommitsAndMax(commits: List[ChanAndCommits], maxReceivable: MilliSatoshi)
