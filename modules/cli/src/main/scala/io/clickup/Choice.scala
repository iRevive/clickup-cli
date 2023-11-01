package io.clickup

import java.nio.file.Path as JPath
import java.time.{Instant, LocalDate}
import java.time.format.DateTimeFormatter

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.option.*
import cats.syntax.reducible.*
import com.monovore.decline.*
import io.clickup.model.{TaskId, TeamId, TimeRange}

import scala.concurrent.duration.*

enum Choice {
  case Task(op: Choice.TaskOp)
  case Timelog(op: Choice.TimelogOp)
  case Configure
}

object Choice {

  enum TaskOp {
    case Summary(taskIds: NonEmptyList[TaskId], detailed: Boolean)
  }

  enum TimelogOp {
    case List(range: TimeRange)
    case Add(taskId: TaskId, date: Instant, duration: FiniteDuration, description: String)
    case Summary(range: TimeRange, detailed: Boolean)
    case Compare(
        range: TimeRange,
        delta: Option[FiniteDuration],
        localLogs: JPath,
        skip: Option[Int],
        detailed: Boolean
    )
  }

  private val customRangeOpts: Opts[TimeRange.Custom] =
    (
      Opts.option[LocalDate]("start", "The start date. Format: yyyy-MM-dd. Example: 2022-10-01"),
      Opts.option[LocalDate]("end", "The end date. Format: yyyy-MM-dd. Example: 31-10-2022")
    ).mapN((start, end) => TimeRange.Custom(start, end))

  private val quickRangeOpts: Opts[TimeRange.Quick] =
    Opts
      .option[TimeRange.Shortcut](
        "range",
        s"The range shortcut. Valid values: ${TimeRange.Shortcut.values.map(_.value).mkString(", ")}"
      )
      .map(shortcut => TimeRange.Quick(shortcut))

  private val rangeOpts: Opts[TimeRange] =
    quickRangeOpts.orElse(customRangeOpts).withDefault(TimeRange.Quick(TimeRange.Shortcut.ThisMonth))

  private val detailedOpts: Opts[Boolean] =
    Opts.flag("detailed", "Whether to show the detailed information or not").orFalse

  private val taskSummaryOpts: Opts[TaskOp.Summary] =
    (
      Opts.options[TaskId]("task-id", "The ID of the task"),
      detailedOpts
    ).mapN((ids, detailed) => TaskOp.Summary(ids, detailed))

  private val timelogListOpts: Opts[TimelogOp.List] =
    rangeOpts.map(range => TimelogOp.List(range))

  private val timelogCompareOpts: Opts[TimelogOp.Compare] =
    (
      rangeOpts,
      Opts.option[FiniteDuration]("delta", "Maximum diff allowed").orNone,
      Opts.option[JPath]("local-logs", "The path to the CSV file with local time logs"),
      Opts.option[Int]("skip-lines", "How many lines to skip from the CSV file").orNone,
      detailedOpts
    ).mapN((range, delta, path, skip, detailed) => TimelogOp.Compare(range, delta, path, skip, detailed))

  private val timelogAddOpts: Opts[TimelogOp.Add] =
    (
      Opts.option[TaskId]("task-id", "The ID of the task"),
      Opts.option[Instant]("date", "The start date"),
      Opts.option[FiniteDuration]("duration", "The duration of the entry")(customDurationArg),
      Opts.option[String]("description", "The description of the entry")
    ).mapN((taskId, date, duration, description) => TimelogOp.Add(taskId, date, duration, description))

  private val timelogSummaryOpts: Opts[TimelogOp.Summary] =
    (rangeOpts, detailedOpts).mapN((range, detailed) => TimelogOp.Summary(range, detailed))

  val opts: Opts[Choice] =
    NonEmptyList
      .of[Opts[Choice]](
        Opts.subcommand("configure", "Configure click-up CLI")(Opts(Choice.Configure)),
        Opts.subcommand("task", "Task operations") {
          NonEmptyList
            .of[Opts[Choice.Task]](
              Opts.subcommand("summary", "Show tasks summary")(
                taskSummaryOpts.map(op => Choice.Task(op))
              )
            )
            .reduceK
        },
        Opts.subcommand("timelog", "Timelog operations") {
          NonEmptyList
            .of[Opts[Choice.Timelog]](
              Opts.subcommand("list", "List ClickUp time entries")(
                timelogListOpts.map(op => Choice.Timelog(op))
              ),
              Opts.subcommand("add", "Add time entry to ClickUp")(
                timelogAddOpts.map(op => Choice.Timelog(op))
              ),
              Opts.subcommand(
                "compare",
                """|Compare local time entries with the ClickUp ones.
                   |
                   |The CSV file format should be: yyyy-MM-dd,hh:mm:ss,task-id
                   |Example                      : 2022-10-03,1:22:46,TEST-123 - fix typo
                   |""".stripMargin
              )(
                timelogCompareOpts.map(op => Choice.Timelog(op))
              ),
              Opts.subcommand("summary", "Show ClickUp time summary")(
                timelogSummaryOpts.map(op => Choice.Timelog(op))
              )
            )
            .reduceK
        }
      )
      .reduceK

  private def customDurationArg: Argument[FiniteDuration] =
    new Argument[FiniteDuration] {
      def read(string: String): ValidatedNel[String, FiniteDuration] =
        string.split(":").toList.flatMap(_.toIntOption) match {
          case hh :: mm :: ss :: Nil =>
            Validated.Valid(hh.hours + mm.minutes + ss.seconds)

          case _ =>
            Validated.invalidNel(s"Cannot parse [$string] as a duration")
        }

      def defaultMetavar: String = "Format: hh:mm:ss. Example: 00:12:15"
    }

  given Argument[LocalDate] = new Argument[LocalDate] {
    private val formatPattern = "yyyy-MM-dd"
    private val format        = DateTimeFormatter.ofPattern(formatPattern)

    def read(string: String): ValidatedNel[String, LocalDate] =
      Either
        .catchNonFatal(LocalDate.parse(string, format))
        .leftMap(_ => s"Cannot parse [$string] as [$formatPattern]")
        .toValidatedNel

    def defaultMetavar: String = s"Format: $formatPattern. Example: 2022-10-01"
  }

  given Argument[TeamId] = new Argument[TeamId] {
    def read(string: String): ValidatedNel[String, TeamId] =
      string.toIntOption
        .filter(_ > 0)
        .toRight(s"Cannot parse [$string] as positive integer")
        .map(integer => TeamId(integer))
        .toValidatedNel

    def defaultMetavar: String = "A positive integer. Example: 1001"
  }

  given Argument[TaskId] = new Argument[TaskId] {
    def read(string: String): ValidatedNel[String, TaskId] =
      TaskId.fromString(string).toValidatedNel

    def defaultMetavar: String = "A custom task id. Example: DEV-123"
  }

  given Argument[Instant] = new Argument[Instant] {
    def read(string: String): ValidatedNel[String, Instant] =
      Either
        .catchNonFatal(Instant.parse(string))
        .leftMap(_ => s"Cannot parse [$string] as Instant")
        .toValidatedNel

    def defaultMetavar: String = "An ISO date time. Example: 2022-01-03T17:09:35.705186Z"
  }

  given Argument[TimeRange.Shortcut] = new Argument[TimeRange.Shortcut] {
    def read(string: String): ValidatedNel[String, TimeRange.Shortcut] =
      TimeRange.Shortcut.values.find(_.value === string).toValidNel(s"Cannot parse [$string] as range")

    def defaultMetavar: String = TimeRange.Shortcut.values.map(_.value).mkString(", ")
  }

}
