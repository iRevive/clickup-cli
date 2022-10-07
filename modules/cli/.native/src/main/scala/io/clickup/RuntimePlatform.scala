package io.clickup

import cats.effect.unsafe.IORuntime
import epollcat.unsafe.EpollRuntime

object RuntimePlatform {
  val default: IORuntime = EpollRuntime.global
}
