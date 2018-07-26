package controllers

import javax.inject._
import play.api._
import play.api.libs.json.{JsObject, JsValue}
import play.api.mvc._
import services.GuessService
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(cc: ControllerComponents, gs: GuessService) extends AbstractController(cc) {

  /**
   * Create an Action to render an HTML page.
   *
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  def initSession(login: String, level: String) = Action {
    // TODO : Inits a session and returns a seed, parameters are mode (easy: synopsis + actors pics, medium: synopsis + timeout, hard: actors only + timeout)
    Ok(gs.initGameSession(login, level))
  }

  def getQuestion (sessionId: String): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    val moviePool = gs.getGuessPool()
    val finalContent = moviePool.flatMap(gs.getFinalPool(sessionId))
    finalContent.map(x => Ok(x))
  }

  def checkAnswer(answer: Int, gameSessionId: String): Action[AnyContent] = Action.async(
    gs.checkAnswer(answer, gameSessionId).map(Ok(_))
  )

  def getGameSession(gameSessionId: String): Action[AnyContent] = Action.async {
    gs.getGameSession(gameSessionId).map(Ok(_))
  }
}
