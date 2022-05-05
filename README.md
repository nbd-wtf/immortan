# IMMORTAN

Immortan is a minimal, privacy-focused, opinionated LN protocol implementation aimed to specifically power lite LN nodes which are mostly to be found on mobile phones or desktop computers and only run private channels.

## Raison d'etre

Existing implementations are of general purpose, that is, they neither address specific complications of running LN nodes on constrained devices nor explore various advantages unique to these setups. Here are _some_ highlights:

- **Security**. Lite nodes can not properly validate a public routing graph since that would require a direct access to local, non-pruning, tx-indexed bitcoind instance which they by definition don't have. Current popular approach of naively accepting any data without validation is prone to spam attacks, destroyed privacy and overblown payment fees. Immortan's answer is to obtain graph data from multiple random nodes at once and then select the most probable candidates based on majority vote, thus minimizing a possibility of attacks.

- **Privacy**. Lite nodes need not have any `nodeId` at all which is only required for validation of public channels and otherwise degrades privacy by making node's channels and invoices linkable. Immortan uses random `nodeId`s whenever possible (when making invoices, synchronizing graph, etc) as well as separate `nodeId`s for each peer a node has channels with, thus making it seen as a different remote node by each of its peers.

- **Resilience**. Preserving channels from getting force-closed is a top priority so IMMORTAN never force-closes channels with locally-originated expired HTLCs as well as with dusty routed HTLCs. It also rejects routing attempts early in unfavorable conditions, such as too high chain fees with too low remaining channel balances. Generally local channels are only force-closed automatically in a handful of terminal cases while in all other situations a decision whether to force-close or try to repair a channel is handed over to user.

- **Routing**. Lite nodes can have much more powerful MPP and pathfinding algorithms when compared to full nodes exactly because they are not supposed to run thousands of channels by design. Immortan makes a compromise here by running all local channels within a single thread and thus obtaining a far greater degree of control over the dynamics of multipart payment, which in turn results in more successful deliveries.

- **Liquidity via private routing**. Unless private channels are able to route 3rd party payments, a remote peer can not unilaterally get its money out of channel, so its liquidity gets locked, ultimately making a whole enterprise a money-losing affair. This issue is fixed here by utilizing relevant properties of trampoline routing, introduction of new extensions to LN protocol and development of mobile-aware Bitcoin wallet good enough to provide routing safety.

- **Liquidity via hosted channels**. IMMORTAN provides an implementation of private hosted channels which enables private and auditable custodians on the Lightning Network.

### Approach

Whatever possible is taken from Acinq's [Eclair](https://github.com/ACINQ/eclair) codebase; this mostly includes low level stuff such as cryptography, messaging codecs, Bitcoin script utilities and so on. Then, a very different choices are made on a higher level when it comes to graph sync, MPP, pathfinding, peer and channel management.

## Usage

### Installation

Install it by adding to your `build.sbt`:

```sbt
libraryDependencies += "com.fiatjaf" %% "immortan" % "0.1.0"
```

### Very dense and confusing guide

Then you must
  1. Import the `LNParams` object which will be the main interface between your code and Immortan.
  2. Implement some [database traits](https://github.com/fiatjaf/IMMORTAN/blob/master/src/main/scala/immortan/sqlite/DBInterface.scala) (or copy the implementation from [SBW](https://github.com/btcontract/wallet) as it was done in [Cliché](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/com/btcontract/wallet/sqlite/DBInterfaceSQLiteAndroidMisc.scala) by [Ilya](https://github.com/engenegr) -- see also [Cliché's DB.scala](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/DB.scala) for the final touch).
  3. Instantiate a `PathFinder`, which will take care of syncing Lightning gossip data and later sending out payments.
  4. Fill in `.connectionProvider`, `.logBag`, `.chainHash`, `.ourInit`, `.syncParams`, `.secret`, `.feeRates`, `.fiatRates`, `.cm` and `.chainWallets`;
  5. Initiate actors for `ElectrumClientPool`, `ElectrumChainSync`, `ElectrumWatcher` and `WalletEventsCatcher`;

See [Cliché's Main.scala](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/Main.scala) for a mostly clean implementation of all of the above.

After that you should be able to easily
  1. Load onchain wallets, listen for transactions and make transactions.
  2. Open normal channels;
  3. Open hosted channels;
  4. Listen for outgoing Lightning payments status changes;
  5. Listen for incoming Lightning payments status changes;
  6. Create Lightning invoices;
  7. Send Lightning payments.

Again, [Cliché's Commands.scala](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/Commands.scala) has some implementations you might be able to understand and copy (the listeners are in [Main.scala](https://github.com/fiatjaf/cliche/blob/b00cb3fdf62cd65854a14b005825dcab45df1002/src/main/scala/Main.scala)).

### [API Documentation (Scaladoc)](https://fiatjaf.github.io/IMMORTAN/immortan/index.html)

Or maybe start here at [LNParams](https://fiatjaf.github.io/IMMORTAN/immortan/LNParams$.html).

## Library users

- An updated, private-routing-enabled [Simple Bitcoin Wallet](https://github.com/btcontract/wallet).
- [Cliché](https://github.com/fiatjaf/cliche), a lightweight node daemon to be embedded into applications
- The seamless hosted fiat channels provider [StandardSats](https://github.com/standardsats/wallet) wallet

## Attribution

This is a fork of [Anton Kumaigorodski](https://github.com/btcontract)'s [code](https://github.com/btcontract/immortan) that was ported to Scala 2.13, prettified with `scalafmt`, had some things refactored due to the port and was published as a library to Maven Central. Aside from that, all credit is due to Anton and his backers:

<table>
  <tbody>
    <tr>
      <td align="center" valign="middle">
        <a href="https://lnbig.com/" target="_blank">
          <img width="146px" src="https://i.imgur.com/W4A92Ym.png">
        </a>
      </td>
    </tr>
  </tbody>
</table>
