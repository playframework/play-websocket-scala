package actors

import akka.actor._
import akka.testkit._

import scala.concurrent.duration._
import scala.collection.immutable.HashSet
import utils.StockQuote

class StockActorSpec extends TestKitSpec {

  final class StockActorWithStockQuote(symbol: String, price: Double, watcher: ActorRef) extends StockActor(symbol) {
    watchers = HashSet[ActorRef](watcher)
    override lazy val stockQuote = new StockQuote {
      def newPrice(lastPrice: Double): Double = price
    }
  }

  "WatchStock" should {
    val symbol = "ABC"

    "notify watchers when a new stock is received" in {
      // Create a stock actor with a stubbed out stockquote price and watcher
      val probe = new TestProbe(system)
      val price = 1234.0
      val stockActor = system.actorOf(Props(new StockActorWithStockQuote(symbol, price, probe.ref)))

      system.actorOf(Props(new ProbeWrapper(probe)))

      // Fire off the message...
      stockActor ! FetchLatest

      // ... and ask the probe if it got the StockUpdate message.
      val actualMessage = probe.receiveOne(500 millis)
      val expectedMessage = StockUpdate(symbol, price)
      actualMessage must ===(expectedMessage)
    }

    "add a watcher and send a StockHistory message to the user" in {
      val probe = new TestProbe(system)

      // Create a standard StockActor.
      val stockActor = system.actorOf(Props(new StockActor(symbol)))

      // create an actor which will test the UserActor
      val userActor = system.actorOf(Props(new ProbeWrapper(probe)))

      // Fire off the message, setting the sender as the UserActor
      // Simulates sending the message as if it was sent from the userActor
      stockActor.tell(WatchStock(symbol), userActor)

      // the userActor will be added as a watcher and get a message with the stock history
      val userActorMessage = probe.receiveOne(500.millis)
      userActorMessage mustBe a [StockHistory]
    }
    
    "remove watcher on UnwatchStock message" in {
      val probe = new TestProbe(system)

      // Create a standard StockActor.
      val stockActor = system.actorOf(Props(new StockActor(symbol)))
      probe watch stockActor
      
      // create an actor which will test the UserActor
      val userActor = system.actorOf(Props(new ProbeWrapper(probe)))

      // First user watch stock
      stockActor.tell(WatchStock(symbol), userActor)
      val userActorMessage = probe.receiveOne(500.millis)
      userActorMessage mustBe a [StockHistory]
      val stockUpdateMessage = probe.receiveOne(500.millis)
      stockUpdateMessage mustBe a [StockUpdate]

      // create another actor which will test the UserActor
      val userActor2 = system.actorOf(Props(new ProbeWrapper(probe)))

      // Second user watch stock
      stockActor.tell(WatchStock(symbol), userActor2)
      val userActorMessage2 = probe.receiveOne(500.millis)
      userActorMessage2 mustBe a [StockHistory]
      val stockUpdateMessage2 = probe.receiveOne(500.millis)
      stockUpdateMessage2 mustBe a [StockUpdate]

      // Now unwatch stock - user 1
      stockActor.tell(UnwatchStock(Some(symbol)), userActor)

      // the stockActor is supposed to be still active since there are more watchers listening, so a new stock update will be triggered      
      val stockUpdateMessageNew = probe.receiveOne(500.millis)
      stockUpdateMessageNew mustBe a [StockUpdate]      
    }    
    
    "remove watcher on UnwatchStock message if no more watchers left stop Actor" in {
      val probe = new TestProbe(system)

      // Create a standard StockActor.
      val stockActor = system.actorOf(Props(new StockActor(symbol)))
      probe watch stockActor
      
      // create an actor which will test the UserActor
      val userActor = system.actorOf(Props(new ProbeWrapper(probe)))

      // First watch stock
      // Simulates sending the message as if it was sent from the userActor
      stockActor.tell(WatchStock(symbol), userActor)

      // the userActor will be added as a watcher and get a message with the stock history
      val userActorMessage = probe.receiveOne(500.millis)
      userActorMessage mustBe a [StockHistory]

      // the userActor will receive a stock update
      val stockUpdateMessage = probe.receiveOne(500.millis)
      stockUpdateMessage mustBe a [StockUpdate]

      // Now unwatch stock
      stockActor.tell(UnwatchStock(Some(symbol)), userActor)

      // the stockActor is supposed to be killed when no more listeners are present      
      probe.expectTerminated(stockActor)     
    }    
    
  }

}
