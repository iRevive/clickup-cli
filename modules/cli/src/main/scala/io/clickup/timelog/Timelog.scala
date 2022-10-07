package io.clickup.timelog

import java.time.{LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter

import io.clickup.api.TimeEntry as ApiTimelog
import io.clickup.model.TaskId

import scala.concurrent.duration.*

enum Timelog {
  case Clickup(date: LocalDate, duration: FiniteDuration, title: String, description: Option[String], taskId: TaskId)
  case Local(date: LocalDate, duration: FiniteDuration, title: String, taskId: TaskId)
}

object Timelog {

  object Clickup {
    def fromApiTimelog(timelog: ApiTimelog): Timelog.Clickup =
      Timelog.Clickup(
        timelog.start.instant.atZone(ZoneOffset.UTC).toLocalDate,
        java.time.Duration.between(timelog.start.instant, timelog.end.instant).toNanos.nanos,
        timelog.task.name,
        timelog.description,
        TaskId.fromUrl(timelog.task_url).orElse(timelog.task.customId).getOrElse(timelog.task.id)
      )
  }

  object Local {
    private val DateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    def fromCSVLine(input: String): Timelog.Local = {
      val Array(date, duration, title) = input.split(",").map(_.trim)
      val Array(h, m, s)               = duration.split(":").map(_.toInt)

      Timelog.Local(
        LocalDate.parse(date, DateFormat),
        h.hours + m.minutes + s.seconds,
        title,
        TaskId.fromString(title).fold(sys.error, identity)
      )
    }
  }
}
