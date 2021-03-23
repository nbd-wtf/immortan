package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import immortan.utils.GraphUtils._
import immortan.utils.PaymentUtils._
import fr.acinq.eclair.payment.PaymentRequest
import immortan.fsm.{TrampolinePaymentRelayer, TrampolineRevealed}
import org.scalatest.funsuite.AnyFunSuite
import fr.acinq.eclair.wire.{TrampolineOn, UpdateAddHtlc}
import fr.acinq.bitcoin.{Block, Crypto}
import fr.acinq.eclair.transactions.RemoteFulfill
import immortan.utils.ChannelUtils.{makeChannelMasterWithBasicGraph, makeHostedCommits}
import immortan.crypto.Tools._

class PaymentTrampolineRoutingSpec extends AnyFunSuite {
  test("Correctly parse trampoline routed payments sent to our fake nodeId") {
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer)
    val ourParams = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0D, logExponent = 0D, CltvExpiryDelta(72))
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)

    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(100000L.msat), randomBytes32, dP, "Invoice", CltvExpiryDelta(18)) // Final payee
    val outerPaymentSecret = randomBytes32

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
      createInnerTrampoline(pr, from = remoteNodeInfo.nodeId, toTrampoline = remoteNodeInfo.nodeSpecificPubKey,
        toFinal = d, trampolineExpiryDelta = CltvExpiryDelta(720), trampolineFees = 1000L.msat)

    val addFromRemote1 = createTrampolineAdd(pr, 11000L.msat, from = remoteNodeInfo.nodeId, toTrampoline = remoteNodeInfo.nodeSpecificPubKey, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret)
    val addFromRemote2 = createTrampolineAdd(pr, 90000L.msat, from = remoteNodeInfo.nodeId, toTrampoline = remoteNodeInfo.nodeSpecificPubKey, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret)

    val reasonableTrampoline1 = ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote1, remoteInfo = remoteNodeInfo)).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote2, remoteInfo = remoteNodeInfo)).asInstanceOf[ReasonableTrampoline]
    assert(TrampolinePaymentRelayer.validateRelay(ourParams, List(reasonableTrampoline1, reasonableTrampoline2), LNParams.blockCount.get).isEmpty)
    assert(reasonableTrampoline1.packet.innerPayload.outgoingNodeId == d)
    assert(reasonableTrampoline2.packet.innerPayload.outgoingNodeId == d)

    val addFromRemote3 = createTrampolineAdd(pr, 90000L.msat, from = remoteNodeInfo.nodeId, toTrampoline = randomKey.publicKey, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret)
    assert(ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote3, remoteInfo = remoteNodeInfo)).isInstanceOf[CMD_FAIL_MALFORMED_HTLC])
  }

  test("Successfully route a multipart trampoline payment") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0D, logExponent = 0D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for

    //             / b \
    // s -> us -> a     d
    //             \ c /

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, dP, "Invoice", CltvExpiryDelta(18)) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2") // Private channel US -> A
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) =
      createInnerTrampoline(pr, from = remoteNodeInfo.nodeId, toTrampoline = remoteNodeInfo.nodeSpecificPubKey,
        toFinal = d, trampolineExpiryDelta = CltvExpiryDelta(720), trampolineFees = 7000L.msat)

    val outerPaymentSecret = randomBytes32
    val addFromRemote1 = createTrampolineAdd(pr, 107000L.msat, from = remoteNodeInfo.nodeId, toTrampoline = remoteNodeInfo.nodeSpecificPubKey, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret)
    val addFromRemote2 = createTrampolineAdd(pr, 400000L.msat, from = remoteNodeInfo.nodeId, toTrampoline = remoteNodeInfo.nodeSpecificPubKey, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret)
    val addFromRemote3 = createTrampolineAdd(pr, 400000L.msat, from = remoteNodeInfo.nodeId, toTrampoline = remoteNodeInfo.nodeSpecificPubKey, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret)
    val reasonableTrampoline1 = ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote1, remoteInfo = remoteNodeInfo)).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote2, remoteInfo = remoteNodeInfo)).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline3 = ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = addFromRemote3, remoteInfo = remoteNodeInfo)).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    synchronized(wait(400L))

    val List(p1, p2) = cm.opm.data.payments(reasonableTrampoline1.fullTag).data.inFlightParts.toList
    cm.opm process RemoteFulfill(preimage, UpdateAddHtlc(null, 1, null, paymentHash, null, p1.cmd.packetAndSecrets.packet, null))
    synchronized(wait(100L))
    fsm doProcess TrampolineRevealed(preimage, cm.opm.data.payments(reasonableTrampoline1.fullTag).data.toSome)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    synchronized(wait(100L))
    println(fsm.state)
  }
}
