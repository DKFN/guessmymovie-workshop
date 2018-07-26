package services

import java.io
import java.sql.Timestamp
import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.Inject
import javax.inject.Singleton
import models.{GameSession, NoGameStarted, Question}
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, JsString, Json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

@Singleton
class GuessService @Inject() (bsClient: BetaSeries){

  val initPoolSize = 100

  val gameDifficutlies = Set("easy", "normal", "hard")

  // Contains all the running game sessions
  var gameSessions: Seq[GameSession] = Seq()

  // Contains all the pending questions of the session
  var pendingQuestions: Seq[Question] = Seq()

  def now() = new Timestamp(System.currentTimeMillis).getTime

  /**
    * Gets the initial pool to choose later
    * @param goHard : The higher it gets the less popular the movies are (in a limit of 10kth less popular)
    * @return A seq[JsObject] containing all the pool
    */
  def getGuessPool(goHard: Int = 1): Future[Seq[JsObject]] = {
    val randOffset = scala.util.Random.nextInt(10000)
    bsClient.getInitMovies(initPoolSize, randOffset % (goHard * 200)).map(x => (x \ "movies").as[Seq[JsObject]])
  }

  def initGameSession(login: String, level: String) = {
    val timeoutPerQuestion = level match {
      case "hard" => Some(15)
      case "medium" => Some(30)
      case _ => None
    }

    val totalTimeout = level match {
      case "hard" => Some(1500)
      case "medium" => Some(3000)
      case _ => None
    }

    val gs = GameSession("test", login, new AtomicInteger(0), 20, now(), 0, level)

    if (gameSessions.exists(x => x.login == login)) {
      Json.obj("error" -> "You already started a session")
    } else {
      gameSessions = gameSessions :+ gs
      Json.obj("session" -> gs.id)
    }

  }


  def checkAnswer(userAnswer: Int, gameSessionId: String): JsObject = {
    // TODO : Update game session score
    val proposal = pendingQuestions.find(x => x.gameSessionId == gameSessionId)
    val gs = gameSessions
      .find(x => x.id == gameSessionId)
      .getOrElse(throw new NoGameStarted())

    // TODO : Add timeout check
    val response = if (proposal.isDefined && proposal.get.expiration.isEmpty)
      Json.obj("correct" -> true, "message" -> "Bonne reponse")
    else
      Json.obj("correct" -> false, "message" -> "Ce n'est pas le bon film !")

    pendingQuestions = pendingQuestions.filter(x => x.gameSessionId != gameSessionId)

    response
  }


  /**
    * Selects the 4 questions and the content of the answer for the client
    * @param initialPool : The pool to select from
    * @return A JsObject representing the answer
    */
  def getFinalPool(sessionId: String)(initialPool: Seq[JsObject]): Future[JsObject] = {
    // Now selecting the 4 movies and inside the 4 movies the answer
    val picked: Seq[JsObject] = for (i <- 0 to 8) yield initialPool(scala.util.Random.nextInt(initPoolSize))
    val answer = scala.util.Random.nextInt(picked.size)
    val answerId = (picked.head \ "id").as[Int]
    val cherryPicked = bsClient.getMovie(answerId)

    // Registering the question as pending
    // TODO : Add timeout depending of the game session
    val gs = gameSessions.find(x => x.id == sessionId).getOrElse(throw new NoGameStarted())
    val q = Question(answerId, sessionId, None)
    pendingQuestions = pendingQuestions :+ q

    Logger.debug(pendingQuestions.toString)

    // Returning the value for the user
    cherryPicked.map { gotten =>
      val answerText = (gotten \\ "synopsis").head.toString()

      // TODO : Throw exceptions in current impl, have to be correctly handled ( means make this compile )
      val getz = Try (answerText.substring(0, answerText.indexOf(" ", 255)).concat(" ...")) match {
          case Success(x) => x
          case Failure(x) => answerText
        }

      Json.obj(
        "questions" -> picked.map(x => Json.obj(
          "title" -> JsString((x \ "title").as[String]),
            "id" -> (x \ "id").as[Int]
          )),
          "answer" -> getz
        )
    }
  }
}
