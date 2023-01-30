package io.clickup.timelog

import java.time.{Instant, LocalDate, LocalTime, ZoneOffset}
import java.time.format.DateTimeFormatter

import cats.syntax.either.*
import fs2.data.csv.{CellDecoder, DecoderError, DecoderResult, Row, RowDecoder}
import io.clickup.api.TimeEntry as ApiTimelog
import io.clickup.model.TaskId

import scala.concurrent.duration.*

enum Timelog {
  case Clickup(date: LocalDate, duration: FiniteDuration, title: String, description: Option[String], taskId: TaskId)
  case Local(date: LocalDate, duration: FiniteDuration, title: String, taskId: TaskId)
  case LocalDetailed(
      date: LocalDate,
      duration: FiniteDuration,
      start: Instant,
      end: Instant,
      title: String,
      taskId: TaskId,
      note: String
  )
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

    given RowDecoder[Local] = new RowDecoder[Local] {
      given CellDecoder[LocalDate] = CellDecoder.localDateDecoder(DateFormat)

      given CellDecoder[FiniteDuration] = new CellDecoder[FiniteDuration] {
        def apply(cell: String): DecoderResult[FiniteDuration] =
          Either
            .catchNonFatal {
              val Array(h, m, s) = cell.split(":").map(_.toInt)
              h.hours + m.minutes + s.seconds
            }
            .leftMap(e => DecoderError(s"Cannot decode [$cell] as hh:mm:ss", inner = e))
      }

      def apply(row: Row): DecoderResult[Local] =
        for {
          date     <- row.asAt[LocalDate](0)
          duration <- row.asAt[FiniteDuration](1)
          title    <- row.asAt[String](2)
          taskId   <- TaskId.fromString(title).leftMap(e => DecoderError(e))
        } yield Local(date, duration, title, taskId)
    }
  }

  object LocalDetailed {
    private val DateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    given RowDecoder[LocalDetailed] = new RowDecoder[LocalDetailed] {
      given CellDecoder[LocalDate] = CellDecoder.localDateDecoder(DateFormat)

      given CellDecoder[FiniteDuration] = new CellDecoder[FiniteDuration] {
        def apply(cell: String): DecoderResult[FiniteDuration] =
          Either
            .catchNonFatal {
              val Array(h, m, s) = cell.split(":").map(_.toInt)
              h.hours + m.minutes + s.seconds
            }
            .leftMap(e => DecoderError(s"Cannot decode [$cell] as hh:mm:ss", inner = e))
      }

      def apply(row: Row): DecoderResult[LocalDetailed] =
        for {
          date     <- row.asAt[LocalDate](0)
          duration <- row.asAt[FiniteDuration](1)
          title    <- row.asAt[String](2)
          start    <- row.asAt[Instant](3)
          end      <- row.asAt[Instant](4)
          note     <- row.asAt[String](5).orElse(Right(""))
          taskId   <- TaskId.fromString(title).leftMap(e => DecoderError(e))
        } yield LocalDetailed(date, duration, start, end, title, taskId, note)
    }
  }
}
