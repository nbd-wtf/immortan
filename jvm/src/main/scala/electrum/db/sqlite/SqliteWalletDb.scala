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

  val overrideCodec: Codec[Map[ByteVector32, ByteVector32]] =
    Codec[Map[ByteVector32, ByteVector32]](
      (runtimeMap: Map[ByteVector32, ByteVector32]) =>
        listOfN(uint16, bytes32 :: bytes32).encode(runtimeMap.toList),
      (wire: BitVector) =>
        listOfN(uint16, bytes32 :: bytes32).decode(wire).map(_.map(_.toMap))
    )

  val statusCodec: Codec[Map[ByteVector32, String]] =
    Codec[Map[ByteVector32, String]](
      (runtimeMap: Map[ByteVector32, String]) =>
        listOfN(uint16, bytes32 :: cstring).encode(runtimeMap.toList),
      (wire: BitVector) =>
        listOfN(uint16, bytes32 :: cstring).decode(wire).map(_.map(_.toMap))
    )

  val transactionsCodec: Codec[Map[ByteVector32, Transaction]] =
    Codec[Map[ByteVector32, Transaction]](
      (runtimeMap: Map[ByteVector32, Transaction]) =>
        listOfN(uint16, bytes32 :: txCodec).encode(runtimeMap.toList),
      (wire: BitVector) =>
        listOfN(uint16, bytes32 :: txCodec).decode(wire).map(_.map(_.toMap))
    )

  val transactionHistoryItemCodec = {
    ("height" | int32) ::
      ("txHash" | bytes32)
  }.as[ElectrumClient.TransactionHistoryItem]

  val seqOfTransactionHistoryItemCodec =
    listOfN[TransactionHistoryItem](uint16, transactionHistoryItemCodec)

  val historyCodec: Codec[Map[ByteVector32, ElectrumWallet.TxHistoryItemList]] =
    Codec[Map[ByteVector32, ElectrumWallet.TxHistoryItemList]](
      (runtimeMap: Map[ByteVector32, ElectrumWallet.TxHistoryItemList]) =>
        listOfN(uint16, bytes32 :: seqOfTransactionHistoryItemCodec)
          .encode(runtimeMap.toList),
      (wire: BitVector) =>
        listOfN(uint16, bytes32 :: seqOfTransactionHistoryItemCodec)
          .decode(wire)
          .map(_.map(_.toMap))
    )

  val proofsCodec: Codec[Map[ByteVector32, GetMerkleResponse]] =
    Codec[Map[ByteVector32, GetMerkleResponse]](
      (runtimeMap: Map[ByteVector32, GetMerkleResponse]) =>
        listOfN(uint16, bytes32 :: proofCodec).encode(runtimeMap.toList),
      (wire: BitVector) =>
        listOfN(uint16, bytes32 :: proofCodec).decode(wire).map(_.map(_.toMap))
    )

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
