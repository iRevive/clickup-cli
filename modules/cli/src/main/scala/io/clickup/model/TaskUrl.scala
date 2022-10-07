package io.clickup.model

import io.circe.Decoder

opaque type TaskUrl = String

object TaskUrl {

  extension (taskUrl: TaskUrl) {
    def asString: String = taskUrl
  }

  given Decoder[TaskUrl] = Decoder.decodeString.map(value => value)
}
