package services

import akka.actor.Status.Success
import com.google.inject.Inject
import javax.inject.Singleton
import models.{GameSession, Question}
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, JsString, Json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Try}

@Singleton
class GuessService @Inject() (bsClient: BetaSeries){

  val initPoolSize = 100

  val gameDifficutlies = Set("easy", "normal", "hard")

  // Contains all the running game sessions
  val gameSessions = Seq()

  // Contains all the pending questions of the session
  var pendingQuestions: Seq[Question] = Seq()

  /**
    * Gets the initial pool to choose later
    * @param goHard : The higher it gets the less popular the movies are (in a limit of 10kth less popular)
    * @return A seq[JsObject] containing all the pool
    */
  def getGuessPool(goHard: Int = 1): Future[Seq[JsObject]] = {
    val randOffset = scala.util.Random.nextInt(10000)
    bsClient.getInitMovies(initPoolSize, randOffset % (goHard * 200)).map(x => (x \ "movies").as[Seq[JsObject]])
  }


  def checkAnswer(userAnswer: Int, gameSessionId: String): JsObject = {
    // TODO : Update game session score
    val proposal = pendingQuestions.find(x => x.gameSessionId == gameSessionId)

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
    val q = Question(answerId, sessionId, None)

    pendingQuestions = pendingQuestions :+ q

    Logger.debug(pendingQuestions.toString)

    // Returning the value for the user
    cherryPicked.map { gotten =>
      val answerText = (gotten \\ "synopsis").head.toString()

      // TODO : Throw exceptions in current impl, have to be correctly handled ( means make this compile )
      val getz = Try[String] { answerText.substring(0, answerText.indexOf(" ", 255)).concat(" ...") } match {
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
