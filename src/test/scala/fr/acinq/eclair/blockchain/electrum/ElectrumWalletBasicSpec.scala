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
import immortan.sqlite.{DataTable, ElectrumHeadersTable}
import immortan.utils.SQLiteUtils
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
    val GetBalanceResponse(confirmed1, unconfirmed1) = state1.balance

    val pub = PrivateKey(ByteVector32(ByteVector.fill(32)(1))).publicKey
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5.btc, Script.pay2pkh(pub)) :: Nil, lockTime = 0)
    val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate, dustLimit, allowSpendUnconfirmed = false, TxIn.SEQUENCE_FINAL)
    val Some((_, _, Some(fee))) = state1.computeTransactionDelta(tx1)
    assert(fee == fee1)

    val state2 = state1.commitTransaction(tx1)
    val GetBalanceResponse(confirmed4, unconfirmed4) = state2.balance
    assert(confirmed4 == confirmed1)
    assert(unconfirmed1 - unconfirmed4 >= btc2satoshi(0.5.btc))
  }

  test("complete transactions (insufficient funds)") {
    val state1 = addFunds(state, state.accountKeys.head, 5.btc)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(6.btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    intercept[RuntimeException] {
      state1.completeTransaction(tx, feerate, dustLimit, allowSpendUnconfirmed = false, TxIn.SEQUENCE_FINAL)
    }
  }

  test("compute the effect of tx") {
    val state1 = addFunds(state, state.accountKeys.head, 1.btc)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5.btc, Script.pay2wsh(randomBytes32)) :: Nil, lockTime = 0)
    val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate, dustLimit, allowSpendUnconfirmed = false, TxIn.SEQUENCE_FINAL)

    val Some((received, sent, Some(fee))) = state1.computeTransactionDelta(tx1)
    assert(fee == fee1)
    assert(sent - received - fee == btc2satoshi(0.5.btc))
  }

  test("use actual transaction weight to compute fees") {
    val state1 = addFunds(state, (state.accountKeys(0), 5000000.sat) :: (state.accountKeys(1), 6000000.sat) :: (state.accountKeys(2), 4000000.sat) :: Nil)

    {
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(5000000.sat, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate, dustLimit, allowSpendUnconfirmed = true, TxIn.SEQUENCE_FINAL)
      val Some((_, _, Some(fee))) = state1.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, feerate))
    }
    {
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(5000000.sat - dustLimit, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate, dustLimit, allowSpendUnconfirmed = true, TxIn.SEQUENCE_FINAL)
      val Some((_, _, Some(fee))) = state1.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, feerate))
    }
    {
      // with a huge fee rate that will force us to use an additional input when we complete our tx
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(3000000.sat, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate * 100, dustLimit, allowSpendUnconfirmed = true, TxIn.SEQUENCE_FINAL)
      val Some((_, _, Some(fee))) = state1.computeTransactionDelta(tx1)
      assert(fee == fee1)
      val actualFeeRate = Transactions.fee2rate(fee, tx1.weight())
      assert(isFeerateOk(actualFeeRate, feerate * 100))
    }
    {
      // with a tiny fee rate that will force us to use an additional input when we complete our tx
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(0.09), Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
      val TxAndFee(tx1, fee1) = state1.completeTransaction(tx, feerate / 10, dustLimit, allowSpendUnconfirmed = true, TxIn.SEQUENCE_FINAL)
      val Some((_, _, Some(fee))) = state1.computeTransactionDelta(tx1)
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
    assert(GetBalanceResponse(350000000.sat, 0.sat) == state3.balance)

    val pay2wpkh = Script.pay2wpkh(ByteVector.fill(20)(1))
    val TxAndFee(tx, fee) = state3.spendAll(Script.write(pay2wpkh), Nil, feerate, dustLimit, TxIn.SEQUENCE_FINAL)
    val Some((received, _, Some(fee1))) = state3.computeTransactionDelta(tx)
    assert(received === 0.sat)
    assert(fee == fee1)
    assert(tx.txOut.map(_.amount).sum + fee == state3.balance.confirmed + state3.balance.unconfirmed)
  }

  test("check that issue #1146 is fixed") {
    val state3 = addFunds(state, state.changeKeys(0), 0.5.btc)

    val pub1 = state.accountKeys(0).publicKey
    val pub2 = state.accountKeys(1).publicKey
    val redeemScript = Scripts.multiSig2of2(pub1, pub2)
    val pubkeyScript = Script.pay2wsh(redeemScript)
    val TxAndFee(tx, fee) = state3.spendAll(Script.write(pubkeyScript), Nil, FeeratePerKw(750.sat), dustLimit, TxIn.SEQUENCE_FINAL)
    val Some((received, _, Some(fee1))) = state3.computeTransactionDelta(tx)
    assert(received === 0.sat)
    assert(fee == fee1)
    assert(tx.txOut.map(_.amount).sum + fee == state3.balance.confirmed + state3.balance.unconfirmed)

    val tx1 = Transaction(version = 2, txIn = Nil, txOut = TxOut(tx.txOut.map(_.amount).sum, pubkeyScript) :: Nil, lockTime = 0)
    assert(Try(state3.completeTransaction(tx1, FeeratePerKw(750.sat), dustLimit, allowSpendUnconfirmed = true, TxIn.SEQUENCE_FINAL)).isSuccess)
  }

  test("can not send all when fee is too large") {
    val state3 = addFunds(state, state.changeKeys(0), 5000.sat)

    val pub1 = state.accountKeys(0).publicKey
    val pub2 = state.accountKeys(1).publicKey
    val redeemScript = Scripts.multiSig2of2(pub1, pub2)
    val pubkeyScript = Script.pay2wsh(redeemScript)
    assert(Try(state3.spendAll(Script.write(pubkeyScript), Nil, FeeratePerKw(10000.sat), dustLimit, TxIn.SEQUENCE_FINAL)).isFailure)
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
        Try(state1.completeTransaction(tx, feerate, dustLimit, allowSpendUnconfirmed = true, TxIn.SEQUENCE_FINAL)) match {
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