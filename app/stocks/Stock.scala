package stocks

import javax.money.{Monetary, MonetaryAmount}

import akka.NotUsed
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Source
import org.javamoney.moneta.Money

import scala.concurrent.duration._

/**
 * A stock is a source of stock quotes and a symbol.
 */
class Stock(val symbol: StockSymbol) {
  private val stockQuoteGenerator: StockQuoteGenerator = new FakeStockQuoteGenerator(symbol)

  private val source: Source[StockQuote, NotUsed] = {
    Source.unfold(stockQuoteGenerator.seed) { (last: StockQuote) =>
      val next = stockQuoteGenerator.newQuote(last)
      Some(next, next)
    }
  }

  /**
   * Returns a source of stock history, containing a single element.
   */
  def history(n: Int): Source[StockHistory, NotUsed] = {
    source.grouped(n).map(sq => new StockHistory(symbol, sq.map(_.price))).take(1)
  }

  /**
   * Provides a source that returns a stock quote every 75 milliseconds.
   */
  def update: Source[StockUpdate, NotUsed] = {
    source
      .throttle(elements = 1, per = 75.millis, maximumBurst = 1, ThrottleMode.shaping)
      .map(sq => new StockUpdate(sq.symbol, sq.price))
  }

  override val toString: String = s"Stock($symbol)"
}

trait StockQuoteGenerator {
  def seed: StockQuote
  def newQuote(lastQuote: StockQuote): StockQuote
}

class FakeStockQuoteGenerator(symbol: StockSymbol) extends StockQuoteGenerator {
  private val usd = Monetary.getCurrency("USD")

  private def random = scala.util.Random.nextDouble()

  private def amount(d: Double): MonetaryAmount = Money.of(d, usd)

  def seed: StockQuote = {
    StockQuote(symbol, StockPrice(amount(random * 800)))
  }

  def newQuote(lastQuote: StockQuote): StockQuote = {
    val jitter = lastQuote.price.raw.getNumber.doubleValueExact() * (0.95 + (0.1 * random))
    StockQuote(symbol, StockPrice(amount(jitter)))
  }
}

case class StockQuote(symbol: StockSymbol, price: StockPrice)

/** Value class for a stock symbol */
class StockSymbol private (val raw: String) extends AnyVal {
  override def toString: String = raw
}

object StockSymbol {
  import play.api.libs.json._ // Combinator syntax

  def apply(raw: String) = new StockSymbol(raw)

  implicit val stockSymbolReads: Reads[StockSymbol] = {
    JsPath.read[String].map(StockSymbol(_))
  }

  implicit val stockSymbolWrites: Writes[StockSymbol] = Writes {
    (symbol: StockSymbol) => JsString(symbol.raw)
  }
}

/** Value class for stock price */
class StockPrice private (val raw: javax.money.MonetaryAmount) extends AnyVal {
  override def toString: String = raw.toString
}

object StockPrice {
  import play.api.libs.json._ // Combinator syntax

  def apply(raw: javax.money.MonetaryAmount):StockPrice = {
    new StockPrice(raw)
  }

  implicit val stockPriceWrites: Writes[StockPrice] = Writes {
    (price: StockPrice) => JsNumber(price.raw.getNumber.doubleValueExact())
  }
}

// Used for automatic JSON conversion
// https://www.playframework.com/documentation/2.6.x/ScalaJson

// JSON presentation class for stock history
case class StockHistory(symbol: StockSymbol, prices: Seq[StockPrice])

object StockHistory {
  import play.api.libs.json._ // Combinator syntax

  implicit val stockHistoryWrites: Writes[StockHistory] = new Writes[StockHistory] {
    override def writes(history: StockHistory): JsValue = Json.obj(
      "type" -> "stockhistory",
      "symbol" -> history.symbol,
      "history" -> history.prices
    )
  }
}

// JSON presentation class for stock update
case class StockUpdate(symbol: StockSymbol, price: StockPrice)

object StockUpdate {
  import play.api.libs.json._ // Combinator syntax

  implicit val stockUpdateWrites: Writes[StockUpdate] = new Writes[StockUpdate] {
    override def writes(update: StockUpdate): JsValue = Json.obj(
      "type" -> "stockupdate",
      "symbol" -> update.symbol,
      "price" -> update.price
    )
  }
}
