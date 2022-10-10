package immortan.electrum.db.sqlite

import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs._
import scoin._
import scoin.ln.CommonCodecs._

import immortan.channel.Codecs._
import immortan.electrum.ElectrumClient.{
  GetMerkleResponse,
  TransactionHistoryItem
}
import immortan.electrum.{ElectrumClient, ElectrumWallet, PersistentData}
import immortan.electrum.ElectrumWallet.TxHistoryItemList

object SqliteWalletDb {
  private val anyOpt = Option.empty[Any]

  val ignoreElectrumClientCodec: Codec[Option[ElectrumClient]] = {
    ignore(0).xmapc(_ => None: Option[ElectrumClient])(_ => ())
  }.as[Option[ElectrumClient]]

  val proofCodec = {
    ("source" | ignoreElectrumClientCodec) ::
      ("txid" | bytes32) ::
      ("merkle" | listOfN(uint16, bytes32)) ::
      ("blockHeight" | uint24) ::
      ("pos" | uint24) ::
      ("contextOpt" | provide(anyOpt))
  }.as[GetMerkleResponse]

  val overrideCodec: Codec[Map[ByteVector32, ByteVector32]] = {
    case class DoubleBytes32(k: ByteVector32, v: ByteVector32)
    val doubleBytes32 = (bytes32 :: bytes32).as[DoubleBytes32]
    val subCodec = listOfN(uint16, doubleBytes32)

    Codec[Map[ByteVector32, ByteVector32]](
      (values: Map[ByteVector32, ByteVector32]) =>
        subCodec.encode(values.map((k, v) => DoubleBytes32(k, v)).toList),
      (bits: BitVector) =>
        subCodec
          .decode(bits)
          .map(_.map(_.map { case DoubleBytes32(k, v) => (k, v) }.toMap))
    )
  }

  val statusCodec: Codec[Map[ByteVector32, String]] = {
    case class Bytes32String(k: ByteVector32, v: String)
    val bytes32string = (bytes32 :: cstring).as[Bytes32String]
    val subCodec = listOfN(uint16, bytes32string)

    Codec[Map[ByteVector32, String]](
      (values: Map[ByteVector32, String]) =>
        subCodec.encode(values.map((k, v) => Bytes32String(k, v)).toList),
      (bits: BitVector) =>
        subCodec
          .decode(bits)
          .map(_.map(_.map { case Bytes32String(k, v) => (k, v) }.toMap))
    )
  }

  val transactionsCodec: Codec[Map[ByteVector32, Transaction]] = {
    case class Bytes32Transaction(k: ByteVector32, v: Transaction)
    val bytes32transaction = (bytes32 :: txCodec).as[Bytes32Transaction]
    val subCodec = listOfN(uint16, bytes32transaction)

    Codec[Map[ByteVector32, Transaction]](
      (values: Map[ByteVector32, Transaction]) =>
        subCodec.encode(values.map((k, v) => Bytes32Transaction(k, v)).toList),
      (bits: BitVector) =>
        subCodec
          .decode(bits)
          .map(_.map(_.map { case Bytes32Transaction(k, v) => (k, v) }.toMap))
    )
  }

  val transactionHistoryItemCodec = {
    ("height" | int32) ::
      ("txHash" | bytes32)
  }.as[ElectrumClient.TransactionHistoryItem]

  val seqOfTransactionHistoryItemCodec =
    listOfN[TransactionHistoryItem](uint16, transactionHistoryItemCodec)

  val historyCodec: Codec[Map[ByteVector32, TxHistoryItemList]] = {
    case class Bytes32TxHistoryItemList(k: ByteVector32, v: TxHistoryItemList)
    val bytes32txHistoryItemList =
      (bytes32 :: seqOfTransactionHistoryItemCodec).as[Bytes32TxHistoryItemList]
    val subCodec = listOfN(uint16, bytes32txHistoryItemList)

    Codec[Map[ByteVector32, TxHistoryItemList]](
      (values: Map[ByteVector32, TxHistoryItemList]) =>
        subCodec.encode(
          values.map((k, v) => Bytes32TxHistoryItemList(k, v)).toList
        ),
      (bits: BitVector) =>
        subCodec
          .decode(bits)
          .map(_.map(_.map { case Bytes32TxHistoryItemList(k, v) =>
            (k, v)
          }.toMap))
    )
  }

  val proofsCodec: Codec[Map[ByteVector32, GetMerkleResponse]] = {
    case class Bytes32Merkle(k: ByteVector32, v: GetMerkleResponse)
    val bytes32merkle = (bytes32 :: proofCodec).as[Bytes32Merkle]
    val subCodec = listOfN(uint16, bytes32merkle)

    Codec[Map[ByteVector32, GetMerkleResponse]](
      (values: Map[ByteVector32, GetMerkleResponse]) =>
        subCodec.encode(values.map((k, v) => Bytes32Merkle(k, v)).toList),
      (bits: BitVector) =>
        subCodec
          .decode(bits)
          .map(_.map(_.map { case Bytes32Merkle(k, v) => (k, v) }.toMap))
    )
  }

  val persistentDataCodec: Codec[PersistentData] = {
    ("accountKeysCount" | int32) ::
      ("changeKeysCount" | int32) ::
      ("status" | statusCodec) ::
      ("transactions" | transactionsCodec) ::
      ("overriddenPendingTxids" | overrideCodec) ::
      ("history" | historyCodec) ::
      ("proofs" | proofsCodec) ::
      ("pendingTransactions" | listOfN(uint16, txCodec)) ::
      ("excludedOutpoints" | listOfN(uint16, outPointCodec))
  }.as[PersistentData]
}
