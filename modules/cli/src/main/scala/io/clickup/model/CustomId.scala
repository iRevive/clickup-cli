package io.clickup.model

import io.circe.Decoder

opaque type CustomId = String

object CustomId {
  given Decoder[CustomId] = Decoder.decodeString.map(value => value)
}
