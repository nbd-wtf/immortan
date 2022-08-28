package immortan.blockchain.fee

import scoin.FeeratePerKw

trait FeeEstimator {
  def getFeeratePerKw(target: Int): FeeratePerKw
}

case class FeeTargets(
    fundingBlockTarget: Int,
    commitmentBlockTarget: Int,
    mutualCloseBlockTarget: Int,
    claimMainBlockTarget: Int
)

case class OnChainFeeConf(feeTargets: FeeTargets, feeEstimator: FeeEstimator)
