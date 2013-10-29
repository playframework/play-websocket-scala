package controllers

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

import actors.StocksActor
import actors.UnwatchStock
import actors.UserActor
import actors.WatchStock
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.Timeout
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsValue
import play.api.mvc.Action
import play.api.mvc.Controller
import play.api.mvc.WebSocket

/**
 * The main web controller that handles returning the index page, setting up a WebSocket, and watching a stock.
 */
object Application extends Controller {
  implicit val timeout = Timeout(1 second)

  def index = Action { implicit request =>
    Ok(views.html.index())
  }

  def ws: WebSocket[JsValue] = WebSocket.using[JsValue] { request =>
    val actorPromise = Promise[ActorRef]()
    val enumerator = Concurrent.unicast[JsValue] { channel =>
      // called when an enumerator is applied to an iteratee -> we can't create the
      // iteratee since this is only called with the iteratee!
      val userActor = Akka.system.actorOf(Props(classOf[UserActor], channel))
      actorPromise.success(userActor)
    }

    val iteratee = Iteratee.foreach[JsValue] { event =>
      actorPromise.future.map(userActor => {
        StocksActor.stocksActor.tell(WatchStock((event \ "symbol").as[String]), userActor)
      })
    }.map { _ =>
      // called at shutdown
      actorPromise.future.map(userActor => {
        StocksActor.stocksActor.tell(UnwatchStock(None), userActor)
        Akka.system.stop(userActor)
      })
    }
    (iteratee, enumerator)
  }
}
