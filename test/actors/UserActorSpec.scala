package actors

import scala.collection.JavaConverters.seqAsJavaListConverter

import org.mockito.Mockito.verify
import org.scalatest.mock.MockitoSugar
import org.specs2.matcher.JsonMatchers
import org.specs2.mock.mockito.ArgumentCapture
import org.specs2.mutable.SpecificationLike
import org.specs2.time.NoTimeConversions

import akka.actor.Props
import akka.testkit.TestActorRef
import play.api.libs.iteratee.Concurrent
import play.api.libs.json.JsValue
import play.api.test.WithApplication

class UserActorSpec extends TestkitExample with SpecificationLike with JsonMatchers with NoTimeConversions with MockitoSugar {

  /*
   * Running tests in parallel (which would ordinarily be the default) will work only if no
   * shared resources are used (e.g. top-level actors with the same name or the
   * system.eventStream).
   *
   * It's usually safer to run the tests sequentially.
   */

  sequential

  "UserActor" should {

    val symbol = "ABC"
    val price = 123
    val history = List[java.lang.Double](0.1, 1.0).asJava

    "send a stock when receiving a StockUpdate message" in new WithApplication {
      val mockChannel = mock[Concurrent.Channel[JsValue]]

      val userActorRef = TestActorRef[UserActor](Props(new UserActor(mockChannel)))
      val userActor = userActorRef.underlyingActor

      // send off the stock update...
      userActor.receive(StockUpdate(symbol, price))

      // ...and expect it to be a JSON node.
      val argument = new ArgumentCapture[JsValue]
      verify(mockChannel).push(argument.capture)
      val node = argument.value.toString
      node must /("type" -> "stockupdate")
      node must /("symbol" -> symbol)
      node must /("price" -> price)
    }

    "send the stock history when receiving a StockHistory message" in new WithApplication {
      val mockChannel = mock[Concurrent.Channel[JsValue]]

      val userActorRef = TestActorRef[UserActor](Props(classOf[UserActor], mockChannel))
      val userActor = userActorRef.underlyingActor

      // send off the stock update...
      userActor.receive(StockHistory(symbol, history))

      // ...and expect it to be a JSON node.
      val argument = new ArgumentCapture[JsValue]
      verify(mockChannel).push(argument.capture)
      val node = argument.value.toString
      node must /("type" -> "stockhistory")
      node must /("symbol" -> symbol)
      (argument.value \ "history")(0).as[Double] must beEqualTo(history.get(0))
    }
  }

}
