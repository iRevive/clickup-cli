package io.clickup

import cats.effect.unsafe.IORuntime

object RuntimePlatform {
  val default: IORuntime = IORuntime.global
}
