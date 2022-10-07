package io.clickup.model

import io.circe.Decoder

opaque type TaskName = String

object TaskName {
  given Decoder[TaskName] = Decoder.decodeString.map(value => value)
}
