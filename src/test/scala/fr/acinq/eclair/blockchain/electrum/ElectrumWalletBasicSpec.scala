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

package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._
import fr.acinq.eclair.blockchain.electrum.ElectrumWalletBasicSpec._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.randomBytes32
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

import scala.util.{Failure, Random, Success, Try}

class ElectrumWalletBasicSpec extends AnyFunSuite {

  private val dustLimit = 546.sat
  private val feerate = FeeratePerKw(20000.sat)

  private val entropy: ByteVector = ByteVector.fill(32)(1)

  private val ewt = ElectrumWalletType.makeSigningType(EclairWallet.BIP84, generate(entropy), Block.RegtestGenesisBlock.hash)

  private val firstAccountKeys = (0 until 10).map(i => derivePublicKey(ewt.accountMaster, i)).toVector
  private val firstChangeKeys = (0 until 10).map(i => derivePublicKey(ewt.changeMaster, i)).toVector

  private val state = ElectrumData(ewt,
    Blockchain.fromCheckpoints(Block.RegtestGenesisBlock.hash, CheckPoint.loadFromChainHash(Block.RegtestGenesisBlock.hash)), firstAccountKeys, firstChangeKeys)
    .copy(status = (firstAccountKeys ++ firstChangeKeys).map(key => ewt.computeScriptHashFromPublicKey(key.publicKey) -> "").toMap)

  def addFunds(data: ElectrumData, key: ExtendedPublicKey, amount: Satoshi): ElectrumData = {
    val tx = Transaction(version = 1, txIn = Nil, txOut = TxOut(amount, ewt.computePublicKeyScript(key.publicKey)) :: Nil, lockTime = 0)
    val scriptHash = ewt.computeScriptHashFromPublicKey(key.publicKey)
    val scriptHashHistory = data.history.getOrElse(scriptHash, List.empty[ElectrumClient.TransactionHistoryItem])
    data.copy(
      history = data.history.updated(scriptHash, ElectrumClient.TransactionHistoryItem(100, tx.txid) :: scriptHashHistory),
      transactions = data.transactions + (tx.txid -> tx)
    )
  }

  def addFunds(data: ElectrumData, keyamount: (ExtendedPublicKey, Satoshi)): ElectrumData = {
    val tx = Transaction(version = 1, txIn = Nil, txOut = TxOut(keyamount._2, ewt.computePublicKeyScript(keyamount._1.publicKey)) :: Nil, lockTime = 0)
    val scriptHash = ewt.computeScriptHashFromPublicKey(keyamount._1.publicKey)
    val scriptHashHistory = data.history.getOrElse(scriptHash, List.empty[ElectrumClient.TransactionHistoryItem])
    data.copy(
      history = data.history.updated(scriptHash, ElectrumClient.TransactionHistoryItem(100, tx.txid) :: scriptHashHistory),
      transactions = data.transactions + (tx.txid -> tx)
    )
  }

  def addFunds(data: ElectrumData, keyamounts: Seq[(ExtendedPublicKey, Satoshi)]): ElectrumData = keyamounts.foldLeft(data)(addFunds)

