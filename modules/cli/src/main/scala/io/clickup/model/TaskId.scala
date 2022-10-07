package io.clickup.model

import cats.Order
import cats.syntax.contravariant.*
import io.circe.{Decoder, Encoder}

opaque type TaskId = String

object TaskId {
  private val TaskIdRegex    = "^([\\w]+-[\\d]+)".r
  private val UrlTaskIdRegex = "/([\\w]+-[\\d]+)$".r

  def fromString(input: String): Either[String, TaskId] =
    TaskIdRegex.findFirstIn(input) match {
      case Some(taskId) => Right(taskId)
      case None         => Left(s"Cannot parse task id from [$input]")
    }

  def fromUrl(url: TaskUrl): Option[TaskId] =
    UrlTaskIdRegex.findFirstIn(url.asString).map(taskId => taskId.drop(1))

  given Encoder[TaskId] = Encoder.encodeString.contramap(value => value)
  given Decoder[TaskId] = Decoder.decodeString.map(value => value)
  given Order[TaskId]   = (x, y) => x.compareTo(y)
}
