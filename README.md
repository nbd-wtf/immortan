Immortan is a minimal, privacy-focused, opinionated LN protocol implementation aimed to specifically power lite LN nodes which are mostly to be found on mobile phones or desktop computers and only run private channels.


### Raison d'etre

Existing implementations are of general purpose, that is, they neither address specific complications of running LN nodes on constrained devices nor explore various advantages unique to these setups. Here are _some_ highlights:

- **Security**. Lite nodes can not properly validate a public routing graph since that would require a direct access to local, non-pruning, tx-indexed bitcoind instance which they by definition don't have. Current popular approach of naively accepting any data without validation is prone to spam attacks, destroyed privacy and overblown payment fees. Immortan's answer is to obtain graph data from multiple random nodes at once and then select the most probable candidates based on majority vote, thus minimizing a possibility of attacks.

- **Privacy**. Lite nodes need not have any `nodeId` at all which is only required for validation of public channels and otherwise degrades privacy by making node's channels and invoices linkable. Immortan uses random `nodeId`s whenever possible (when making invoices, synchronizing graph, etc) as well as separate `nodeId`s for each peer a node has channels with, thus making it seen as a different remote node by each of its peers.

- **Resillience**. Preserving channels from getting force-closed is a top priority so IMMORTAN never force-closes channels with locally-originated expired HTLCs as well as with dusty routed HTLCs. It also rejects routing attempts early in unfavorable conditions, such as too high chain fees with too low remaining channel balances. Generally local channels are only force-closed automatically in a handful of terminal cases while in all other situations a decision whether to force-close or try to repair a channel is handed over to user.

- **Routing**. Lite nodes can have much more powerful MPP and pathfinding algorithms when compared to full nodes exactly because they are not supposed to run thousands of channels by design. Immortan makes a compromise here by running all local channels within a single thread and thus obtaining a far greater degree of control over the dynamics of multipart payment, which in turn results in more successful deliveries.

- **Liquidity via private routing**. Unless private channels are able to route 3rd party payments, a remote peer can not unilaterally get its money out of channel, so its liquidity gets locked, ultimately making a whole enterprise a money-losing affair. This issue is fixed here by utilizing relevant properties of trampoline routing, introduction of new extensions to LN protocol and development of mobile-aware Bitcoin wallet good enough to provide routing safety.

- **Liquidity via hosted channels**. IMMORTAN provides an implementation of private hosted channels which enables private and auditable custodians on the Lightning Network.


### Approach

Whatever possible is taken from Acinq's [Eclair](https://github.com/ACINQ/eclair) codebase; this mostly includes low level stuff such as cryptography, messaging codecs, Bitcoin script utilities and so on. Then, a very different choices are made on a higher level when it comes to graph sync, MPP, pathfinding, peer and channel management.


### Status

Ready for use, check (admittedly way too brief, will be improved) [wiki](https://github.com/btcontract/IMMORTAN/wiki/Getting-started).


### Supporting project

Immortan is an open source library free to use by anyone, development is made possible entirely by the support of these awesome backers:

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


### Library users

- An updated, private-routing-enabled [Simple Bitcoin Wallet](https://github.com/btcontract/wallet).
