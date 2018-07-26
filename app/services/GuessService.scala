package services

import java.sql.Timestamp

import com.google.inject.Inject
import javax.inject.Singleton
import models.{GameSession, NoGameStarted, Question}
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Random, Success, Try}

@Singleton
class GuessService @Inject() (bsClient: BetaSeries){

  val initPoolSize = 100
  val increment = 10

  val gameDifficutlies = Set("easy", "normal", "hard")
  val noGameResponse: JsObject = Json.obj("error" -> "Not in a game").as[JsObject]
  val unkownErrorResponse: JsObject = Json.obj("error" -> "Unkown error").as[JsObject]
  val gameAlreadyStarted: JsObject = Json.obj("error" -> "Game already started").as[JsObject]
  val tooLate: JsObject = Json.obj("error" -> "Too late ! Game over.").as[JsObject]
  val correctAnswer: JsObject = Json.obj("correct" -> true, "message" -> "Bonne reponse")
  val badAnswer = Json.obj("correct" -> false, "message" -> "Ce n'est pas le bon film !")

  // Contains all the running game sessions
  var gameSessions: Seq[GameSession] = Seq()

  // Contains all the pending questions of the session
  var pendingQuestions: Seq[Question] = Seq()

  def now(): Long = new Timestamp(System.currentTimeMillis).getTime

  /**
    * Gets the initial pool to choose later
    * @param goHard : The higher it gets the less popular the movies are (in a limit of 10kth less popular)
    * @return A seq[JsObject] containing all the pool
    */
  def getGuessPool(goHard: Int = 1): Future[Seq[JsObject]] = {
    val randOffset = scala.util.Random.nextInt(10000)
    bsClient.getInitMovies(initPoolSize, randOffset % (goHard * 200))
      .map(x => (x \ "movies").as[Seq[JsObject]])
      .map(Random.shuffle(_))
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

    def getSeed: () => String = () => {
      val maybeSessionSeed = (1 to 64).map(x => Random.nextPrintableChar).filter(x => x.isLetterOrDigit).mkString
      if (gameSessions.exists(x => x.id == maybeSessionSeed)) getSeed(): String else maybeSessionSeed
    }

    val gs = GameSession(getSeed(), login, 0, 20, now(), 0, level)

    if (gameSessions.exists(x => x.login == login)) {
      gameAlreadyStarted
    } else {
      gameSessions = gameSessions :+ gs
      Json.obj("session" -> gs.id)
    }

  }

  def getGameSession(gameSessionId: String): Future[JsObject] = {
    val maybeGs = gameSessions.find(x => x.id == gameSessionId)
    returnHandlingErrors(() => Future.successful(if (maybeGs.isDefined) maybeGs.get.toJson else JsObject.empty), maybeGs)
  }


  def checkAnswer(userAnswer: Int, gameSessionId: String): Future[JsObject] = {
    val proposal = pendingQuestions.find(x => x.gameSessionId == gameSessionId)
    val gs = gameSessions
      .find(x => x.id == gameSessionId)

    val checkProposal = (p: Option[Question]) => p.get.expiration.isEmpty && p.get.answerId == userAnswer

    // TODO : Add timeout check
    val checkAnswer: Option[Question] => Future[JsObject] = (p: Option[Question]) => Future.successful(p match {
      case Some(q: Question) => if (checkProposal(proposal)) correctAnswer else badAnswer
      case _ => Json.obj("error" -> "Do you have a pending question ?")
    })

    val response: Future[JsObject] = returnHandlingErrors(() => checkAnswer(proposal), gs)
    pendingQuestions = pendingQuestions.filter(x => x.gameSessionId != gameSessionId)
    gs.map(x => x.score + 10)
    Logger.debug(pendingQuestions.toString)
    response
  }

  /**
    * Selects the 4 questions and the content of the answer for the client
    * @param initialPool : The pool to select from
    * @return A JsObject representing the answer
    */
  def getFinalPool(sessionId: String)(initialPool: Seq[JsObject]): Future[JsValue] = {
    // Now selecting the 4 movies and inside the 4 movies the answer
    val picked: Seq[JsObject] = for (i <- 0 to 8) yield initialPool(scala.util.Random.nextInt(initPoolSize))
    val answer = scala.util.Random.nextInt(picked.size)
    val answerId = (picked.head \ "id").as[Int]
    val cherryPicked = bsClient.getMovie(answerId)

    // Registering the question as pending
    // TODO : Add timeout depending of the game session
    val maybeGs = gameSessions.find(x => x.id == sessionId)
    val q = Question(answerId, sessionId, None)
    pendingQuestions = pendingQuestions :+ q

    Logger.debug(pendingQuestions.toString)

    // Returning the value for the user
    val finalResult = () => cherryPicked.map { gotten =>
      val answerText = (gotten \\ "synopsis").head.toString()
      Json.obj(
        "questions" -> Random.shuffle(picked.map(x => Json.obj(
          "title" -> JsString((x \ "title").as[String]),
            "id" -> (x \ "id").as[Int]
          ))),
          "answer" -> trySmashString(answerText)
        )
    }
    returnHandlingErrors(finalResult, maybeGs)
  }

  private def returnHandlingErrors(callback: () => Future[JsObject], gs: Option[GameSession]) = {
    gs match {
      case Some(g) => callback()
      case None => Future.successful(noGameResponse)
      case _ => Future.successful(unkownErrorResponse)
    }
  }

  private def trySmashString(answerText: String) =
    Try (answerText.substring(0, answerText.indexOf(" ", 255)).concat(" ...")) match {
      case Success(x) => x
      case Failure(x) => answerText
    }
}
