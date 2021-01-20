package immortan.crypto

import fr.acinq.eclair._
import fr.acinq.bitcoin._
import scala.util.{Success, Try}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.wire.{Color, NodeAddress, NodeAnnouncement}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.router.RouteCalculation
import immortan.crypto.Noise.KeyPair
import java.io.ByteArrayInputStream
import language.implicitConversions
import scala.collection.mutable
import scodec.bits.ByteVector
import java.nio.ByteOrder


object Tools {
  type Bytes = Array[Byte]
  type Fiat2Btc = Map[String, Double]

  def none: PartialFunction[Any, Unit] = { case _ => }
  def runAnd[T](result: T)(action: Any): T = result

  implicit class Any2Some[T](underlying: T) {
    def toSome: Option[T] = Some(underlying)
  }

  def toMapBy[K, V](items: Iterable[V], mapper: V => K): Map[K, V] = items.map(item => mapper(item) -> item).toMap
  def mapKeys[K, V, K1](m: mutable.Map[K, V], fun: K => K1, defVal: V): mutable.Map[K1, V] = m map { case (key, value) => fun(key) -> value } withDefaultValue defVal
  def memoize[In, Out](fun: In => Out): collection.mutable.HashMap[In, Out] = new collection.mutable.HashMap[In, Out] { self =>
    override def apply(key: In): Out = getOrElseUpdate(key, fun apply key)
  }

  def randomBest[T, B](bestItem: T, mapper: T => B, items: Iterable[T] = Nil): T = {
    // Given a list and a best item from it, get random best if there are many equally good items
    val bestItems = items.filter(item => mapper(item) == mapper(bestItem) || item == bestItem)
    scala.util.Random.shuffle(bestItems).head
  }

  def hostedNodesCombined(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector = {
    val pubkey1First: Boolean = LexicographicalOrdering.isLessThan(pubkey1, pubkey2)
    if (pubkey1First) pubkey1 ++ pubkey2 else pubkey2 ++ pubkey1
  }

  def hostedChanId(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector32 = {
    val nodesCombined = hostedNodesCombined(pubkey1, pubkey2)
    Crypto.sha256(nodesCombined)
  }

  def hostedShortChanId(pubkey1: ByteVector, pubkey2: ByteVector): ShortChannelId = {
    val stream = new ByteArrayInputStream(hostedNodesCombined(pubkey1, pubkey2).toArray)
    def getChunk: Long = Protocol.uint64(stream, ByteOrder.BIG_ENDIAN)
    val id = List.fill(8)(getChunk).foldLeft(Long.MaxValue)(_ % _)
    ShortChannelId(id)
  }

  def mkNodeAnnouncement(id: PublicKey, na: NodeAddress, alias: String): NodeAnnouncement =
    NodeAnnouncement(signature = ByteVector64.Zeroes, features = Features.empty, timestamp = 0L,
      nodeId = id, rgbColor = Color(-128, -128, -128), alias, addresses = na :: Nil)

  def mkFakeLocalEdge(from: PublicKey, toPeer: PublicKey): GraphEdge = {
    // Augments a graph with local edge corresponding to our hosted channel
    // Parameters do not matter except that it must point from us to peer

    val zeroCltvDelta = CltvExpiryDelta(0)
    val randomShortChannelId = ShortChannelId(secureRandom.nextLong)
    val fakeDesc = ChannelDesc(randomShortChannelId, from, to = toPeer)
    val fakeHop = ExtraHop(from, randomShortChannelId, MilliSatoshi(0L), 0L, zeroCltvDelta)
    GraphEdge(updExt = RouteCalculation.toFakeUpdate(fakeHop), desc = fakeDesc)
  }

  def randomKeyPair: KeyPair = {
    val pk: PrivateKey = randomKey
    KeyPair(pk.publicKey.value, pk.value)
  }

  def isValidFinalScriptPubkey(raw: ByteVector): Boolean = Try(Script parse raw) match {
    case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pkh, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) => pkh.size == 20
    case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) => scriptHash.size == 20
    case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.length == 20 => true
    case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.length == 32 => true
    case _ => false
  }
}

class LightningException(reason: String = "Lightning related failure") extends RuntimeException(reason)
case class CMDAddImpossible(cmd: immortan.CMD_ADD_HTLC, code: Int) extends LightningException

trait CanBeRepliedTo {
  def process(reply: Any): Unit
}

abstract class StateMachine[T] {
  def become(freshData: T, freshState: String): StateMachine[T] = {
    // Update state, data and return itself for easy chaining operations
    state = freshState
    data = freshData
    this
  }

  def doProcess(change: Any): Unit
  var state: String = _
  var data: T = _
}