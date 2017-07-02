package actors

import akka.NotUsed
import akka.actor.{Actor, ActorLogging}
import akka.event.LoggingReceive
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Source
import stocks._

import scala.collection.mutable
import scala.concurrent.duration._

/**
 * This actor contains a set of stocks internally that may be used by
 * all websocket clients.
 */
class StocksActor extends Actor with ActorLogging {

  // May want to remove stocks that aren't viewed by any clients...
  private val stocksMap: mutable.Map[StockSymbol, Stock] = mutable.HashMap()

  def receive = LoggingReceive {
    case WatchStocks(symbols) =>
      val stocks = symbols.map(symbol => stocksMap.getOrElseUpdate(symbol, new Stock(symbol)))
      sender ! Stocks(stocks)
  }
}

/**
 * A stock is a stream of stock quotes and a symbol.
 */
class Stock(val symbol: StockSymbol) {
  private lazy val stockQuoteGenerator: StockQuoteGenerator = new FakeStockQuoteGenerator(symbol)

  // http://doc.akka.io/docs/akka/current/scala/stream/stages-overview.html#unfold
  // http://doc.akka.io/docs/akka/current/scala/stream/stream-quickstart.html#time-based-processing
  val source: Source[StockQuote, NotUsed] = {
    Source.unfold(stockQuoteGenerator.seed) { (last: StockQuote) =>
      val next = stockQuoteGenerator.newQuote(last)
      Some(next, next)
    }
  }
}

case class Stocks(stocks: Set[Stock]) {
  require(stocks.nonEmpty, "Must specify at least one stock!")
}

case class WatchStocks(symbols: Set[StockSymbol]) {
  require(symbols.nonEmpty, "Must specify at least one symbol!")
}

