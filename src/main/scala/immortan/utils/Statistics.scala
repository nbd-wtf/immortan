package immortan.utils


abstract class Statistics[O] {
  type Collection = Traversable[O]

  def extract(item: O): Double

  def notTopOutlier(mu: Double, sd: Double, stdDevs: Double)(item: O): Boolean = extract(item) <= mu + sd * stdDevs

  def notBottomOutlier(mu: Double, sd: Double, stdDevs: Double)(item: O): Boolean = extract(item) >= mu + sd * stdDevs

  def variance(items: Collection, mean: Double): Double = (0D /: items) { case (total, item) => math.pow(extract(item) - mean, 2) + total } / items.size

  def mean(items: Collection): Double = items.map(extract).sum / items.size

  def sd(items: Collection, mean: Double): Double = {
    val itemsVariance = variance(items, mean)
    math.sqrt(itemsVariance)
  }
}