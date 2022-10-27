package immortan.electrum

import scala.concurrent.Future

import immortan.electrum.ElectrumClient._
import immortan.{LNParams, every, after}
import immortan.LNParams.ec

class ElectrumClientPlatform(
    pool: ElectrumClientPool,
    server: ElectrumServerAddress,
    onReady: ElectrumClient => Unit
) extends ElectrumClient {
  def address: String = ""
  def shutdown(): Unit = {}
  def request[R <: Response](r: Request): Future[R] =
    Future.failed(new NotImplementedError("not implemented"))
}
