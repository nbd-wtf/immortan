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

package fr.acinq.eclair.blockchain.electrum.db.sqlite

import fr.acinq.bitcoin.{Block, BlockHeader, OutPoint, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.GetMerkleResponse
import fr.acinq.eclair.blockchain.electrum.{ElectrumClient, PersistentData}
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb.persistentDataCodec
import fr.acinq.eclair.blockchain.electrum.db.{CompleteChainWalletInfo, SigningWallet}
import fr.acinq.eclair.{randomBytes, randomBytes32, randomKey}
import immortan.sqlite._
import immortan.utils.SQLiteUtils
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random


class SqliteWalletDbSpec extends AnyFunSuite {
  val random = new Random()

  def makeChildHeader(header: BlockHeader): BlockHeader = header.copy(hashPreviousBlock = header.hash, nonce = random.nextLong() & 0xffffffffL)

  def makeHeaders(n: Int, acc: Seq[BlockHeader] = Seq(Block.RegtestGenesisBlock.header)): Seq[BlockHeader] = {
    if (acc.size == n) acc else makeHeaders(n, acc :+ makeChildHeader(acc.last))
  }

  def randomTransaction = Transaction(version = 2,
    txIn = TxIn(OutPoint(randomBytes32, random.nextInt(100)), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
    txOut = TxOut(Satoshi(random.nextInt(10000000)), randomBytes(20)) :: Nil,
    0L
  )

  def randomHeight = if (random.nextBoolean()) random.nextInt(500000) else -1

  def randomHistoryItem = ElectrumClient.TransactionHistoryItem(randomHeight, randomBytes32)

  def randomHistoryItems = (0 to random.nextInt(100)).map(_ => randomHistoryItem).toList

  def randomProof = GetMerkleResponse(randomBytes32, (0 until 10).map(_ => randomBytes32).toList, random.nextInt(100000), 0, None)

  def randomPersistentData = {
    val transactions = for (i <- 0 until random.nextInt(100)) yield randomTransaction

    PersistentData(
      accountKeysCount = 10,
      changeKeysCount = 10,
      status = (for (i <- 0 until random.nextInt(100)) yield randomBytes32 -> random.nextInt(100000).toHexString).toMap,
      transactions = transactions.map(tx => tx.hash -> tx).toMap,
      overriddenPendingTxids = transactions.map(tx => tx.txid -> tx.txid).toMap,
      history = (for (i <- 0 until random.nextInt(100)) yield randomBytes32 -> randomHistoryItems).toMap,
      proofs = (for (i <- 0 until random.nextInt(100)) yield randomBytes32 -> randomProof).toMap,
      pendingTransactions = transactions.toList
    )
  }

  test("add/get/list headers") {
    val connection = SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, DataTable, ElectrumHeadersTable)
    val db = new SQLiteData(connection)
    val headers = makeHeaders(100)
    db.addHeaders(headers, 2016)

    val headers1 = db.getHeaders(2016, Int.MaxValue)
    assert(headers1 === headers)

    val headers2 = db.getHeaders(2016, 50)
    assert(headers2 === headers.take(50))

    var height = 2016
    headers.foreach(header => {
      val Some((height1, header1)) = db.getHeader(header.hash)
      assert(height1 == height)
      assert(header1 == header)

      val Some(header2) = db.getHeader(height1)
      assert(header2 == header)
      height = height + 1
    })
  }

  test("serialize persistent data") {
    val connection = SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, ChainWalletTable, ElectrumHeadersTable)
    val db = new SQLiteChainWallet(connection)
    assert(db.listWallets.isEmpty)

    val data = randomPersistentData
    val key = randomKey.publicKey
    val core = SigningWallet(EclairWallet.BIP49, isRemovable = true)
    val info = CompleteChainWalletInfo(core, persistentDataCodec.encode(data).require.toByteVector, Satoshi(1000L), "label")
    db.addChainWallet(info, info.data, key)
    val List(check) = db.listWallets.toList
    assert(check === info)
  }
}
