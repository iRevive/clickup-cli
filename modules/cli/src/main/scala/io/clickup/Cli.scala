package io.clickup

import java.nio.file.Path as JPath
import java.time.{Instant, LocalDate}
import java.time.temporal.ChronoUnit

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.Console
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.monadError.*
import cats.syntax.parallel.*
import fs2.data.csv.lowlevel
import fs2.io.file.{Files, Path}
import io.clickup.api.ApiClient
import io.clickup.model.{TaskId, TimeRange}
import io.clickup.timelog.{Comparison, Report, Summary, Timelog}
import io.clickup.util.Prompt
import io.clickup.util.color.*
import io.clickup.util.time.*
import org.polyvariant.colorize.trueColor.*

import scala.concurrent.duration.FiniteDuration

class Cli[F[_]: Async: Parallel: Console](api: ApiClient[F], configSource: Config.Source[F]) {

  def taskSummary(ids: NonEmptyList[TaskId], detailed: Boolean): F[Unit] =
    for {
      config <- configSource.load
      _ <- ids.parTraverse_ { taskId =>
        api
          .taskInfo(taskId, config.teamId, config.apiToken)
          .redeemWith(
            error => Console[F].println(s"Task [$taskId] - cannot fetch info due to ${error.getMessage}"),
            task => {
              val status = task.status.status.hexColor(task.status.color)

              Console[F].println(
                colorize"Task [${taskId.toString.cyan}${task.url.fold("")(r => s" $r")}] - $status - ${task.name}".render
              )
            }
          )
      }
    } yield ()

  def listTimeEntries(range: TimeRange): F[Unit] =
    for {
      config       <- configSource.load
      (start, end) <- Async[F].pure(TimeRange.dates(range, config.timezone))
      timelog <- api.timeEntries(
        start.atStartOfDay(config.timezone).toInstant,
        end.atStartOfDay(config.timezone).toInstant,
        config.teamId,
        config.apiToken
      )
      _ <- Console[F].println("Found time entries: ")
      logs = timelog.map(log => Timelog.Clickup.fromApiTimelog(log))
      _ <- logs.groupBy(_.date).toList.sortBy(_._1).traverse_ { case (date, entries) =>
        Console[F].println(s"$date: ${entries.map(_.duration).reduce(_ + _).pretty}") >> entries
          .groupBy(_.taskId)
          .toList
          .traverse_ { case (taskId, taskEntries) =>
            val max = taskEntries.map(entry => entry.duration.pretty).map(_.length).max
            Console[F].println(
              s"\n  Task [$taskId]: ${taskEntries.map(_.duration).reduce(_ + _).pretty}"
            ) >> taskEntries.sortBy(_.duration).zipWithIndex.traverse_ { case (entry, idx) =>
              val pretty = entry.duration.pretty
              val pad    = " " * math.max(0, max - pretty.length)
              Console[F].println(
                s"    ${idx + 1}) ${entry.duration.pretty}$pad - ${entry.description.getOrElse("")}"
              )
            }
          } >> Console[F].println("")
      }
    } yield ()

  def summary(range: TimeRange, detailed: Boolean): F[Unit] =
    for {
      config       <- configSource.load
      (start, end) <- Async[F].pure(TimeRange.dates(range, config.timezone))
      entries <- api.timeEntries(
        start.atStartOfDay(config.timezone).toInstant,
        end.atStartOfDay(config.timezone).toInstant,
        config.teamId,
        config.apiToken
      )
      logs <- Async[F].pure(entries.map(entry => Timelog.Clickup.fromApiTimelog(entry)))
      _    <- Summary.render(start, end, logs, config.teamId, Summary.Mode.fromBoolean(detailed))
    } yield ()

  def addTimeEntry(taskId: TaskId, date: Instant, duration: FiniteDuration, description: String): F[Unit] =
    for {
      config <- configSource.load
      _      <- api.addTimeEntry(taskId, date, duration, description, config.teamId, config.apiToken)
      _      <- Console[F].println("Time entry added successfully")
    } yield ()

  def compareTimelog(
      range: TimeRange,
      delta: Option[FiniteDuration],
      localLogs: JPath,
      skip: Option[Int],
      detailed: Boolean
  ): F[Unit] =
    for {
      config        <- configSource.load
      (start, end)  <- Async[F].pure(TimeRange.dates(range, config.timezone))
      _             <- Console[F].println("Fetching local files")
      localTimelogs <- loadLocal(localLogs, skip.getOrElse(0))
      _             <- Console[F].println("Fetching clickup logs")
      clickupTimelogs <- api.timeEntries(
        start.atStartOfDay(config.timezone).toInstant,
        end.atStartOfDay(config.timezone).toInstant,
        config.teamId,
        config.apiToken
      )
      summaries <- Async[F].pure(
        Comparison.compareAll(
          start,
          end,
          delta.getOrElse(config.diffDelta),
          clickupTimelogs.map(tl => Timelog.Clickup.fromApiTimelog(tl)),
          localTimelogs
        )
      )

      clickupMissing = summaries
        .collect { case Comparison.DateSummary.ClickupMissing(_, logs) =>
          logs.map(_.duration).reduce(_ + _)
        }
        .reduceOption(_ + _)

      localMissing = summaries
        .collect { case Comparison.DateSummary.LocalMissing(_, logs) =>
          logs.map(_.duration).reduce(_ + _)
        }
        .reduceOption(_ + _)

      _ <- clickupMissing.foldMapA(s => Console[F].println(s"ClickUp total missing hours ${s.pretty}"))
      _ <- localMissing.foldMapA(s => Console[F].println(s"Local total missing hours ${s.pretty}"))

      _ <- summaries.traverse_(summary => Report.printSummary(summary, config.teamId, detailed))
    } yield ()

  def configure: F[Unit] =
    for {
      _      <- Console[F].println("Starting configuration process")
      config <- Config.Configure.fromPrompt[F]
      _      <- configSource.write(config)
    } yield ()

  private def loadLocal(path: JPath, drop: Int): F[List[Timelog.Local]] =
    Files[F]
      .readAll(Path.fromNioPath(path))
      .through(fs2.text.utf8.decode)
      .through(lowlevel.rows[F, String]())
      .drop(drop)
      .through(lowlevel.decode[F, Timelog.Local])
      .compile
      .toList
}
