package actors

import java.util.Collections

import scala.collection.JavaConversions.asScalaBuffer

import akka.actor.Actor
import akka.actor.actorRef2Scala
import play.api.Play
import play.api.Play.current
import play.api.libs.iteratee.Concurrent
import play.api.libs.json.JsNumber
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper

/**
 * The broker between the WebSocket and the StockActor(s).  The UserActor holds the connection and sends serialized
 * JSON data to the client.
 */

class UserActor(channel: Concurrent.Channel[JsValue]) extends Actor {
  // add some default stocks
  val defaultStocks = Play.application.configuration.getStringList("default.stocks")
  defaultStocks.getOrElse(Collections.emptyList).foreach {
    StocksActor.stocksActor ! new WatchStock(_)
  }

  def receive = {
    case stockUpdate: StockUpdate =>
      // push the stock to the client
      val stockUpdateMessage = Json.obj(
        "type" -> "stockupdate",
        "symbol" -> stockUpdate.symbol,
        "price" -> stockUpdate.price.doubleValue())
      channel.push(stockUpdateMessage)
    case stockHistory: StockHistory =>
      val historyItems = stockHistory.history.map(x => new JsNumber(BigDecimal.valueOf(x)))

      // push the history to the client
      val stockHistoryMessage = Json.obj(
        "type" -> "stockhistory",
        "symbol" -> stockHistory.symbol,
        "history" -> historyItems)
      channel.push(stockHistoryMessage)
  }
}
