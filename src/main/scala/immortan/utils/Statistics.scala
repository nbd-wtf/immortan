package immortan.utils


abstract class Statistics[O] {
  type Collection = Traversable[O]

  def extract(item: O): Double

  def topOutlier(mu: Double, sd: Double, devitaionTimes: Double)(item: O): Boolean = extract(item) > mu + sd * devitaionTimes

  def bottomOutlier(mu: Double, sd: Double, devitaionTimes: Double)(item: O): Boolean = extract(item) < mu + sd * devitaionTimes

  def variance(items: Collection, mean: Double): Double = (0D /: items) { case (total, item) => math.pow(extract(item) - mean, 2) + total } / items.size

  def sd(items: Collection, mean: Double): Double = math sqrt variance(items, mean)

  def mean(items: Collection): Double = items.map(extract).sum / items.size
}