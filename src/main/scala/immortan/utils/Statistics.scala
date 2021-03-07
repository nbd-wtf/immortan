package immortan.utils


abstract class Statistics[O] {
  type Collection = List[O]

  def extract(item: O): Double

  def removeExtremeOutliers(items: Collection, lower: Int = 20, upper: Int = 20): Collection = items.size match { case size =>
    items.sortBy(extract).drop(size / lower).dropRight(size / upper)
  }

  def isTopOutlier(mu: Double, sd: Double, devitaionTimes: Double)(item: O): Boolean = extract(item) > mu + sd * devitaionTimes

  def isBottomOutlier(mu: Double, sd: Double, devitaionTimes: Double)(item: O): Boolean = extract(item) < mu + sd * devitaionTimes

  def variance(items: Collection, mean: Double): Double = (0D /: items) { case (total, item) => math.pow(extract(item) - mean, 2) + total } / items.size

  def sd(items: Collection, mean: Double): Double = math sqrt variance(items, mean)

  def mean(items: Collection): Double = items.map(extract).sum / items.size
}