package models

import java.util.concurrent.atomic.AtomicInteger


case class GameSession (
                         id: String,
                         login: String,
                         score: AtomicInteger,
                         rowsLeft: Int,
                         start: Long,
                         end: Long,
                         difficulty: String
                       )

case class Question (answerId: Int, gameSessionId: String, expiration: Option[Int] = None)

class NoGameStarted(message: String = "No game started") extends Exception()

