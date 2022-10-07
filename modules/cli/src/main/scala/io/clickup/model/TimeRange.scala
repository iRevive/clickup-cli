package io.clickup.model

import java.time.{LocalDate, ZoneOffset}
import java.time.temporal.{TemporalAdjusters, WeekFields}
import java.util.Locale

enum TimeRange {
  case Quick(shortcut: TimeRange.Shortcut)
  case Custom(start: LocalDate, end: LocalDate)
}

object TimeRange {

  enum Shortcut(val value: String) {
    case ThisWeek  extends Shortcut("this-week")
    case LastWeek  extends Shortcut("last-week")
    case ThisMonth extends Shortcut("this-month")
    case LastMonth extends Shortcut("last-month")
  }

  /**
    * Returns start and end dates for the given range
    *
    * @param range the range to calculate the dates for
    * @param zoneOffset the offset to calculate the current date at
    */
  def dates(range: TimeRange, zoneOffset: ZoneOffset): (LocalDate, LocalDate) = {
    inline def now = LocalDate.now(zoneOffset)

    range match {
      case TimeRange.Quick(Shortcut.ThisWeek)  => weekRange(now)
      case TimeRange.Quick(Shortcut.LastWeek)  => weekRange(now.minusWeeks(1))
      case TimeRange.Quick(Shortcut.ThisMonth) => monthRange(now)
      case TimeRange.Quick(Shortcut.LastMonth) => monthRange(now.minusMonths(1))
      case TimeRange.Custom(start, end)        => (start, end)
    }
  }

  private def weekRange(date: LocalDate): (LocalDate, LocalDate) = {
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek
    val lastDayOfWeek  = firstDayOfWeek.plus(6)

    (
      date.`with`(TemporalAdjusters.previousOrSame(firstDayOfWeek)),
      date.`with`(TemporalAdjusters.nextOrSame(lastDayOfWeek))
    )
  }

  private def monthRange(date: LocalDate): (LocalDate, LocalDate) =
    (date.`with`(TemporalAdjusters.firstDayOfMonth()), date.`with`(TemporalAdjusters.lastDayOfMonth()))
}