  test("complete transactions (enough funds)") {
    val state1 = addFunds(state, state.accountKeys.head, 1.btc)
    val GetBalanceResponse(confirmed1) = state1.balance

    val pub = PrivateKey(ByteVector32(ByteVector.fill(32)(1))).publicKey
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5.btc, Script.pay2pkh(pub)) :: Nil, lockTime = 0)
    val Success(response1) = state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos)
    val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(response1.tx)
    assert(fee == response1.fee)

    val state2 = state1.commitTransaction(response1.tx)
    val GetBalanceResponse(confirmed4) = state2.balance
    assert(confirmed1 - confirmed4 >= btc2satoshi(0.5.btc))
  }

  test("complete transactions (insufficient funds)") {
    val state1 = addFunds(state, state.accountKeys.head, 5.btc)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(6.btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    intercept[RuntimeException] {
      state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos).get
    }
  }

  test("compute the effect of tx") {
    val state1 = addFunds(state, state.accountKeys.head, 1.btc)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5.btc, Script.pay2wsh(randomBytes32)) :: Nil, lockTime = 0)
    val Success(response1) = state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos)

    val Some(TransactionDelta(_, Some(fee), received, sent)) = state1.computeTransactionDelta(response1.tx)
    assert(sent - received - fee == btc2satoshi(0.5.btc))
    assert(fee == response1.fee)
  }

  test("use actual transaction weight to compute fees") {
    val state1 = addFunds(state, (state.accountKeys(0), 5000000.sat) :: (state.accountKeys(1), 6000000.sat) :: (state.accountKeys(2), 4000000.sat) :: Nil)

    {
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(5000000.sat, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val Success(response1) = state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos)
      val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(response1.tx)
      assert(fee == response1.fee)
      val actualFeeRate = Transactions.fee2rate(fee, response1.tx.weight())
      assert(isFeerateOk(actualFeeRate, feerate))
    }
    {
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(5000000.sat - dustLimit, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val Success(response1) = state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos)
      val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(response1.tx)
      assert(fee == response1.fee)
      val actualFeeRate = Transactions.fee2rate(fee, response1.tx.weight())
      assert(isFeerateOk(actualFeeRate, feerate))
    }
    {
      // with a huge fee rate that will force us to use an additional input when we complete our tx
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(3000000.sat, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val Success(response1) = state1.completeTransaction(tx, feerate * 100, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos)
      val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(response1.tx)
      assert(fee == response1.fee)
      val actualFeeRate = Transactions.fee2rate(fee, response1.tx.weight())
      assert(isFeerateOk(actualFeeRate, feerate * 100))
    }
    {
      // with a tiny fee rate that will force us to use an additional input when we complete our tx
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(0.09), Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val Success(response1) = state1.completeTransaction(tx, feerate / 10, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos)
      val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(response1.tx)
      assert(fee == response1.fee)
      val actualFeeRate = Transactions.fee2rate(fee, response1.tx.weight())
      assert(isFeerateOk(actualFeeRate, feerate / 10))
    }
  }

  test("spend all our balance") {
    val state1 = addFunds(state, state.accountKeys(0), 1.btc)
    val state2 = addFunds(state1, state1.accountKeys(1), 2.btc)
    val state3 = addFunds(state2, state2.changeKeys(0), 0.5.btc)
    assert(state3.utxos.length == 3)
    assert(GetBalanceResponse(350000000.sat) == state3.balance)

    val pay2wpkh = Script.pay2wpkh(ByteVector.fill(20)(1))
    val Success(response1) = state3.spendAll(Script.write(pay2wpkh), Map.empty, state3.utxos, Nil, feerate, dustLimit, TxIn.SEQUENCE_FINAL)
    val Some(TransactionDelta(_, Some(fee1), received, _)) = state3.computeTransactionDelta(response1.tx)
    assert(received === 0.sat)
    assert(response1.fee == fee1)
    assert(response1.tx.txOut.map(_.amount).sum + response1.fee == state3.balance.totalBalance)
  }

  test("RBF-bump reusing old utxos") {
    val state1 = addFunds(state, state.accountKeys(0), 2.btc)
    val state2 = addFunds(state1, state.accountKeys(1), 2.btc)

    assert(GetBalanceResponse(400000000.sat) == state2.balance)
    assert(state2.utxos.length == 2)

    val pay2pkh = Script.pay2pkh(ByteVector.fill(20)(1))
    val spendTx1 = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(3), pay2pkh) :: Nil, lockTime = 0)
    val Success(response1) = state2.completeTransaction(spendTx1, feerate / 10, dustLimit, EclairWallet.OPT_IN_FULL_RBF, state2.utxos)
    val changeScriptHash = ewt.computeScriptHashFromPublicKey(state2.publicScriptChangeMap(response1.tx.txOut.filter(state2.isMine).head.publicKeyScript).publicKey) // Change utxo updated
    val state3 = state2.commitTransaction(response1.tx).copy(status = state2.status.updated(changeScriptHash, "used-change-utxo"))

    assert(state3.balance.totalBalance == state2.balance.totalBalance - spendTx1.txOut.map(_.amount).sum - response1.fee)
    assert(state3.utxos.length == 1) // Only change output is left

    val response2 = state3.rbfBump(RBFBump(response1.tx, feerate, EclairWallet.OPT_IN_FULL_RBF), dustLimit).result.right.get
    assert(response1.tx.txIn.map(_.outPoint).toSet == response2.tx.txIn.map(_.outPoint).toSet) // Bumped tx spends the same utxos as original one
    assert(response1.tx.txOut.filterNot(state3.isMine).toSet == response2.tx.txOut.filterNot(state3.isMine).toSet) // Recipient gets the same amount
    assert(response1.tx.txOut.filter(state3.isMine).head.amount - response2.tx.txOut.filter(state3.isMine).head.amount == response2.fee - response1.fee) // Fee is taken from change output
    assert(response1.fee * 10 == response2.fee)

    val state4 = state3.commitTransaction(response2.tx)
    assert(state4.withOverridingTxids.balance.totalBalance == state3.balance.totalBalance - response2.fee + response1.fee) // But former unconfirmed change utxo gets overridden and thrown out
    assert(state4.withOverridingTxids.overriddenPendingTxids == Map(response1.tx.txid -> response2.tx.txid))
  }

  test("RBF-bump adding new utxos") {
    val state1 = addFunds(state, state.accountKeys(0), 1.btc)
    val state2 = addFunds(state1, state.accountKeys(1), 1.btc)
    val state3 = addFunds(state2, state.accountKeys(2), 1.btc)

    assert(GetBalanceResponse(300000000.sat) == state3.balance)
    assert(state3.utxos.length == 3)

    val pay2pkh = Script.pay2pkh(ByteVector.fill(20)(1))
    val spendTx1 = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1.9999), pay2pkh) :: Nil, lockTime = 0)
    val Success(response1) = state3.completeTransaction(spendTx1, feerate / 10, dustLimit, EclairWallet.OPT_IN_FULL_RBF, state2.utxos)
    val changeScriptHash = ewt.computeScriptHashFromPublicKey(state3.publicScriptChangeMap(response1.tx.txOut.filter(state3.isMine).head.publicKeyScript).publicKey) // Change utxo updated
    val state4 = state3.commitTransaction(response1.tx).copy(status = state3.status.updated(changeScriptHash, "used-change-utxo"))

    assert(state4.balance.totalBalance == state3.balance.totalBalance - spendTx1.txOut.map(_.amount).sum - response1.fee)
    assert(state4.utxos.length == 2) // Only change and unused outputs are left

    val response2 = state4.rbfBump(RBFBump(response1.tx, feerate, EclairWallet.OPT_IN_FULL_RBF), dustLimit).result.right.get
    assert(response1.tx.txIn.map(_.outPoint).toSet.subsetOf(response2.tx.txIn.map(_.outPoint).toSet)) // Bumped tx spends original outputs and adds another one
    assert(response1.tx.txOut.filterNot(state4.isMine).toSet == response2.tx.txOut.filterNot(state4.isMine).toSet) // Recipient gets the same amount
    assert(response2.tx.txOut.filter(state4.isMine).map(_.amount).sum == state3.balance.totalBalance - response2.tx.txOut.filterNot(state4.isMine).map(_.amount).sum - response2.fee) // Our change output is larger
    assert(response2.fee > response1.fee)

    val state5 = state4.commitTransaction(response2.tx)
    assert(state5.withOverridingTxids.balance.totalBalance == state4.balance.totalBalance - response2.fee + response1.fee) //Former unconfirmed change utxo gets overridden and thrown out
    assert(state5.withOverridingTxids.overriddenPendingTxids == Map(response1.tx.txid -> response2.tx.txid))
  }

  test("RBF-bump draining a wallet") {
    val state1 = addFunds(state, state.accountKeys(0), 1.btc)
    val state2 = addFunds(state1, state1.accountKeys(1), 2.btc)
    val state3 = addFunds(state2, state2.changeKeys(0), 3.btc)
    assert(state3.utxos.length == 3)
    assert(GetBalanceResponse(600000000L.sat) == state3.balance)

    val pay2wpkh = Script.pay2wpkh(ByteVector.fill(20)(1))
    val Success(response1) = state3.spendAll(Script.write(pay2wpkh), Map.empty, state3.utxos, Nil, feerate / 10, dustLimit, EclairWallet.OPT_IN_FULL_RBF)
    val state4 = state3.commitTransaction(response1.tx) // No change utxo

    val response2 = state4.rbfBump(RBFBump(response1.tx, feerate, EclairWallet.OPT_IN_FULL_RBF), dustLimit).result.right.get
    assert(response1.tx.txOut.map(_.publicKeyScript) == response2.tx.txOut.map(_.publicKeyScript) && response1.tx.txOut.size == 1) // Both txs spend to the same address not belonging to us
    assert(response2.tx.txOut.map(_.amount).sum == state3.balance.totalBalance - response2.fee) // Bumped draining transaction has an increased fee
    assert(response1.tx.txIn.map(_.outPoint).toSet == response2.tx.txIn.map(_.outPoint).toSet) // Both txs spend same inputs
    assert(response1.fee * 10 == response2.fee)

    val state5 = state4.commitTransaction(response2.tx)
    assert(state5.withOverridingTxids.balance.totalBalance == state5.balance.totalBalance)
    assert(state5.withOverridingTxids.balance.totalBalance == 0L.sat)
  }

  test("RBF-cancel of spend all") {
    val state1 = addFunds(state, state.accountKeys(0), 1.btc)
    val state2 = addFunds(state1, state1.accountKeys(1), 2.btc)
    val state3 = addFunds(state2, state2.changeKeys(0), 3.btc)

    val pay2wpkh = Script.pay2wpkh(ByteVector.fill(20)(1))
    val Success(response1) = state3.spendAll(Script.write(pay2wpkh), Map.empty, state3.utxos, Nil, feerate / 10, dustLimit, EclairWallet.OPT_IN_FULL_RBF)
    val state4 = state3.commitTransaction(response1.tx)

    val rerouteScript = state3.publicScriptChangeMap.head._1
    val response2 = state4.rbfReroute(RBFReroute(response1.tx, feerate, rerouteScript, EclairWallet.OPT_IN_FULL_RBF), dustLimit).result.right.get
    assert(response2.tx.txOut.head.publicKeyScript == rerouteScript && response2.tx.txOut.size == 1) // Cancelling tx sends funds to a different destination
    assert(response2.tx.txOut.map(_.amount).sum == state3.balance.totalBalance - response2.fee) // Bumped draining transaction has an increased fee
    assert(response1.tx.txIn.map(_.outPoint).toSet == response2.tx.txIn.map(_.outPoint).toSet) // Both txs spend same inputs
    assert(response1.fee * 10 == response2.fee)

    val state5 = state4.commitTransaction(response2.tx)
    assert(state5.withOverridingTxids.balance.totalBalance == state5.balance.totalBalance)
    assert(state5.withOverridingTxids.balance.totalBalance == state3.balance.totalBalance - response2.fee)
  }

  test("RBF-cancel of spend with change") {
    val state1 = addFunds(state, state.accountKeys(0), 2.btc)
    val state2 = addFunds(state1, state.accountKeys(1), 2.btc)

    assert(GetBalanceResponse(400000000.sat) == state2.balance)
    assert(state2.utxos.length == 2)

    val pay2pkh = Script.pay2pkh(ByteVector.fill(20)(1))
    val spendTx1 = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(3), pay2pkh) :: Nil, lockTime = 0)
    val Success(response1) = state2.completeTransaction(spendTx1, feerate / 10, dustLimit, EclairWallet.OPT_IN_FULL_RBF, state2.utxos)
    val changeScriptHash = ewt.computeScriptHashFromPublicKey(state2.publicScriptChangeMap(response1.tx.txOut.filter(state2.isMine).head.publicKeyScript).publicKey) // Change utxo updated
    val state3 = state2.commitTransaction(response1.tx).copy(status = state2.status.updated(changeScriptHash, "used-change-utxo-1"))

    assert(state3.balance.totalBalance == state2.balance.totalBalance - spendTx1.txOut.map(_.amount).sum - response1.fee)
    assert(state3.utxos.length == 1) // Only change output is left

    val rerouteKey = state3.firstUnusedChangeKey.get
    val rerouteScript = state3.publicScriptChangeMap.find(_._2 == rerouteKey).get._1
    val response2 = state3.rbfReroute(RBFReroute(response1.tx, feerate, rerouteScript, EclairWallet.OPT_IN_FULL_RBF), dustLimit).result.right.get
    assert(response2.tx.txOut.head.publicKeyScript == rerouteScript && response2.tx.txOut.size == 1) // Cancelling tx sends funds to our change address
    assert(response2.tx.txOut.head.amount == state2.balance.totalBalance - response2.fee) // Bumped draining transaction has an increased fee
    assert(response1.tx.txIn.map(_.outPoint).toSet == response2.tx.txIn.map(_.outPoint).toSet) // Both txs spend same inputs

    val changeScriptHash1 = ewt.computeScriptHashFromPublicKey(rerouteKey.publicKey) // New change utxo updated
    val state4 = state3.commitTransaction(response2.tx).copy(status = state3.status.updated(changeScriptHash1, "used-change-utxo-2"))
    assert(state4.utxos.length == 2) // Two competing change outputs
    assert(state4.withOverridingTxids.utxos.length == 1) // But one output is overridden
    assert(state4.withOverridingTxids.overriddenPendingTxids == Map(response1.tx.txid -> response2.tx.txid))
    assert(state4.withOverridingTxids.balance.totalBalance == state2.balance.totalBalance - response2.fee)
  }

  test("CPFP") {
    val state1 = addFunds(state, state.accountKeys(0), 1.btc)
    val state2 = addFunds(state1, state1.accountKeys(1), 2.btc)
    val key = state2.changeKeys(0)

    val txOut1 = TxOut(0.25.btc, ewt.computePublicKeyScript(key.publicKey))
    val txOut2 = TxOut(0.25.btc, ewt.computePublicKeyScript(key.publicKey))

    val tx0 = Transaction(version = 1, txIn = Nil, txOut = txOut1 :: txOut2 :: Nil, lockTime = 0)
    val scriptHash = ewt.computeScriptHashFromPublicKey(key.publicKey)
    val scriptHashHistory = state2.history.getOrElse(scriptHash, List.empty[ElectrumClient.TransactionHistoryItem])
    val state3 = state2.copy(
      history = state2.history.updated(scriptHash, ElectrumClient.TransactionHistoryItem(100, tx0.txid) :: scriptHashHistory),
      transactions = state2.transactions + (tx0.txid -> tx0)
    )

    assert(state3.utxos.length == 4)
    assert(GetBalanceResponse(350000000.sat) == state3.balance)

    val pay2wpkh = Script.pay2wpkh(ByteVector.fill(20)(1))
    val fromOutPoints = tx0.txOut.zipWithIndex.map { case (_, idx) => OutPoint(tx0.hash, idx) }
    val usableUtxos = state3.utxos.filter(fromOutPoints contains _.item.outPoint)
    val Success(response1) = state3.spendAll(Script.write(pay2wpkh), Map.empty, usableUtxos, Nil, feerate, dustLimit, TxIn.SEQUENCE_FINAL)
    val Some(TransactionDelta(_, Some(fee1), received, _)) = state3.computeTransactionDelta(response1.tx)
    assert(received === 0.sat)
    assert(response1.tx.txIn.size == usableUtxos.size)
    assert(response1.fee == fee1)
    assert(response1.tx.txOut.map(_.amount).sum + response1.fee == usableUtxos.map(_.item.value.sat).sum)
  }

  test("check that issue #1146 is fixed") {
    val state3 = addFunds(state, state.changeKeys(0), 0.5.btc)

    val pub1 = state.accountKeys(0).publicKey
    val pub2 = state.accountKeys(1).publicKey
    val redeemScript = Scripts.multiSig2of2(pub1, pub2)
    val pubkeyScript = Script.pay2wsh(redeemScript)
    val Success(response1) = state3.spendAll(Script.write(pubkeyScript), Map.empty, state3.utxos, Nil, FeeratePerKw(750.sat), dustLimit, TxIn.SEQUENCE_FINAL)
    val Some(TransactionDelta(_, Some(fee1), received, _)) = state3.computeTransactionDelta(response1.tx)
    assert(received === 0.sat)
    assert(response1.fee == fee1)
    assert(response1.tx.txOut.map(_.amount).sum + response1.fee == state3.balance.totalBalance)

    val tx1 = Transaction(version = 2, txIn = Nil, txOut = TxOut(response1.tx.txOut.map(_.amount).sum, pubkeyScript) :: Nil, lockTime = 0)
    assert(Try(state3.completeTransaction(tx1, FeeratePerKw(750.sat), dustLimit, TxIn.SEQUENCE_FINAL, state3.utxos)).isSuccess)
  }

  test("can not send all when fee is too large") {
    val state3 = addFunds(state, state.changeKeys(0), 5000.sat)

    val pub1 = state.accountKeys(0).publicKey
    val pub2 = state.accountKeys(1).publicKey
    val redeemScript = Scripts.multiSig2of2(pub1, pub2)
    val pubkeyScript = Script.pay2wsh(redeemScript)
    assert(state3.spendAll(Script.write(pubkeyScript), Map.empty, state3.utxos, Nil, FeeratePerKw(10000.sat), dustLimit, TxIn.SEQUENCE_FINAL).isFailure)
  }

  test("fuzzy test") {
    val random = new Random()
    (0 to 10) foreach { _ =>
      val funds = for (_ <- 0 until random.nextInt(10)) yield {
        val index = random.nextInt(state.accountKeys.length)
        val amount = dustLimit + random.nextInt(10000000).sat
        (state.accountKeys(index), amount)
      }
      val state1 = addFunds(state, funds)
      (0 until 30) foreach { _ =>
        val amount = dustLimit + random.nextInt(10000000).sat
        val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
        state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos) match {
          case Success(txAndFee) => txAndFee.tx.txOut.foreach(o => require(o.amount >= dustLimit, "output is below dust limit"))
          case Failure(cause) if cause.getMessage != null && cause.getMessage.contains("insufficient funds") => ()
          case _ => // Do nothing
        }
      }
    }
  }
}

object ElectrumWalletBasicSpec {
  def isFeerateOk(actualFeeRate: FeeratePerKw, targetFeeRate: FeeratePerKw): Boolean =
    Math.abs(actualFeeRate.toLong - targetFeeRate.toLong) < 0.1 * (actualFeeRate + targetFeeRate).toLong
}