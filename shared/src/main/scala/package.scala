import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.implicitConversions
import scodec.{Codec, DecodeResult}
import scodec.bits.ByteVector
import scodec.codecs._
import rx.lang.scala.Observable
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import scoin.Crypto.{PrivateKey, PublicKey, ChaCha20Poly1305}
import scoin.Psbt.KeyPathWithMaster
import scoin._
import scoin.DeterministicWallet.{ExtendedPrivateKey, KeyPath}
import scoin.ln._
import scoin.ln.CommonCodecs._
import scoin.ln.Bolt11Invoice.ExtraHop

import immortan.electrum.ElectrumWallet.GenerateTxResponse
import immortan.channel.CommitmentSpec
import immortan.router.Graph.GraphStructure.GraphEdge
import immortan.router.RouteCalculation
import immortan.router.Router.ChannelDesc
import immortan.crypto.Noise.KeyPair
import immortan.utils.{FeeRatesInfo, ThrottledWork}
import immortan.Channel
import immortan._

package object immortan {
  sealed trait ChannelKind
  case object IrrelevantChannelKind extends ChannelKind
  case object HostedChannelKind extends ChannelKind
  case object NormalChannelKind extends ChannelKind

  case class FullPaymentTag(
      paymentHash: ByteVector32,
      paymentSecret: ByteVector32,
      tag: Int
  )

  case class ShortPaymentTag(paymentSecret: ByteVector32, tag: Int)

  case class EncryptedPaymentSecret(data: ByteVector) extends Tlv

  object PaymentTagTlv {
    final val LOCALLY_SENT = 0
    final val TRAMPLOINE_ROUTED = 1
    final val FINAL_INCOMING = 2

    final val TLV_TAG = UInt64(4127926135L)

    type EncryptedSecretStream = TlvStream[EncryptedPaymentSecret]

    val shortPaymentTagCodec =
      (("paymentSecret" | bytes32) ::
        ("tag" | int32)).as[ShortPaymentTag]
  }

  implicit class UpdateAddHtlcOps(add: UpdateAddHtlc) {
    // Important: LNParams.secret must be defined
    def fullTagOpt: Option[FullPaymentTag] = for {
      cipherBytes <- add.tlvStream.unknown
        .find(_.tag == PaymentTagTlv.TLV_TAG)
        .map(_.value)

      plainBytes <- chaChaDecrypt(
        LNParams.secret.keys.ourNodePrivateKey.value,
        cipherBytes
      ).toOption

      DecodeResult(shortTag, _) <- PaymentTagTlv.shortPaymentTagCodec
        .decode(plainBytes.toBitVector)
        .toOption

    } yield FullPaymentTag(
      add.paymentHash,
      shortTag.paymentSecret,
      shortTag.tag
    )

    // This is relevant for outgoing payments,
    // NO_SECRET means this is NOT an outgoing local or trampoline-routed payment
    lazy val fullTag: FullPaymentTag = fullTagOpt getOrElse FullPaymentTag(
      add.paymentHash,
      ChannelMaster.NO_SECRET,
      PaymentTagTlv.LOCALLY_SENT
    )

    // This is relevant for outgoing payments (with these we can ensure onion key uniqueness)
    final lazy val partId: ByteVector = add.onionRoutingPacket.publicKey
  }

  case class UpdateCore(
      position: java.lang.Integer,
      shortChannelId: ShortChannelId,
      feeBase: MilliSatoshi,
      feeProportionalMillionths: Long,
      cltvExpiryDelta: CltvExpiryDelta,
      htlcMaximumMsat: MilliSatoshi
  ) {
    def noPosition: UpdateCore = this.copy(position = 0)
  }

  implicit class ChannelUpdateOps(cu: ChannelUpdate) {
    def position: Int = if (cu.channelFlags.isNode1) 1 else 2

    lazy val core: UpdateCore = UpdateCore(
      position,
      cu.shortChannelId,
      cu.feeBaseMsat,
      cu.feeProportionalMillionths,
      cu.cltvExpiryDelta,
      cu.htlcMaximumMsat
    )

    def extraHop(nodeId: PublicKey): ExtraHop = ExtraHop(
      nodeId,
      cu.shortChannelId,
      cu.feeBaseMsat,
      cu.feeProportionalMillionths,
      cu.cltvExpiryDelta
    )

    // Point useless fields to same object, db-restored should be same, make sure it does not erase channelUpdateChecksumCodec fields
    def lite: ChannelUpdate = cu.copy(
      signature = ByteVector64.Zeroes,
      LNParams.chainHash
    )
  }

  implicit class ChannelAnnouncementOps(ann: ChannelAnnouncement) {
    def isPHC: Boolean =
      ann.bitcoinKey1 == ann.nodeId1 &&
        ann.bitcoinKey2 == ann.nodeId2 &&
        ann.bitcoinSignature1 == ann.nodeSignature1 &&
        ann.bitcoinSignature2 == ann.nodeSignature2

    def getNodeIdSameSideAs(cu: ChannelUpdate): PublicKey =
      if (cu.position == 1) ann.nodeId1 else ann.nodeId2

    // Point useless fields to same object, db-restored should be the same
    def lite: ChannelAnnouncement =
      ann.copy(
        nodeSignature1 = ByteVector64.Zeroes,
        nodeSignature2 = ByteVector64.Zeroes,
        bitcoinSignature1 = ByteVector64.Zeroes,
        bitcoinSignature2 = ByteVector64.Zeroes,
        features = Features.empty,
        chainHash = LNParams.chainHash,
        bitcoinKey1 = invalidPubKey,
        bitcoinKey2 = invalidPubKey
      )
  }

  trait CanBeShutDown {
    def becomeShutDown(): Unit
  }

  trait CanBeRepliedTo {
    def process(reply: Any): Unit
  }

  abstract class StateMachine[T, S] {
    def become(freshData: T, freshState: S): StateMachine[T, S] = {
      // Update state, data and return itself for easy chaining operations
      state = freshState
      data = freshData
      this
    }

    def initialState: S

    def doProcess(change: Any): Unit
    var TOTAL_INTERVAL_SECONDS: Long = 60
    var secondsLeft: Long = _
    var state: S = initialState
    var data: T = _

    lazy val delayedCMDWorker: ThrottledWork[String, Long] =
      new ThrottledWork[String, Long] {
        def work(cmd: String): Observable[Long] =
          Observable.interval(1.second).doOnSubscribe {
            secondsLeft = TOTAL_INTERVAL_SECONDS
          }

        def process(cmd: String, tickUpdateInterval: Long): Unit = {
          secondsLeft = TOTAL_INTERVAL_SECONDS - (tickUpdateInterval + 1)
          if (secondsLeft <= 0L) {
            doProcess(cmd)
            unsubscribeCurrentWork()
          }
        }
      }
  }

  type Fiat2Btc = Map[String, Double]
  final val SEPARATOR = " "
  final val PERCENT = "%"

  def trimmed(inputText: String): String = inputText.trim.take(144)

  def none: PartialFunction[Any, Unit] = { case _ => }

  implicit class IterableOfTuple2[T, V](underlying: Iterable[(T, V)] = Nil) {
    def secondItems: Iterable[V] = underlying.map { case (_, secondItem) =>
      secondItem
    }
    def firstItems: Iterable[T] = underlying.map { case (firstItem, _) =>
      firstItem
    }
  }

  implicit class ThrowableOps(error: Throwable) {
    def stackTraceAsString: String = {
      val stackTraceWriter = new java.io.StringWriter
      error printStackTrace new java.io.PrintWriter(stackTraceWriter)
      stackTraceWriter.toString
    }
  }

  def ratio(bigger: MilliSatoshi, lesser: MilliSatoshi): Double =
    scala.util
      .Try(bigger.toLong)
      .map(lesser.toLong * 10000d / _)
      .map(_.toLong / 100d)
      .getOrElse(0d)

  def mapKeys[K, V, K1](
      items: mutable.Map[K, V],
      mapper: K => K1,
      defVal: V
  ): mutable.Map[K1, V] =
    items.map { case (key, value) =>
      mapper(key) -> value
    } withDefaultValue defVal

  def memoize[In <: Object, Out <: Object](
      fun: In => Out
  ): LoadingCache[In, Out] = {
    val loader = new CacheLoader[In, Out] {
      override def load(key: In): Out = fun apply key
    }
    CacheBuilder.newBuilder
      .expireAfterAccess(7, TimeUnit.DAYS)
      .maximumSize(2000)
      .build[In, Out](loader)
  }

  def mkFakeLocalEdge(from: PublicKey, toPeer: PublicKey): GraphEdge = {
    // Augments a graph with local edge corresponding to our local channel
    // Parameters do not matter except that it must point to real peer

    val zeroCltvDelta = CltvExpiryDelta(0)
    val randomScid = ShortChannelId(
      Crypto.randomBytes(8).toLong(signed = false)
    )
    val fakeDesc = ChannelDesc(randomScid, from, to = toPeer)
    val fakeHop =
      ExtraHop(
        from,
        randomScid,
        MilliSatoshi(0L),
        0L,
        zeroCltvDelta
      )
    GraphEdge(updExt = RouteCalculation.toFakeUpdate(fakeHop), desc = fakeDesc)
  }

  // Defines whether updated feerate exceeds a given threshold
  def newFeerate(
      info1: FeeRatesInfo,
      spec: CommitmentSpec,
      threshold: Double
  ): Option[FeeratePerKw] = {
    val newFeerate = info1.onChainFeeConf.feeEstimator.getFeeratePerKw(
      info1.onChainFeeConf.feeTargets.commitmentBlockTarget
    )
    if (
      spec.commitTxFeerate
        .max(newFeerate)
        .toLong
        .toDouble / spec.commitTxFeerate
        .min(newFeerate)
        .toLong > threshold
    ) Some(newFeerate)
    else None
  }

  def randomKeyPair: KeyPair = {
    val pk: PrivateKey = randomKey()
    KeyPair(pk.publicKey.value, pk.value)
  }

  val dummyExtPrivKey: ExtendedPrivateKey = ExtendedPrivateKey(
    secretkeybytes = ByteVector32(Crypto.randomBytes(32)),
    chaincode = ByteVector32(Crypto.randomBytes(32)),
    depth = 0,
    path = KeyPath.Root,
    parent = 0L
  )

  def chaChaEncrypt(
      key: ByteVector32,
      nonce: ByteVector,
      data: ByteVector
  ): ByteVector = {
    val ciphertext =
      ChaCha20Poly1305.encrypt(key, nonce, data, ByteVector.empty)
    ciphertext.takeRight(16) ++ nonce ++ ciphertext.dropRight(
      16
    ) // mac (16b) + nonce (12b) + ciphertext (variable size)
  }

  def chaChaDecrypt(
      key: ByteVector32,
      data: ByteVector
  ): scala.util.Try[ByteVector] = scala.util.Try {
    ChaCha20Poly1305.decrypt(
      ciphertext = data.drop(16).drop(12) ++ data.take(16),
      key,
      nonce = data.drop(16).take(12),
      ByteVector.empty
    )
  }

  def prepareBip84Psbt(
      response: GenerateTxResponse,
      masterFingerprint: Long
  ): Psbt = {
    // We ONLY support BIP84 watching wallets so all inputs have witnesses
    val psbt1 = Psbt(response.tx)

    // Provide info about inputs
    val psbt2 = response.tx.txIn.foldLeft(psbt1) { case (psbt, txIn) =>
      val parentTransaction = response.data.transactions(txIn.outPoint.txid)
      val utxoPubKey = response.data.publicScriptMap(
        parentTransaction.txOut(txIn.outPoint.index.toInt).publicKeyScript
      )
      val derivationPath = Map[PublicKey, KeyPathWithMaster](
        utxoPubKey.publicKey ->
          KeyPathWithMaster(
            masterFingerprint,
            utxoPubKey.path
          )
      )
      psbt
        .updateWitnessInputTx(
          parentTransaction,
          txIn.outPoint.index.toInt,
          derivationPaths = derivationPath
        )
        .get
    }

    // Provide info about our change output
    response.tx.txOut.zipWithIndex.foldLeft(psbt2) {
      case (psbt, txOut ~ index) =>
        response.data.publicScriptChangeMap.get(txOut.publicKeyScript) map {
          changeKey =>
            val changeKeyPathWithMaster =
              KeyPathWithMaster(masterFingerprint, changeKey.path)
            val derivationPath =
              Map(changeKeyPathWithMaster -> changeKey.publicKey).map(_.swap)
            psbt
              .updateWitnessOutput(index, derivationPaths = derivationPath)
              .get
        } getOrElse psbt
    }
  }

  def extractBip84Tx(psbt: Psbt): scala.util.Try[Transaction] = {
    // We ONLY support BIP84 watching wallets so all inputs have witnesses
    psbt.extract() orElse psbt.inputs.zipWithIndex
      .foldLeft(psbt) { case (psbt1, input ~ index) =>
        val witness = (Script.witnessPay2wpkh _).tupled(input.partialSigs.head)
        psbt1.finalizeWitnessInput(index, witness).get
      }
      .extract()
  }

  object ~ {
    // Useful for matching nested Tuple2 with less noise
    def unapply[A, B](t2: (A, B) /* Got a tuple */ ) = Some(t2)
  }
}
