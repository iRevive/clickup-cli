package io.clickup.timelog

import java.time.LocalDate
import java.time.temporal.ChronoUnit

import cats.Monad
import cats.Order
import cats.Semigroup
import cats.data.NonEmptyList
import cats.effect.std.Console
import cats.syntax.applicative.*
import cats.syntax.functor.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.reducible.*
import io.clickup.api.TimeEntries
import io.clickup.model.TeamId
import io.clickup.model.TaskId
import io.clickup.util.color.*
import io.clickup.util.time.*
import org.polyvariant.colorize.*

import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

object Summary {

  enum Mode(val detailed: Boolean) {
    case Short extends Mode(detailed = false)
    case Full  extends Mode(detailed = true)
  }

  object Mode {
    def fromBoolean(detailed: Boolean): Mode =
      if (detailed) Mode.Full else Mode.Short
  }

  def render[F[_]: Monad: Console](
      start: LocalDate,
      end: LocalDate,
      logs: List[Timelog.Clickup],
      teamId: TeamId,
      mode: Mode
  ): F[Unit] = {
    val timeRangeDays = ChronoUnit.DAYS.between(start, end) + 1

    NonEmptyList.fromList(logs) match {
      case Some(logs) =>
        val total = logs.reduceMap(_.duration)

        for {
          _ <- Console[F].println(s"Summary [$start -> $end] ($timeRangeDays days):")
          _ <- Console[F].println("")
          _ <- Console[F].println(colorize"You have reported ${total.pretty.cyan} in ${logs.size.toString.green} tasks")
          _ <- logTimeLogs[F](logs, teamId).whenA(mode.detailed)
        } yield ()

      case None =>
        Console[F].println(s"Summary [$start -> $end] ($timeRangeDays days): nothing found")
    }
  }

  private def logTimeLogs[F[_]: Monad: Console](logs: NonEmptyList[Timelog.Clickup], teamId: TeamId): F[Unit] =
    Console[F].println("Found time entries:") >> Console[F].println("") >> logs
      .groupMapReduce(_.taskId)(_.duration)
      .toList
      .sortBy(_._2)(Ordering[FiniteDuration].reverse)
      .traverse_ { case (taskId, duration) =>
        Console[F].println(s"Task [$taskId https://app.clickup.com/t/$teamId/$taskId] - ${duration.pretty}")
      }

  private given Semigroup[FiniteDuration] = (x, y) => x + y
}
