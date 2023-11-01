package io.clickup.timelog

import java.time.LocalDate

import cats.Semigroup
import cats.data.NonEmptyList
import cats.syntax.semigroup.*
import io.clickup.model.TaskId

import scala.concurrent.duration.FiniteDuration

object Comparison {

  enum Platform {
    case Clickup
    case Local
  }

  enum Log {
    case Single(taskId: TaskId, date: LocalDate, platform: Platform, duration: FiniteDuration)
    case Summed(taskId: TaskId, platform: Platform, duration: FiniteDuration)
  }

  enum Diff {
    case More(diff: FiniteDuration, clickup: Log.Summed, local: Log.Summed)
    case Less(diff: FiniteDuration, clickup: Log.Summed, local: Log.Summed)
    case Equal(delta: FiniteDuration, clickup: Log.Summed, local: Log.Summed)
    case Absent(taskId: TaskId, platform: Platform, duration: FiniteDuration)
  }

  enum DateSummary {
    case ClickupMissing(date: LocalDate, local: NonEmptyList[Log.Summed])
    case LocalMissing(date: LocalDate, clickup: NonEmptyList[Log.Summed])
    case Differences(date: LocalDate, diffs: NonEmptyList[Diff])
    case Empty(date: LocalDate)
  }

  def compareAll(
      start: LocalDate,
      end: LocalDate,
      durationEqualsDelta: FiniteDuration,
      clickupTimelogs: List[Timelog.Clickup],
      localTimelogs: List[Timelog.Local]
  ): List[DateSummary] = {

    val clickup: Map[LocalDate, NonEmptyList[Log.Summed]] =
      byDateMap(clickupTimelogs.map(log => Log.Single(log.taskId, log.date, Platform.Clickup, log.duration)))

    val local: Map[LocalDate, NonEmptyList[Log.Summed]] =
      byDateMap(localTimelogs.map(log => Log.Single(log.taskId, log.date, Platform.Clickup, log.duration)))

    LazyList
      .iterate(start)(_.plusDays(1))
      .takeWhile(d => d.isBefore(end) || d.isEqual(end))
      .map { date =>
        (clickup.get(date), local.get(date)) match {
          case (Some(clickup), Some(local)) =>
            DateSummary.Differences(date, NonEmptyList.fromListUnsafe(compare(local, clickup, durationEqualsDelta)))

          case (None, Some(local)) =>
            DateSummary.ClickupMissing(date, local)

          case (Some(clickup), None) =>
            DateSummary.LocalMissing(date, clickup)

          case (None, None) =>
            DateSummary.Empty(date)
        }
      }
      .toList
  }

  private def compare(
      local: NonEmptyList[Log.Summed],
      clickup: NonEmptyList[Log.Summed],
      eqDelta: FiniteDuration
  ): List[Diff] = {
    val (diffs, clickupLeftovers) =
      local.foldLeft((List.empty[Diff], clickup.toList)) { case ((diffs, clickup), timelog) =>
        val (diff, leftover) =
          findOne(clickup, timelog.taskId) match {
            case (Some(value), leftover) => (calculateDiff(value, timelog, eqDelta), leftover)
            case (None, _)               => (Diff.Absent(timelog.taskId, Platform.Clickup, timelog.duration), clickup)
          }

        (diffs :+ diff, leftover)
      }

    diffs ++ clickupLeftovers.map(timelog => Diff.Absent(timelog.taskId, Platform.Local, timelog.duration))
  }

  private def findOne(
      clickup: List[Log.Summed],
      searchId: TaskId
  ): (Option[Log.Summed], List[Log.Summed]) = {
    @annotation.tailrec
    def loop(
        input: List[Log.Summed],
        output: List[Log.Summed]
    ): (Option[Log.Summed], List[Log.Summed]) =
      input match {
        case head :: tail =>
          if (head.taskId == searchId) (Some(head), output ++ tail)
          else loop(tail, output :+ head)

        case Nil =>
          (None, output)
      }

    loop(clickup, Nil)
  }

  private def calculateDiff(clickup: Log.Summed, local: Log.Summed, eqDelta: FiniteDuration): Diff = {
    val delta        = clickup.duration - local.duration
    val deltaNanos   = delta.toNanos
    val eqDeltaNanos = eqDelta.toNanos

    if (deltaNanos.abs < eqDeltaNanos) Diff.Equal(delta, clickup, local)
    else if (deltaNanos > eqDeltaNanos) Diff.More(delta, clickup, local)
    else Diff.Less(delta, clickup, local)
  }

  private given Semigroup[Log.Summed] = (x, y) => x.copy(duration = x.duration + y.duration)

  private def byDateMap(logs: List[Log.Single]): Map[LocalDate, NonEmptyList[Log.Summed]] = {
    @annotation.tailrec
    def loop(
        input: List[Log.Single],
        acc: Map[LocalDate, Map[TaskId, Log.Summed]]
    ): Map[LocalDate, Map[TaskId, Log.Summed]] =
      input match {
        case head :: tail =>
          val date               = head.date
          val taskId             = head.taskId
          val summed: Log.Summed = Log.Summed(taskId, head.platform, head.duration)

          val next = acc.get(date).fold(Map(taskId -> summed)) { durations =>
            durations.updated(taskId, durations.get(taskId).fold(summed)(v => v |+| summed))
          }

          loop(tail, acc.updated(date, next))

        case Nil => acc
      }

    loop(logs, Map.empty).view.mapValues(map => NonEmptyList.fromListUnsafe(map.values.toList)).toMap
  }

}
