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
import fr.acinq.eclair.blockchain.{EclairWallet, TxAndFee}
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
    val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos).get
    val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(tx1)
    assert(fee == fee1)

    val state2 = state1.commitTransaction(tx1)
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
    val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos).get

    val Some(TransactionDelta(_, Some(fee), received, sent)) = state1.computeTransactionDelta(tx1)
    assert(fee == fee1)
    assert(sent - received - fee == btc2satoshi(0.5.btc))
  }

  test("use actual transaction weight to compute fees") {
    val state1 = addFunds(state, (state.accountKeys(0), 5000000.sat) :: (state.accountKeys(1), 6000000.sat) :: (state.accountKeys(2), 4000000.sat) :: Nil)

    {
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(5000000.sat, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos).get
      val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, feerate))
    }
    {
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(5000000.sat - dustLimit, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos).get
      val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, feerate))
    }
    {
      // with a huge fee rate that will force us to use an additional input when we complete our tx
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(3000000.sat, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate * 100, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos).get
      val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, feerate * 100))
    }
    {
      // with a tiny fee rate that will force us to use an additional input when we complete our tx
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(0.09), Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate / 10, dustLimit, TxIn.SEQUENCE_FINAL, state1.utxos).get
      val Some(TransactionDelta(_, Some(fee), _, _)) = state1.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
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
    val TxAndFee(tx, fee) = state3.spendAll(Script.write(pay2wpkh), state3.utxos, Nil, feerate, dustLimit, TxIn.SEQUENCE_FINAL).get
    val Some(TransactionDelta(_, Some(fee1), received, _)) = state3.computeTransactionDelta(tx)
    assert(received === 0.sat)
    assert(fee == fee1)
    assert(tx.txOut.map(_.amount).sum + fee == state3.balance.totalBalance)
  }

  test("RBF-bump reusing old utxos") {
    val state1 = addFunds(state, state.accountKeys(0), 2.btc)
    val state2 = addFunds(state1, state.accountKeys(1), 2.btc)

    assert(GetBalanceResponse(400000000.sat) == state2.balance)
    assert(state2.utxos.length == 2)

    val pay2pkh = Script.pay2pkh(ByteVector.fill(20)(1))
    val spendTx1 = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(3), pay2pkh) :: Nil, lockTime = 0)
    val TxAndFee(tx1, fee1) = state2.completeTransaction(spendTx1, feerate / 10, dustLimit, EclairWallet.OPT_IN_FULL_RBF, state2.utxos).get
    val state3 = state2.commitTransaction(tx1)

    assert(state3.balance.totalBalance == state2.balance.totalBalance - spendTx1.txOut.map(_.amount).sum - fee1)
    assert(state3.utxos.length == 1) // Only change output is left

    val TxAndFee(tx2, fee2) = state3.rbfBump(RBFBump(tx1, feerate, EclairWallet.OPT_IN_FULL_RBF), dustLimit).result.right.get
    assert(tx1.txIn.map(_.outPoint).toSet == tx2.txIn.map(_.outPoint).toSet) // Bumped tx spends the same utxos as original one
    assert(tx1.txOut.filterNot(state3.isMine).toSet == tx2.txOut.filterNot(state3.isMine).toSet) // Recipient gets the same amount
    assert(tx1.txOut.filter(state3.isMine).head.amount - tx2.txOut.filter(state3.isMine).head.amount == fee2 - fee1) // Fee is taken from change output
    assert(fee1 * 10 == fee2)

    val state4 = state3.commitTransaction(tx2)
    assert(state4.balance.totalBalance == state3.balance.totalBalance + tx2.txOut.filter(state3.isMine).head.amount) // We now have 2 unconfirmed change outputs
  }

  test("RBF-bump adding new utxos") {
    val state1 = addFunds(state, state.accountKeys(0), 1.btc)
    val state2 = addFunds(state1, state.accountKeys(1), 1.btc)
    val state3 = addFunds(state2, state.accountKeys(2), 1.btc)

    assert(GetBalanceResponse(300000000.sat) == state3.balance)
    assert(state3.utxos.length == 3)

    val pay2pkh = Script.pay2pkh(ByteVector.fill(20)(1))
    val spendTx1 = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1.9999), pay2pkh) :: Nil, lockTime = 0)
    val TxAndFee(tx1, fee1) = state3.completeTransaction(spendTx1, feerate / 10, dustLimit, EclairWallet.OPT_IN_FULL_RBF, state2.utxos).get
    val state4 = state3.commitTransaction(tx1)

    assert(state4.balance.totalBalance == state3.balance.totalBalance - spendTx1.txOut.map(_.amount).sum - fee1)
    assert(state4.utxos.length == 2) // Only change and unused outputs are left

    val TxAndFee(tx2, fee2) = state4.rbfBump(RBFBump(tx1, feerate, EclairWallet.OPT_IN_FULL_RBF), dustLimit).result.right.get
    assert(tx1.txIn.map(_.outPoint).toSet.subsetOf(tx2.txIn.map(_.outPoint).toSet)) // Bumped tx spends original outputs and adds another one
    assert(tx1.txOut.filterNot(state4.isMine).toSet == tx2.txOut.filterNot(state4.isMine).toSet) // Recipient gets the same amount
    assert(tx2.txOut.filter(state4.isMine).map(_.amount).sum == state3.balance.totalBalance - tx2.txOut.filterNot(state4.isMine).map(_.amount).sum - fee2) // Our change output is larger
    assert(tx1.txOut.filter(state4.isMine).map(_.amount).sum == state3.balance.totalBalance - tx1.txOut.filterNot(state4.isMine).map(_.amount).sum - fee1 - 1.btc) // Two competing txs
    assert(fee2 > fee1)
  }

  test("RBF-bump draining a wallet") {
    val state1 = addFunds(state, state.accountKeys(0), 1.btc)
    val state2 = addFunds(state1, state1.accountKeys(1), 2.btc)
    val state3 = addFunds(state2, state2.changeKeys(0), 3.btc)
    assert(state3.utxos.length == 3)
    assert(GetBalanceResponse(600000000L.sat) == state3.balance)

    val pay2wpkh = Script.pay2wpkh(ByteVector.fill(20)(1))
    val TxAndFee(tx1, fee) = state3.spendAll(Script.write(pay2wpkh), state3.utxos, Nil, feerate / 10, dustLimit, EclairWallet.OPT_IN_FULL_RBF).get
    val state4 = state3.commitTransaction(tx1)

    val TxAndFee(tx2, fee2) = state4.rbfBump(RBFBump(tx1, feerate, EclairWallet.OPT_IN_FULL_RBF), dustLimit).result.right.get
    assert(tx1.txOut.map(_.publicKeyScript) == tx2.txOut.map(_.publicKeyScript) && tx1.txOut.size == 1) // Both txs spend to the same address not belonging to us
    assert(tx2.txOut.map(_.amount).sum == state3.balance.totalBalance - fee2) // Bumped draining transaction has an increased fee
    assert(tx1.txIn.map(_.outPoint).toSet == tx2.txIn.map(_.outPoint).toSet) // Both txs spend same inputs
    assert(fee * 10 == fee2)
  }

  test("RBF-cancel") {
    val state1 = addFunds(state, state.accountKeys(0), 1.btc)
    val state2 = addFunds(state1, state1.accountKeys(1), 2.btc)
    val state3 = addFunds(state2, state2.changeKeys(0), 3.btc)

    val pay2wpkh = Script.pay2wpkh(ByteVector.fill(20)(1))
    val TxAndFee(tx1, fee) = state3.spendAll(Script.write(pay2wpkh), state3.utxos, Nil, feerate / 10, dustLimit, EclairWallet.OPT_IN_FULL_RBF).get
    val state4 = state3.commitTransaction(tx1)

    val rerouteScript = Script.write(Script.pay2wpkh(ByteVector.fill(20)(2)))
    val TxAndFee(tx2, fee2) = state4.rbfReroute(RBFReroute(tx1, feerate, rerouteScript, EclairWallet.OPT_IN_FULL_RBF), dustLimit).result.right.get
    assert(tx2.txOut.head.publicKeyScript == rerouteScript && tx2.txOut.size == 1) // Cancelling tx sends funds to a different destination
    assert(tx2.txOut.map(_.amount).sum == state3.balance.totalBalance - fee2) // Bumped draining transaction has an increased fee
    assert(tx1.txIn.map(_.outPoint).toSet == tx2.txIn.map(_.outPoint).toSet) // Both txs spend same inputs
    assert(fee * 10 == fee2)
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
    val TxAndFee(tx1, fee) = state3.spendAll(Script.write(pay2wpkh), usableUtxos, Nil, feerate, dustLimit, TxIn.SEQUENCE_FINAL).get
    val Some(TransactionDelta(_, Some(fee1), received, _)) = state3.computeTransactionDelta(tx1)
    assert(received === 0.sat)
    assert(tx1.txIn.size == usableUtxos.size)
    assert(fee == fee1)
    assert(tx1.txOut.map(_.amount).sum + fee == usableUtxos.map(_.item.value.sat).sum)
  }

  test("check that issue #1146 is fixed") {
    val state3 = addFunds(state, state.changeKeys(0), 0.5.btc)

    val pub1 = state.accountKeys(0).publicKey
    val pub2 = state.accountKeys(1).publicKey
    val redeemScript = Scripts.multiSig2of2(pub1, pub2)
    val pubkeyScript = Script.pay2wsh(redeemScript)
    val TxAndFee(tx, fee) = state3.spendAll(Script.write(pubkeyScript), state3.utxos, Nil, FeeratePerKw(750.sat), dustLimit, TxIn.SEQUENCE_FINAL).get
    val Some(TransactionDelta(_, Some(fee1), received, _)) = state3.computeTransactionDelta(tx)
    assert(received === 0.sat)
    assert(fee == fee1)
    assert(tx.txOut.map(_.amount).sum + fee == state3.balance.totalBalance)

    val tx1 = Transaction(version = 2, txIn = Nil, txOut = TxOut(tx.txOut.map(_.amount).sum, pubkeyScript) :: Nil, lockTime = 0)
    assert(Try(state3.completeTransaction(tx1, FeeratePerKw(750.sat), dustLimit, TxIn.SEQUENCE_FINAL, state3.utxos)).isSuccess)
  }

  test("can not send all when fee is too large") {
    val state3 = addFunds(state, state.changeKeys(0), 5000.sat)

    val pub1 = state.accountKeys(0).publicKey
    val pub2 = state.accountKeys(1).publicKey
    val redeemScript = Scripts.multiSig2of2(pub1, pub2)
    val pubkeyScript = Script.pay2wsh(redeemScript)
    assert(state3.spendAll(Script.write(pubkeyScript), state3.utxos, Nil, FeeratePerKw(10000.sat), dustLimit, TxIn.SEQUENCE_FINAL).isFailure)
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