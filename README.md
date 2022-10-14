<a href="https://nbd.wtf"><img align="right" height="196" src="https://user-images.githubusercontent.com/1653275/194609043-0add674b-dd40-41ed-986c-ab4a2e053092.png" /></a>

# IMMORTAN

Immortan is a minimal, privacy-focused, opinionated LN protocol implementation aimed to specifically power lite LN nodes which are mostly to be found on mobile phones or desktop computers and only run private channels.

Immortan is not based on Éclair and is not an Éclair fork. It's a standalone implementation, much smaller, written from scratch with standalone and independent logic. However it does use codecs and type definitions carefully copied and pasted from the Éclair codebase.

## Raison d'etre

Existing implementations are of general purpose, that is, they neither address specific complications of running LN nodes on constrained devices nor explore various advantages unique to these setups. Here are _some_ highlights:

- **Security**. Lite nodes can not properly validate a public routing graph since that would require a direct access to local, non-pruning, tx-indexed bitcoind instance which they by definition don't have. Current popular approach of naively accepting any data without validation is prone to spam attacks, destroyed privacy and overblown payment fees. Immortan's answer is to obtain graph data from multiple random nodes at once and then select the most probable candidates based on majority vote, thus minimizing a possibility of attacks.

- **Privacy**. Lite nodes need not have any `nodeId` at all which is only required for validation of public channels and otherwise degrades privacy by making node's channels and invoices linkable. Immortan uses random `nodeId`s whenever possible (when making invoices, synchronizing graph, etc) as well as separate `nodeId`s for each peer a node has channels with, thus making it seen as a different remote node by each of its peers.

- **Resilience**. Preserving channels from getting force-closed is a top priority so IMMORTAN never force-closes channels with locally-originated expired HTLCs as well as with dusty routed HTLCs. It also rejects routing attempts early in unfavorable conditions, such as too high chain fees with too low remaining channel balances. Generally local channels are only force-closed automatically in a handful of terminal cases while in all other situations a decision whether to force-close or try to repair a channel is handed over to user.

- **Routing**. Lite nodes can have much more powerful MPP and pathfinding algorithms when compared to full nodes exactly because they are not supposed to run thousands of channels by design. Immortan makes a compromise here by running all local channels within a single thread and thus obtaining a far greater degree of control over the dynamics of multipart payment, which in turn results in more successful deliveries.

- **Liquidity via hosted channels**. IMMORTAN provides an implementation of private hosted channels which enables private and auditable custodians on the Lightning Network.

## Usage

### Installation

Install it by adding to your `build.sbt`:

```sbt
libraryDependencies += "com.fiatjaf" %% "immortan" % "0.7.8"
```

### Very dense and confusing guide

Then you must
  1. Import the `LNParams` object which will be the main interface between your code and Immortan.
  2. Implement some [database traits](https://github.com/fiatjaf/IMMORTAN/blob/master/src/main/scala/immortan/sqlite/DBInterface.scala) (or copy the implementation from [SBW](https://github.com/btcontract/wallet) as it was done in [Cliché](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/com/btcontract/wallet/sqlite/DBInterfaceSQLiteAndroidMisc.scala) by [Ilya](https://github.com/engenegr) -- see also [Cliché's DB.scala](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/DB.scala) for the final touch).
  3. Instantiate a `PathFinder`, which will take care of syncing Lightning gossip data and later sending out payments.
  4. Fill in `.connectionProvider`, `.logBag`, `.chainHash`, `.ourInit`, `.syncParams`, `.secret`, `.feeRates`, `.fiatRates`, `.cm` and `.chainWallets`;
  5. Instantiate `ElectrumClientPool`, `ElectrumChainSync`, `ElectrumWatcher` and `WalletEventsCatcher`;

See [Cliché's Main.scala](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/Main.scala) for a mostly clean implementation of all of the above.

After that you should be able to easily
  1. Load onchain wallets, listen for transactions and make transactions.
  2. Open normal channels;
  3. Open hosted channels;
  4. Listen for outgoing Lightning payments status changes;
  5. Listen for incoming Lightning payments status changes;
  6. Create Lightning invoices;
  7. Send Lightning payments.

Again, [Cliché's `Commands.scala`](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/Commands.scala) has some implementations you might be able to understand and copy (the listeners are in [Main.scala](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/Main.scala)).

### [API Documentation (Scaladoc)](https://javadoc.io/doc/com.fiatjaf/immortan_2.13/latest/index.html)

Or maybe start here at [LNParams](https://javadoc.io/doc/com.fiatjaf/immortan_2.13/latest/index.html/LNParams$.html).

## Library users

- The [Open Bitcoin Wallet](https://github.com/nbd-wtf/obw), an SBW fork with improved openness
- [Cliché](https://github.com/fiatjaf/cliche), a lightweight node daemon to be embedded into applications
- The seamless hosted fiat channels provider [StandardSats](https://github.com/standardsats/wallet) wallet
- [Simple Bitcoin Wallet](https://github.com/btcontract/wallet), the wallet that started it all, now abandoned
