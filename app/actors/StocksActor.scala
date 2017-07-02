package actors

import akka.actor.{Actor, ActorLogging, Props}
import akka.event.LoggingReceive
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source

class StocksActor extends Actor with ActorLogging {

  private var stocksMap: Map[String, Source[Double, _]] = Map.empty

  def receive = LoggingReceive {
    case watchStock@WatchStock(symbol) =>
      val stockQueue = Source.queue[Double](50, OverflowStrategy.backpressure)
      stocksMap += (symbol -> stockQueue)
      // get or create the StockActor for the symbol and forward this message
      context.child(symbol).getOrElse { 
        context.actorOf(Props(new StockActor(symbol)), symbol)
      } forward watchStock
    case unwatchStock@UnwatchStock(Some(symbol)) =>
      // if there is a StockActor for the symbol forward this message
      context.child(symbol).foreach(_.forward(unwatchStock))
    case unwatchStock@UnwatchStock(None) =>
      // if no symbol is specified, forward to everyone
      context.children.foreach(_.forward(unwatchStock))
  }

}
