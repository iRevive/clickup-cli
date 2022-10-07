package io.clickup.model

import io.circe.{Decoder, Encoder}

opaque type TeamId = Int

object TeamId {
  def apply(value: Int): TeamId = value

  extension (teamId: TeamId) {
    def asInt: Int = teamId
  }

  given Encoder[TeamId] = Encoder.encodeInt.contramap(value => value)

  given Decoder[TeamId] = Decoder.decodeInt.map(value => value)
}
