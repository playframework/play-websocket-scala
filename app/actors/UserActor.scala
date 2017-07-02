package actors

import javax.inject._

import akka.NotUsed
import akka.actor.Status.Success
import akka.actor._
import akka.event.LoggingReceive
import akka.stream.scaladsl._
import akka.stream._
import akka.util.Timeout
import com.google.inject.assistedinject.Assisted
import play.api.Configuration
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json._
import stocks._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Creates a user actor that manages the websocket stream.
 *
 * @param stocksActor the actor responsible for stocks and their streams
 * @param ec implicit CPU bound execution context.
 */
class UserActor @Inject()(@Assisted id: String, @Named("stocksActor") stocksActor: ActorRef)
                         (implicit mat: Materializer, ec: ExecutionContext)
  extends Actor with ActorLogging {
  import akka.pattern.pipe

  implicit val timeout = Timeout(50.millis)

  // https://markatta.com/codemonkey/blog/2016/10/02/chat-with-akka-http-websockets/
  // http://doc.akka.io/docs/akka/current/scala/stream/stream-dynamic.html#dynamic-fan-in-and-fan-out-with-mergehub-and-broadcasthub
  // https://github.com/akka/akka/blob/master/akka-docs/src/test/scala/docs/stream/HubsDocSpec.scala
  // https://github.com/akka/akka/blob/master/akka-stream-tests/src/test/scala/akka/stream/scaladsl/HubSpec.scala
  val (hubSink, hubSource) = MergeHub.source[JsValue](perProducerBufferSize = 16)
    .toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both)
    .run()

  // https://github.com/akka/akka/blob/master/akka-stream/src/main/scala/akka/stream/KillSwitch.scala
  private var stockKillSwitches: Map[Stock,UniqueKillSwitch] = Map.empty

  // Set this actor as the sink when JsValue is sent from the browser.
  private val actorSink: Sink[JsValue, _] = Sink.actorRefWithAck(self,
    onInitMessage = Success("init"),
    ackMessage = Success("ack"),
    onCompleteMessage = PoisonPill)

  override def receive: Receive = LoggingReceive {
    case WatchStocks(symbols) =>
      val websocketFlowFuture = generateFlow(symbols)
      pipe(websocketFlowFuture) to sender()

    case Success(msg) =>
      log.debug(s"Successful $msg")

    case json: JsValue =>
      // When the user types in a stock in the upper right corner, this is triggered,
      // because this actor is a sink for the websocket flow.
      val symbol = (json \ "symbol").as[String]
      log.info("Received symbol " + symbol)
      //stocksActor ! WatchStocks(symbol)
      sender() ! Success("ack") // must signal processing in Sink.actorRefWithAck
  }

  /**
   * Generates a flow that can be used by the websocket.
   *
   * @param symbols stock symbols.
   * @return the flow of JSON
   */
  private def generateFlow(symbols: Set[StockSymbol]): Future[Flow[JsValue, JsValue, _]] = {
    import akka.pattern.ask

    // Ask the stocksActor for a stream containing these stocks.
    val future = (stocksActor ? WatchStocks(symbols)).mapTo[Stocks]

    // when we get the response back, we want to turn that into a flow by creating a single
    // source and a single sink, so we merge all of the stock sources together into one, and
    // set the actor itself up as the sink.
    future.map { (newStocks: Stocks) =>
      newStocks.stocks.foreach { stock =>
        val historySource: Source[JsValue, NotUsed] = generateStockHistorySource(stock)
          .named(s"history-${stock.symbol}-$id")
          .log(s"history-${stock.symbol}-$id")
        val updateSource: Source[JsValue, NotUsed] = generateStockUpdateSource(stock)
          .named(s"update-${stock.symbol}-$id")
          .log(s"update-${stock.symbol}-$id")
        val stockSource: Source[JsValue, NotUsed] = historySource.concat(updateSource)

        // Set up a flow that will let us pull out a killswitch for this specific stock.
        val killswitchFlow: Flow[JsValue, JsValue, UniqueKillSwitch] = {
          Flow.apply[JsValue].joinMat(KillSwitches.singleBidi[JsValue, JsValue])(Keep.right)
        }

        // Set up a complete runnable graph from the stock source to the hub's sink
        val graph: RunnableGraph[UniqueKillSwitch] = {
          stockSource
            .viaMat(killswitchFlow)(Keep.right)
            .to(hubSink)
            .named(s"stock-${stock.symbol}-$id")
        }

        // Start it up!
        val killSwitch = graph.run()

        // Pull out the kill switch so we can stop it when we want to unwatch a stock.
        stockKillSwitches += (stock -> killSwitch)
      }

      // Put the source and sink together to make a flow of hub source as output (aggregating all
      // stocks as JSON to the browser) and the actor as the sink (receiving any JSON messages
      // from the browser)
      val websocketFlow: Flow[JsValue, JsValue, NotUsed] = {
        Flow.fromSinkAndSource(actorSink, hubSource)
          .backpressureTimeout(3.seconds)
      }
      websocketFlow.log(s"websocket-$id", jsvalue => jsvalue.toString())
    }
  }

  /**
   * Generates the stockhistory source by taking the first 50 elements of each source.
   */
  private def generateStockHistorySource(stock: Stock): Source[JsValue, NotUsed] = {
    val stockHistory = stock.source.take(50).runWith(Sink.seq).map { s =>
      Json.toJson(StockHistory(stock.symbol, s.map(_.price)))
    }
    Source.fromFuture(stockHistory)
  }

  /**
   * Generates the stockupdate source
   *
   * @return a source containing the merged sources of all the stocks.
   */
  private def generateStockUpdateSource(stock: Stock): Source[JsValue, NotUsed] = {
    // Throttle the updates so they only happen once per 75 millis
    stock.source
      .throttle(elements = 1, per = 75.millis, maximumBurst = 1, ThrottleMode.shaping)
      .map { stockQuote =>
      Json.toJson(StockUpdate(stockQuote.symbol, stockQuote.price))
    }
  }

  // JSON presentation class for stock history
  case class StockHistory(symbol: StockSymbol, prices: Seq[StockPrice])
  object StockHistory {
    implicit val stockHistoryWrites: Writes[StockHistory] = new Writes[StockHistory]{
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
    // Used for automatic JSON conversion
    // https://www.playframework.com/documentation/2.6.x/ScalaJson
    implicit val stockUpdateWrites: Writes[StockUpdate] = new Writes[StockUpdate] {
      override def writes(update: StockUpdate): JsValue = Json.obj(
        "type" -> "stockupdate",
        "symbol" -> update.symbol,
        "price" -> update.price
      )
    }
  }
}

/**
 * Provide some DI and configuration sugar for new UserActor instances.
 */
class UserParentActor @Inject()(childFactory: UserActor.Factory,
                                configuration: Configuration)
                               (implicit ec: ExecutionContext)
  extends Actor with InjectedActorSupport with ActorLogging {
  import UserParentActor._
  import akka.pattern.{ask, pipe}

  implicit val timeout = Timeout(2.seconds)

  private val defaultStocks = configuration.get[Seq[String]]("default.stocks").map(StockSymbol(_))

  override def receive: Receive = LoggingReceive {
    case Create(id) =>
      log.info(s"Creating user actor with default stocks $defaultStocks")
      val child: ActorRef = injectedChild(childFactory(id), s"userActor-$id")
      val future = (child ? WatchStocks(defaultStocks.toSet)).mapTo[Flow[JsValue, JsValue, _]]
      pipe(future) to sender()
  }
}

object UserParentActor {
  case class Create(id: String)
}

object UserActor {
  trait Factory {
    def apply(id: String): Actor
  }
}
