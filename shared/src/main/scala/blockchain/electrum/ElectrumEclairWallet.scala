package immortan.blockchain.electrum

import scoin.{
  ByteVector32,
  OP_PUSHDATA,
  OP_RETURN,
  OutPoint,
  Satoshi,
  Script,
  Transaction,
  TxIn,
  TxOut
}
import immortan.blockchain.EclairWallet
import immortan.blockchain.EclairWallet._
import immortan.blockchain.electrum.ElectrumClient.BroadcastTransaction
import immortan.blockchain.electrum.ElectrumWallet._
import immortan.blockchain.electrum.db.CompleteChainWalletInfo
import immortan.blockchain.fee.FeeratePerKw
import scodec.bits.ByteVector

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

case class ElectrumEclairWallet(
    wallet: ElectrumWallet,
    ewt: ElectrumWalletType,
    info: CompleteChainWalletInfo
) extends EclairWallet {
  private def emptyUtxo(pubKeyScript: ByteVector): TxOut =
    TxOut(Satoshi(0L), pubKeyScript)

  private def isInChain(
      error: immortan.blockchain.bitcoind.rpc.Error
  ): Boolean = error.message.toLowerCase.contains("already in block chain")

  override def getData: Future[GetDataResponse] = wallet.getData()

  override def getReceiveAddresses: Future[GetCurrentReceiveAddressesResponse] =
    wallet.getCurrentReceiveAddresses()

  override def makeFundingTx(
      pubkeyScript: ByteVector,
      amount: Satoshi,
      feeRatePerKw: FeeratePerKw
  ): Future[GenerateTxResponse] = {
    getData.flatMap { response =>
      if (response.data.balance == amount)
        wallet
          .sendAll(
            pubkeyScript,
            pubKeyScriptToAmount = Map.empty,
            feeRatePerKw,
            sequenceFlag = TxIn.SEQUENCE_FINAL,
            fromOutpoints = Set.empty
          )
      else
        wallet.generateTxResponse(
          pubKeyScriptToAmount = Map(pubkeyScript -> amount),
          feeRatePerKw,
          sequenceFlag = TxIn.SEQUENCE_FINAL
        )
    }
  }

  override def broadcast(tx: Transaction): Future[Boolean] = {
    wallet.broadcastTransaction(tx) flatMap {
      case ElectrumClient.BroadcastTransactionResponse(_, None) => Future(true)
      case res: ElectrumClient.BroadcastTransactionResponse
          if res.error.exists(isInChain) =>
        Future(true)
      case res: ElectrumClient.BroadcastTransactionResponse
          if res.error.isDefined =>
        Future(false)
      case _ => Future(false)
    }
  }

  override def sendPreimageBroadcast(
      preimages: Set[ByteVector32],
      pubKeyScript: ByteVector,
      feeRatePerKw: FeeratePerKw
  ): Future[GenerateTxResponse] = {
    val preimageTxOuts = preimages.toList
      .map(_.bytes)
      .map(OP_PUSHDATA.apply)
      .grouped(2)
      .map(OP_RETURN :: _)
      .map(Script.write)
      .map(emptyUtxo)
      .toList
    wallet.sendAll(
      pubKeyScript,
      pubKeyScriptToAmount = Map.empty,
      feeRatePerKw,
      OPT_IN_FULL_RBF,
      fromOutpoints = Set.empty,
      preimageTxOuts
    )
  }

  override def makeBatchTx(
      scriptToAmount: Map[ByteVector, Satoshi],
      feeRatePerKw: FeeratePerKw
  ): Future[GenerateTxResponse] = {
    wallet
      .completeTransaction(scriptToAmount, feeRatePerKw, OPT_IN_FULL_RBF)
  }

  override def makeTx(
      pubKeyScript: ByteVector,
      amount: Satoshi,
      prevScriptToAmount: Map[ByteVector, Satoshi],
      feeRatePerKw: FeeratePerKw
  ): Future[GenerateTxResponse] = {
    getData
      .map(_.data.balance == prevScriptToAmount.values.sum + amount)
      .flatMap {
        case false =>
          makeBatchTx(
            prevScriptToAmount.updated(pubKeyScript, amount),
            feeRatePerKw
          )
        case true =>
          wallet.sendAll(
            pubKeyScript,
            prevScriptToAmount,
            feeRatePerKw,
            OPT_IN_FULL_RBF,
            fromOutpoints = Set.empty
          )
      }
  }

  override def makeCPFP(
      fromOutpoints: Set[OutPoint],
      pubKeyScript: ByteVector,
      feeRatePerKw: FeeratePerKw
  ): Future[GenerateTxResponse] = {
    wallet.sendAll(
      pubKeyScript,
      pubKeyScriptToAmount = Map.empty,
      feeRatePerKw,
      OPT_IN_FULL_RBF,
      fromOutpoints
    )
  }

  override def makeRBFBump(
      tx: Transaction,
      feeRatePerKw: FeeratePerKw
  ): Future[RBFResponse] = {
    wallet.rbfBump(RBFBump(tx, feeRatePerKw, OPT_IN_FULL_RBF))
  }

  override def makeRBFReroute(
      tx: Transaction,
      feeRatePerKw: FeeratePerKw,
      pubKeyScript: ByteVector
  ): Future[RBFResponse] = {
    wallet.rbfReroute(
      RBFReroute(tx, feeRatePerKw, pubKeyScript, OPT_IN_FULL_RBF)
    )
  }

  override def provideExcludedOutpoints(
      excludedOutPoints: List[OutPoint] = Nil
  ): Unit = wallet.send(ProvideExcludedOutPoints(excludedOutPoints))

  override def doubleSpent(tx: Transaction): Future[IsDoubleSpentResponse] =
    wallet.isDoubleSpent(tx)

  override def hasFingerprint: Boolean = info.core.masterFingerprint.nonEmpty

  override def isBuiltIn: Boolean = isSigning && info.core.walletType == BIP84

  override def isSigning: Boolean = ewt.secrets.nonEmpty
}
