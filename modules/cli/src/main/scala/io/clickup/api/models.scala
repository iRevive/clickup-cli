package io.clickup.api

import java.time.Instant

import io.circe.Decoder
import io.circe.generic.auto.*
import io.clickup.model.*

final case class Timestamp(raw: String, millis: Long, instant: Instant)

object Timestamp {
  given Decoder[Timestamp] = Decoder[String].emap { string =>
    for {
      millis <- string.toLongOption.toRight(s"Cannot decode [$string] as Long")
    } yield Timestamp(string, millis, Instant.ofEpochMilli(millis))
  }
}

final case class Task(
    id: TaskId,
    customId: Option[TaskId],
    name: String,
    status: Status,
    url: Option[TaskUrl]
) derives Decoder

final case class TimeEntry(
    id: String,
    task: Task,
    start: Timestamp,
    end: Timestamp,
    at: Timestamp,
    description: Option[String],
    task_url: TaskUrl
) derives Decoder

final case class TimeEntries(data: List[TimeEntry]) derives Decoder

final case class Status(
    status: String,
    color: String
)
