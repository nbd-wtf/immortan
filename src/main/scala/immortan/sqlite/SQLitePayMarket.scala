package immortan.sqlite

import java.lang.{Long => JLong}
import immortan.utils.{LNUrl, PayRequest}
import immortan.{ChannelMaster, PayLinkInfo}
import fr.acinq.eclair.MilliSatoshi


class SQLitePayMarket(db: DBInterface) {
  def updateLabel(newLabel: String, pay: Option[LNUrl], nextWithdraw: Option[LNUrl] = None): Unit = {
    require(pay.isDefined || nextWithdraw.isDefined, "Item must contain either pay or nextWithdraw link")
    db.change(PayMarketTable.updLabelSql, pay.map(_.request).orNull, nextWithdraw.map(_.request).orNull)
    db.change(PayMarketTable.newVirtualSql, newLabel, pay.orElse(nextWithdraw).get.uri.getHost)
    ChannelMaster.next(ChannelMaster.payMarketDbStream)
  }

  def remove(pay: Option[LNUrl], nextWithdraw: Option[LNUrl] = None): Unit = {
    require(pay.isDefined || nextWithdraw.isDefined, "Item must contain either pay or nextWithdraw link")
    db.change(PayMarketTable.killSql, pay.map(_.request).orNull, nextWithdraw.map(_.request).orNull)
    ChannelMaster.next(ChannelMaster.payMarketDbStream)
  }

  // TODO: enable compound pay/withdraw item, for now we only process basic pay links
  def saveLink(pay: LNUrl, payReq: PayRequest, msat: MilliSatoshi, comment: String, hash: String, stamp: Long = System.currentTimeMillis): Unit = db txWrap {
    db.change(PayMarketTable.newSql, pay.uri.getHost, pay.request, new String, payReq.metadata, msat.toLong: JLong, stamp: JLong, hash, -1L: JLong, /* -1 = NO BALANCE */ comment, new String)
    db.change(PayMarketTable.updPayInfoSql, payReq.metadata, msat.toLong: JLong, stamp: JLong, hash, comment, pay.uri.getHost)
    db.change(PayMarketTable.newVirtualSql, payReq.meta.queryText, pay.uri.getHost)
    ChannelMaster.next(ChannelMaster.payMarketDbStream)
  }

  def searchLinks(rawSearchQuery: String): RichCursor = db.search(PayMarketTable.searchSql, rawSearchQuery)

  def listRecentLinks(limit: Int): RichCursor = db.select(PayMarketTable.selectRecentSql, limit.toString)

  def toLinkInfo(rc: RichCursor): PayLinkInfo =
    PayLinkInfo(domain = rc string PayMarketTable.domain, payString = rc string PayMarketTable.lnurlPay, nextWithdrawString = rc string PayMarketTable.nextLnurlWithdraw,
      metaString = rc string PayMarketTable.meta, lastMsat = MilliSatoshi(rc long PayMarketTable.lastMsat), lastDate = rc long PayMarketTable.lastDate,
      lastBalanceLong = rc long PayMarketTable.lastBalance, lastCommentString = rc string PayMarketTable.lastComment,
      labelString = rc string PayMarketTable.label)
}