package io.clickup

import java.nio.file.Path as JPath
import java.time.{Instant, LocalDate}

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.Console
import cats.syntax.applicative.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.monadError.*
import cats.syntax.parallel.*
import fs2.data.csv.{RowDecoder, lowlevel}
import fs2.io.file.{Files, Path}
import io.clickup.api.{ApiClient, ApiToken}
import io.clickup.model.{TaskId, TeamId, TimeRange}
import io.clickup.timelog.{Comparison, Report, Summary, Timelog}
import io.clickup.util.Prompt
import io.clickup.util.color.*
import io.clickup.util.time.*
import org.polyvariant.colorize.trueColor.*

import scala.concurrent.duration.*

class Cli[F[_]: Async: Parallel: Console: Files](api: ApiClient[F], configSource: Config.Source[F]) {

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
      localTimelogs <- loadLocal[Timelog.Local](localLogs, skip.getOrElse(0))
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

  def sync(
      range: TimeRange,
      delta: Option[FiniteDuration],
      localLogs: JPath,
      skip: Option[Int],
      confirmed: Boolean,
      dryRun: Boolean
  ): F[Unit] = {

    given Prompt.Read[Boolean] = new Prompt.Read[Boolean] {
      def read(input: String): Either[String, Boolean] =
        input.toLowerCase match {
          case "y" | "yes" | "true" => Right(true)
          case "n" | "no" | "false" => Right(false)
          case _                    => Left(s"Cannot decode [$input] as y/yes/true/n/no/false")
        }
    }

    given taskIdRead: Prompt.Read[Option[TaskId]] = new Prompt.Read[Option[TaskId]] {
      def read(input: String): Either[String, Option[TaskId]] =
        if (input.trim.isEmpty) Right(None) else TaskId.fromString(input).map(Some(_))
    }

    given finiteDurationRead: Prompt.Read[Option[FiniteDuration]] = new Prompt.Read[Option[FiniteDuration]] {
      def read(input: String): Either[String, Option[FiniteDuration]] =
        if (input.trim.isEmpty) Right(None)
        else {
          Either
            .catchNonFatal {
              val Array(h, m, s) = input.split(":").map(_.toInt)
              Option(h.hours + m.minutes + s.seconds)
            }
            .leftMap(_ => s"Cannot decode [$input] as hh:mm:ss")
        }
    }

    given textRead: Prompt.Read[Option[String]] = new Prompt.Read[Option[String]] {
      def read(input: String): Either[String, Option[String]] =
        if (input.trim.isEmpty) Right(None) else Right(Some(input))
    }

    given instantRead: Prompt.Read[Option[Instant]] = new Prompt.Read[Option[Instant]] {
      def read(input: String): Either[String, Option[Instant]] =
        if (input.trim.isEmpty) Right(None)
        else {
          Either
            .catchNonFatal(Option(Instant.parse(input)))
            .leftMap(_ => s"Cannot decode [$input] as Instant")
        }
    }

    def prettyTaskId(teamId: TeamId, taskId: TaskId): String =
      s"$taskId https://app.clickup.com/t/$teamId/$taskId"

    def update(timelog: Timelog.LocalDetailed, teamId: TeamId, token: ApiToken) =
      if (dryRun || confirmed) {
        val taskId   = timelog.taskId
        val start    = timelog.start
        val duration = timelog.duration
        val note     = timelog.note

        for {
          _ <- Console[F].println(
            s"[${timelog.date}] Importing task ${prettyTaskId(teamId, taskId)} at [${start.pretty}] as [${duration.pretty}] with note [$note]"
          )
          _ <- api.addTimeEntry(taskId, start, duration, note, teamId, token).unlessA(dryRun)
          _ <- Console[F].println(s"[${timelog.date}] Added to ${prettyTaskId(teamId, taskId)}")
        } yield ()
      } else {
        for {
          _           <- Console[F].println("")
          _           <- Console[F].println(s"[${timelog.date}] Add timelog [${timelog.title}] as:")
          taskIdOpt   <- Prompt.readWithRetries[F, Option[TaskId]](s"Task ID [${timelog.taskId}]: ")
          startOpt    <- Prompt.readWithRetries[F, Option[Instant]](s"Date [${timelog.start}]: ")
          durationOpt <- Prompt.readWithRetries[F, Option[FiniteDuration]](s"Duration [${timelog.duration.pretty}]: ")
          noteOpt     <- Prompt.readWithRetries[F, Option[String]](s"Note [${timelog.note}]: ")

          taskId   = taskIdOpt.getOrElse(timelog.taskId)
          start    = startOpt.getOrElse(timelog.start)
          duration = durationOpt.getOrElse(timelog.duration)
          note     = noteOpt.getOrElse(timelog.note)

          _ <- Console[F].println(
            s"Importing task ${prettyTaskId(teamId, taskId)} at [${start.pretty}] as [${duration.pretty}] with note [$note]"
          )
          _ <- api.addTimeEntry(taskId, start, duration, note, teamId, token)
          _ <- Console[F].println(s"Added to ${prettyTaskId(teamId, taskId)}")
        } yield ()
      }

    for {
      config       <- configSource.load
      (start, end) <- Async[F].pure(TimeRange.dates(range, config.timezone))

      _ <- Console[F].println("Running a dry-run sync").whenA(dryRun)

      _ <- Console[F]
        .println("You are running synchronization in the confirmed mode. Are you sure?")
        .whenA(confirmed && !dryRun)

      _ <- Prompt.readWithRetries[F, Boolean]("Sure [y/n]: ").whenA(confirmed && !dryRun)

      _             <- Console[F].println("Fetching local files")
      localTimelogs <- loadLocal[Timelog.LocalDetailed](localLogs, skip.getOrElse(0))
      _             <- Console[F].println("Fetching clickup logs")

      clickupTimelogs <- api
        .timeEntries(
          start.atStartOfDay(config.timezone).toInstant,
          end.atStartOfDay(config.timezone).toInstant,
          config.teamId,
          config.apiToken
        )
        .map(_.map(tl => Timelog.Clickup.fromApiTimelog(tl)))

      _ <-
        localTimelogs.groupBy(_.date).toList.sortBy(_._1).traverse_ { case (date, byDate) =>
          byDate.groupBy(_.taskId).toList.traverse_ { case (taskId, locals) =>
            clickupTimelogs.filter(c => c.taskId == taskId && c.date == date) match {
              case Nil =>
                locals.traverse_(local => update(local, config.teamId, config.apiToken))

              case clickup =>
                val clickupTotal = clickup.map(_.duration.toNanos).sum
                val localTotal   = locals.map(_.duration.toNanos).sum
                val diffNanos    = clickupTotal - localTotal
                val eqDeltaNanos = delta.getOrElse(config.diffDelta).toNanos

                val io =
                  for {
                    _ <- Console[F].println(
                      s"[$date] Timelogs for [${prettyTaskId(config.teamId, taskId)}] already exist: the delta diff is [${diffNanos.nanos.pretty}]"
                    )
                    _ <- Console[F].println("  Clickup entries:")
                    _ <- clickup.zipWithIndex.traverse_ { case (c, idx) =>
                      val note = c.description.filter(_.nonEmpty).fold("")(d => s" [$d]")
                      Console[F].println(
                        s"  ${idx + 1}) ${c.duration.pretty}$note"
                      )
                    }
                    _ <- Console[F].println("  Local entries:")
                    _ <- locals.zipWithIndex.traverse_ { case (c, idx) =>
                      val note = Option(c.note).filter(_.nonEmpty).fold("")(d => s" [$d]")
                      Console[F].println(
                        s"  ${idx + 1}) ${c.duration.pretty} at [${c.start.pretty}]$note"
                      )
                    }
                  } yield ()

                io.whenA(diffNanos.abs >= eqDeltaNanos)

            }
          }
        }
    } yield ()
  }

  def configure: F[Unit] =
    for {
      _      <- Console[F].println("Starting configuration process")
      config <- Config.Configure.fromPrompt[F]
      _      <- configSource.write(config)
    } yield ()

  private def loadLocal[A: RowDecoder](path: JPath, drop: Int): F[List[A]] =
    Files[F]
      .readAll(Path.fromNioPath(path))
      .through(fs2.text.utf8.decode)
      .through(lowlevel.rows[F, String]())
      .drop(drop)
      .through(lowlevel.decode[F, A])
      .compile
      .toList
}
