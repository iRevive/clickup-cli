package io.clickup.util

import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

object time {

  extension (duration: FiniteDuration) {
    def pretty: String = prettyRec(Nil, timeUnitList, duration)
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
