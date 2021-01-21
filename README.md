Immortan is a minimal, privacy-focused, opinionated LN protocol implementation aimed to specifically power lite LN nodes which are mostly to be found on mobile phones or desktop computers and only run private channels.


### Raison d'etre

Existing implementations are of general purpose, that is, they neither address specific complications of running LN nodes on constrained devices nor explore various advantages unique to these setups. Here are _some_ highlights:

- **Security**. Lite nodes can not properly validate a public routing graph since that would require a direct access to local, non-pruning, tx-indexed bitcoind instance which they by definition don't have. Current popular approach of naively accepting any data without validation is prone to spam attacks, destroyed privacy and overblown payment fees. Immortan's answer is to obtain graph data from multiple random nodes at once and then select the most probable candidates based on majority vote, thus minimizing a possibility of attacks.

- **Privacy**. Lite nodes need not have any `nodeId` at all which is only required for validation of public channels and otherwise degrades privacy by making node's channels and invoices linkable. Immortan uses random `nodeId`s whenever possible (when making invoices, synchronizing graph, etc) as well as separate `nodeId`s for each peer a node has channels with, thus making it seen as a different remote node by each of its peers.

- **Routing**. Lite nodes can have much more powerful MPP and pathfinding algorithms when compared to full nodes exactly because they are not supposed to run thousands of channels by design. Immortan makes a compromise here by running all local channels within a single thread and thus obtaining a far greater degree of control over the dynamics of multipart payment which in turn results in more successful deliveries.

- **LIQUIDITY**. Last and most important: currently private channels do not route 3rd party payments which is a very grave mistake. The reason is: in absense of routing a peer can not unilaterally get its money out of channel so its liquidity gets locked, ultimately making a whole enterprise a money-losing affair. This can and will be fixed here by utilizing relevant properties of trampoline routing, introduction of new extensions to LN protocol and development of mobile-aware Bitcoin wallet good enough to provide routing safety.


### Approach

Whatever possible is taken from Acinq's [Eclair](https://github.com/ACINQ/eclair) codebase; this mostly includes low level stuff such as cryptography, messaging codecs, Bitcoin script utilities and so on. Then, a very different choices are made on a higher level when it comes to graph sync, MPP, pathfinding, peer and channel management.


### Status

Not yet ready for use, under heavy development. Detailed technical descriptions and integration tutorials will be provided once internal structures stabilize enough.


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
      <td align="center" valign="middle">
        <a href="http://lightblock.me/" target="_blank">
          lightblock.me
        </a>
      </td>
    </tr>
  </tbody>
</table>


### Library users

- An updated, private-routing-enabled, fully autonomous [BLW](https://github.com/btcontract/lnwallet).