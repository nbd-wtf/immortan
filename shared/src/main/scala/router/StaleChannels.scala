package immortan.router

import scala.concurrent.duration._

object StaleChannels {
  def isStale(timestamp: Long): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    val staleThresholdSeconds =
      (System.currentTimeMillis.milliseconds - 14.days).toSeconds
    timestamp < staleThresholdSeconds
  }

  def isAlmostStale(timestamp: Long): Boolean = {
    // we define almost stale as 2 weeks minus 4 days
    val staleThresholdSeconds =
      (System.currentTimeMillis.milliseconds - 10.days).toSeconds
    timestamp < staleThresholdSeconds
  }
}
