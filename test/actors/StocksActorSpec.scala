package actors

import akka.actor.Props
import akka.testkit.TestProbe

import scala.concurrent.duration._

class StocksActorSpec extends TestKitSpec {

  "StockHistory" should {
    val symbol = "ABC"

    "send a StockHistory message to the user" in {
      val probe = new TestProbe(system)
      val stocksActor = system.actorOf(Props[StocksActor])

      // create an actor which will test the UserActor
      val userActor = system.actorOf(Props(new ProbeWrapper(probe)))

      // Fire off the message, setting the sender as the UserActor
      // Simulates sending the message as if it was sent from the userActor
      stocksActor.tell(WatchStock(symbol), userActor)

      // Should create a new stockActor as a child and send it the stock history
      val stockHistory = probe.receiveOne(500 millis)
      stockHistory mustBe a[StockHistory]
    }
    
    "send a unwatchStock message to the user" in {
      val probe = new TestProbe(system)
      val probeStock = new TestProbe(system)

      val stocksActor = system.actorOf(Props[StocksActor])

      // create an actor which will test the UserActor
      val userActor = system.actorOf(Props(new ProbeWrapper(probe)))

      // First watch stock
      stocksActor.tell(WatchStock(symbol), userActor)

      // the userActor will be added as a watcher and get a message with the stock history
      val userActorMessage = probe.receiveOne(500 millis)
      userActorMessage mustBe a[StockHistory]

      // the userActor will get a message with the stock update
      val stockUpdateMsg = probe.receiveOne(500 millis)
      stockUpdateMsg mustBe a[StockUpdate]

      // now unwatch 
      stocksActor.tell(UnwatchStock(Some(symbol)), userActor)

      // Should not expect any more messages at this point
      probe.expectNoMsg()
    }
    
  }
}
