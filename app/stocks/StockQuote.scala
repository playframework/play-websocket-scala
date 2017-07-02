package stocks
import play.api.libs.json._ // Combinator syntax

trait StockQuoteGenerator {
  def seed: StockQuote
  def newQuote(lastQuote: StockQuote): StockQuote
}

class FakeStockQuoteGenerator(symbol: StockSymbol) extends StockQuoteGenerator {
  private def random: Double = scala.util.Random.nextDouble

  def seed: StockQuote = {
    StockQuote(symbol, StockPrice(random * 800))
  }

  def newQuote(lastQuote: StockQuote): StockQuote = {
    StockQuote(symbol, StockPrice(lastQuote.price.raw * (0.95 + (0.1 * random))))
  }

}

case class StockQuote(symbol: StockSymbol, price: StockPrice)

/** Value class for a stock symbol */
class StockSymbol private (val raw: String) extends AnyVal {
  override def toString: String = raw
}

object StockSymbol {
  def apply(raw: String) = new StockSymbol(raw)

  implicit val stockSymbolWrites: Writes[StockSymbol] = Writes {
    (symbol: StockSymbol) => JsString(symbol.raw)
  }
}

/** Value class for stock price */
class StockPrice private (val raw: Double) extends AnyVal {
  override def toString: String = raw.toString
}

object StockPrice {
  def apply(raw: Double):StockPrice = new StockPrice(raw)

  implicit val stockPriceWrites: Writes[StockPrice] = Writes {
    (price: StockPrice) => JsNumber(price.raw)
  }
}