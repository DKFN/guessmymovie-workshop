package models

import java.util.concurrent.atomic.AtomicInteger
import play.api.libs.json._

import play.api.libs.json.{JsObject, Json}


case class GameSession (
                         id: String,
                         login: String,
                         var score: Int,
                         rowsLeft: Int,
                         start: Long,
                         end: Long,
                         difficulty: String
                       ) {

  implicit val gameSessionWrite = Json.writes[GameSession]
  implicit val gameSessionFormat = Json.format[GameSession]
  implicit val gameSessionRead = Json.reads[GameSession]

  def toJson: JsObject = {
    Json.toJson[GameSession](this).as[JsObject]
  }

}

case class Question (answerId: Int, gameSessionId: String, expiration: Option[Int] = None)

class NoGameStarted(message: String = "No game started") extends Exception()

