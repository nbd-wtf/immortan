package immortan.channel

import scoin._
import scoin.ln._

sealed trait CommitmentOutput

object CommitmentOutput {
  case object ToLocal extends CommitmentOutput
  case object ToRemote extends CommitmentOutput
  case object ToLocalAnchor extends CommitmentOutput
  case object ToRemoteAnchor extends CommitmentOutput
  case class InHtlc(incomingHtlc: IncomingHtlc) extends CommitmentOutput
  case class OutHtlc(outgoingHtlc: OutgoingHtlc) extends CommitmentOutput
}

object DirectedHtlc {
  def incoming: PartialFunction[DirectedHtlc, UpdateAddHtlc] = {
    case h: IncomingHtlc => h.add
  }
  def outgoing: PartialFunction[DirectedHtlc, UpdateAddHtlc] = {
    case h: OutgoingHtlc => h.add
  }
}

sealed trait DirectedHtlc { val add: UpdateAddHtlc }
case class IncomingHtlc(add: UpdateAddHtlc) extends DirectedHtlc
case class OutgoingHtlc(add: UpdateAddHtlc) extends DirectedHtlc

trait RemoteReject { val ourAdd: UpdateAddHtlc }
case class RemoteUpdateFail(fail: UpdateFailHtlc, ourAdd: UpdateAddHtlc)
    extends RemoteReject
case class RemoteUpdateMalform(
    malform: UpdateFailMalformedHtlc,
    ourAdd: UpdateAddHtlc
) extends RemoteReject

case class RemoteFulfill(ourAdd: UpdateAddHtlc, theirPreimage: ByteVector32)
case class LocalFulfill(theirAdd: UpdateAddHtlc, ourPreimage: ByteVector32)

case class CommitmentSpec(
    htlcs: Set[DirectedHtlc],
    toLocal: MilliSatoshi,
    toRemote: MilliSatoshi,
    commitTxFeerate: FeeratePerKw
) {
  def findIncomingHtlcById(id: Long): Option[IncomingHtlc] =
    htlcs.collectFirst { case htlc: IncomingHtlc if htlc.add.id == id => htlc }
  def findOutgoingHtlcById(id: Long): Option[OutgoingHtlc] =
    htlcs.collectFirst { case htlc: OutgoingHtlc if htlc.add.id == id => htlc }
  lazy val incomingAdds: Set[UpdateAddHtlc] =
    htlcs.collect(DirectedHtlc.incoming)
  lazy val outgoingAdds: Set[UpdateAddHtlc] =
    htlcs.collect(DirectedHtlc.outgoing)
}

object CommitmentSpec {
  def addHtlc(
      spec: CommitmentSpec,
      directedHtlc: DirectedHtlc
  ): CommitmentSpec = directedHtlc match {
    case OutgoingHtlc(add) =>
      spec.copy(
        toLocal = spec.toLocal - add.amountMsat,
        htlcs = spec.htlcs + directedHtlc
      )
    case IncomingHtlc(add) =>
      spec.copy(
        toRemote = spec.toRemote - add.amountMsat,
        htlcs = spec.htlcs + directedHtlc
      )
  }

  def fulfillIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec =
    spec.findIncomingHtlcById(htlcId) match {
      case Some(htlc) =>
        spec.copy(
          toLocal = spec.toLocal + htlc.add.amountMsat,
          htlcs = spec.htlcs - htlc
        )
      case None => throw new RuntimeException
    }

  def fulfillOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec =
    spec.findOutgoingHtlcById(htlcId) match {
      case Some(htlc) =>
        spec.copy(
          toRemote = spec.toRemote + htlc.add.amountMsat,
          htlcs = spec.htlcs - htlc
        )
      case None => throw new RuntimeException
    }

  def failIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec =
    spec.findIncomingHtlcById(htlcId) match {
      case Some(htlc) =>
        spec.copy(
          toRemote = spec.toRemote + htlc.add.amountMsat,
          htlcs = spec.htlcs - htlc
        )
      case None => throw new RuntimeException
    }

  def failOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec =
    spec.findOutgoingHtlcById(htlcId) match {
      case Some(htlc) =>
        spec.copy(
          toLocal = spec.toLocal + htlc.add.amountMsat,
          htlcs = spec.htlcs - htlc
        )
      case None => throw new RuntimeException
    }

  def reduce(
      localCommitSpec: CommitmentSpec,
      localChanges: List[UpdateMessage],
      remoteChanges: List[UpdateMessage] = Nil
  ): CommitmentSpec = {
    val spec1 = localChanges.foldLeft(localCommitSpec) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, OutgoingHtlc(u))
      case (spec, _)                => spec
    }

    val spec2 = remoteChanges.foldLeft(spec1) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, IncomingHtlc(u))
      case (spec, _)                => spec
    }

    val spec3 = localChanges.foldLeft(spec2) {
      case (spec, u: UpdateFulfillHtlc)       => fulfillIncomingHtlc(spec, u.id)
      case (spec, u: UpdateFailHtlc)          => failIncomingHtlc(spec, u.id)
      case (spec, u: UpdateFailMalformedHtlc) => failIncomingHtlc(spec, u.id)
      case (spec, _)                          => spec
    }

    val spec4 = remoteChanges.foldLeft(spec3) {
      case (spec, u: UpdateFulfillHtlc)       => fulfillOutgoingHtlc(spec, u.id)
      case (spec, u: UpdateFailHtlc)          => failOutgoingHtlc(spec, u.id)
      case (spec, u: UpdateFailMalformedHtlc) => failOutgoingHtlc(spec, u.id)
      case (spec, _)                          => spec
    }

    val spec5 = (localChanges ++ remoteChanges).foldLeft(spec4) {
      case (spec, u: UpdateFee) => spec.copy(commitTxFeerate = u.feeratePerKw)
      case (spec, _)            => spec
    }

    spec5
  }
}
