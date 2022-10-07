package io.clickup.timelog

import cats.Monad
import cats.effect.std.Console
import cats.syntax.applicative.*
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.reducible.*
import cats.syntax.traverse.*
import io.clickup.model.{TaskId, TeamId}

object Report {

  def printSummary[F[_]: Monad: Console](
      summary: Comparison.DateSummary,
      teamId: TeamId,
      detailed: Boolean
  ): F[Unit] = {
    import Comparison.{DateSummary, Diff}

    def prettyTaskId(taskId: TaskId): String =
      s"$taskId https://app.clickup.com/t/$teamId/$taskId"

    summary match {
      case DateSummary.ClickupMissing(date, logs) =>
        Console[F].println(s"[$date] has missing ClickUp entries:") >> logs.traverse_ {
          case Comparison.Log.Summed(taskId, platform, duration) =>
            Console[F].println(
              s"  ❓   Task [${prettyTaskId(taskId)}] with duration of [${duration.toMinutes} minutes] is absent in $platform"
            )
        }

      case DateSummary.LocalMissing(date, logs) =>
        Console[F].println(s"[$date] has missing local entries:") >> logs.traverse_ {
          case Comparison.Log.Summed(taskId, platform, duration) =>
            Console[F].println(
              s"  ❓   Task [${prettyTaskId(taskId)}] with duration of [${duration.toMinutes} minutes] is absent in $platform"
            )
        }

      case DateSummary.Differences(date, diffs) =>
        val onlyEquals = diffs.collect { case _: Diff.Equal =>
          true
        }.length === diffs.length

        (Console[F].println(s"[$date] has differences:") >> diffs.traverse_ {
          case Diff.More(diff, left, _) =>
            Console[F].println(
              s"  ⬆️    Task [${prettyTaskId(left.taskId)}] has more time in ClickUp. Diff [${diff.toSeconds} seconds]"
            )

          case Diff.Less(diff, left, _) =>
            Console[F].println(
              s"  ⬇️    Task [${prettyTaskId(left.taskId)}] has less time in ClickUp. Diff [${diff.toSeconds} seconds]"
            )

          case Diff.Equal(delta, left, _) =>
            Console[F]
              .println(
                s"  ✅   Task [${prettyTaskId(left.taskId)}] is in sync. Diff delta [${delta.toSeconds} seconds]"
              )
              .whenA(detailed)

          case Diff.Absent(taskId, platform, duration) =>
            Console[F].println(
              s"  ❓   Task [${prettyTaskId(taskId)}] with duration of [${duration.toMinutes} minutes] is absent in $platform"
            )
        }).whenA(detailed || !onlyEquals)

      case DateSummary.Empty(_) =>
        Monad[F].unit
    }
  }
}
