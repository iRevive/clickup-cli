package io.clickup.util

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

object time {

  private val formatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault)

  extension (instant: Instant) {
    def pretty: String = formatter.format(instant)
  }

  extension (duration: FiniteDuration) {
    def pretty: String = {
      val d = if (duration.length < 0) -duration else duration
      prettyRec(Nil, timeUnitList, d)
    }
  }

  private val timeUnitList: List[TimeUnit] =
    List(TimeUnit.HOURS, TimeUnit.MINUTES, TimeUnit.SECONDS, TimeUnit.MILLISECONDS)

  @tailrec
  private def prettyRec(acc: List[FiniteDuration], remUnits: List[TimeUnit], rem: FiniteDuration): String =
    remUnits match {
      case h :: t =>
        if (rem > FiniteDuration(1, h)) {
          val x = FiniteDuration(rem.toUnit(h).toLong, h)
          prettyRec(x :: acc, t, rem - x)
        } else {
          prettyRec(acc, t, rem)
        }
      case Nil =>
        acc.reverse.map(_.toString).mkString(" ")
    }

}
