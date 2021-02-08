/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import scodec.bits.ByteVector

/**
 * Created by PM on 07/12/2016.
 */

sealed trait CommitmentOutput

object CommitmentOutput {

  case object ToLocal extends CommitmentOutput

  case object ToRemote extends CommitmentOutput

  case object ToLocalAnchor extends CommitmentOutput

  case object ToRemoteAnchor extends CommitmentOutput

  case class InHtlc(incomingHtlc: IncomingHtlc) extends CommitmentOutput

  case class OutHtlc(outgoingHtlc: OutgoingHtlc) extends CommitmentOutput

}

sealed trait DirectedHtlc {
  val add: UpdateAddHtlc
}

object DirectedHtlc {
  def incoming: PartialFunction[DirectedHtlc, UpdateAddHtlc] = {
    case h: IncomingHtlc => h.add
  }

  def outgoing: PartialFunction[DirectedHtlc, UpdateAddHtlc] = {
    case h: OutgoingHtlc => h.add
  }
}

case class IncomingHtlc(add: UpdateAddHtlc) extends DirectedHtlc

case class OutgoingHtlc(add: UpdateAddHtlc) extends DirectedHtlc

trait RemoteFailed {
  val paymentHash: ByteVector32
  val amount: MilliSatoshi
  val partId: ByteVector
}

case class TheirFailed(reason: ByteVector, paymentHash: ByteVector32, amount: MilliSatoshi, partId: ByteVector) extends RemoteFailed

case class TheirMalformed(paymentHash: ByteVector32, amount: MilliSatoshi, partId: ByteVector) extends RemoteFailed

case class OurFulfilled(paymentHash: ByteVector32, incomingAmount: MilliSatoshi)

final case class CommitmentSpec(feeratePerKw: FeeratePerKw, toLocal: MilliSatoshi, toRemote: MilliSatoshi, htlcs: Set[DirectedHtlc] = Set.empty,
                                remoteFailed: Set[TheirFailed] = Set.empty, remoteMalformed: Set[TheirMalformed] = Set.empty,
                                localFulfilled: Set[OurFulfilled] = Set.empty) {

  lazy val incomingAdds: Set[UpdateAddHtlc] = htlcs.collect(DirectedHtlc.incoming)

  lazy val outgoingAdds: Set[UpdateAddHtlc] = htlcs.collect(DirectedHtlc.outgoing)

  def findIncomingHtlcById(id: Long): Option[IncomingHtlc] = htlcs.collectFirst { case htlc: IncomingHtlc if htlc.add.id == id => htlc }

  def findOutgoingHtlcById(id: Long): Option[OutgoingHtlc] = htlcs.collectFirst { case htlc: OutgoingHtlc if htlc.add.id == id => htlc }

  def withoutHistory: CommitmentSpec = copy(remoteFailed = Set.empty, remoteMalformed = Set.empty, localFulfilled = Set.empty)
}

object CommitmentSpec {
  def removeHtlc(changes: List[UpdateMessage], id: Long): List[UpdateMessage] =
    changes filterNot { case update: UpdateAddHtlc => update.id == id case _ => false }

  def addHtlc(spec: CommitmentSpec, directedHtlc: DirectedHtlc): CommitmentSpec = directedHtlc match {
    case OutgoingHtlc(add) => spec.copy(toLocal = spec.toLocal - add.amountMsat, htlcs = spec.htlcs + directedHtlc)
    case IncomingHtlc(add) => spec.copy(toRemote = spec.toRemote - add.amountMsat, htlcs = spec.htlcs + directedHtlc)
  }

  def fulfillIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
    spec.findIncomingHtlcById(htlcId) match {
      case Some(htlc) =>
        val localFulfilled = spec.localFulfilled + OurFulfilled(htlc.add.paymentHash, htlc.add.amountMsat)
        spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - htlc, localFulfilled = localFulfilled)
      case None => throw new RuntimeException(s"Cannot find htlc id=$htlcId")
    }
  }

  def fulfillOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = spec.findOutgoingHtlcById(htlcId) match {
    case Some(htlc) => spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
    case None => throw new RuntimeException(s"Cannot find htlc id=$htlcId")
  }

  def failIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = spec.findIncomingHtlcById(htlcId) match {
    case Some(htlc) => spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
    case None => throw new RuntimeException(s"Cannot find htlc id=$htlcId")
  }

  def failOutgoingHtlc(spec: CommitmentSpec, htlcId: Long, u: UpdateFailHtlc): CommitmentSpec = {
    spec.findOutgoingHtlcById(htlcId) match {
      case Some(htlc) =>
        val remoteFailed1 = spec.remoteFailed + TheirFailed(u.reason, htlc.add.paymentHash, htlc.add.amountMsat, htlc.add.partId)
        spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - htlc, remoteFailed = remoteFailed1)
      case None => throw new RuntimeException(s"Cannot find htlc id=$htlcId")
    }
  }

  def failOutgoingMalformedHtlc(spec: CommitmentSpec, htlcId: Long, u: UpdateFailMalformedHtlc): CommitmentSpec = {
    spec.findOutgoingHtlcById(htlcId) match {
      case Some(htlc) =>
        val remoteMalformed1 = spec.remoteMalformed + TheirMalformed(htlc.add.paymentHash, htlc.add.amountMsat, htlc.add.partId)
        spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - htlc, remoteMalformed = remoteMalformed1)
      case None => throw new RuntimeException(s"Cannot find htlc id=$htlcId")
    }
  }

  def reduce(localCommitSpec: CommitmentSpec, localChanges: List[LightningMessage] = Nil, remoteChanges: List[LightningMessage] = Nil): CommitmentSpec = {
    val spec1 = localChanges.foldLeft(localCommitSpec.withoutHistory) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, OutgoingHtlc(u))
      case (spec, _) => spec
    }
    val spec2 = remoteChanges.foldLeft(spec1) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, IncomingHtlc(u))
      case (spec, _) => spec
    }
    val spec3 = localChanges.foldLeft(spec2) {
      case (spec, u: UpdateFulfillHtlc) => fulfillIncomingHtlc(spec, u.id)
      case (spec, u: UpdateFailHtlc) => failIncomingHtlc(spec, u.id)
      case (spec, u: UpdateFailMalformedHtlc) => failIncomingHtlc(spec, u.id)
      case (spec, _) => spec
    }
    val spec4 = remoteChanges.foldLeft(spec3) {
      case (spec, u: UpdateFulfillHtlc) => fulfillOutgoingHtlc(spec, u.id)
      case (spec, u: UpdateFailHtlc) => failOutgoingHtlc(spec, u.id, u)
      case (spec, u: UpdateFailMalformedHtlc) => failOutgoingMalformedHtlc(spec, u.id, u)
      case (spec, _) => spec
    }
    val spec5 = (localChanges ++ remoteChanges).foldLeft(spec4) {
      case (spec, u: UpdateFee) => spec.copy(feeratePerKw = u.feeratePerKw)
      case (spec, _) => spec
    }
    spec5
  }
}