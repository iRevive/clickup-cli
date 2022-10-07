package io.clickup

import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.TimeUnit

import cats.Monad
import cats.effect.{Async, Ref}
import cats.effect.std.Console
import cats.syntax.applicativeError.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.monadError.*
import cats.syntax.traverse.*
import fs2.{Pipe, Stream}
import fs2.io.file.{Files, Flags, NoSuchFileException, Path}
import io.circe.{Decoder, DecodingFailure, Encoder, Json, Printer}
import io.circe.generic.auto.*
import io.clickup.api.ApiToken
import io.clickup.model.TeamId
import io.clickup.util.Prompt

import scala.concurrent.duration.{Duration, FiniteDuration}

final case class Config(
    apiToken: ApiToken,
    teamId: TeamId,
    diffDelta: FiniteDuration,
    timezone: ZoneOffset
) derives Encoder.AsObject, Decoder

object Config {

  val DefaultZone: ZoneOffset = ZoneOffset.UTC

  given Encoder[FiniteDuration] = Encoder { duration =>
    import io.circe.syntax.*

    Json.obj("length" -> duration.length.asJson, "unit" -> duration.unit.name.asJson)
  }

  given Decoder[FiniteDuration] = Decoder { cursor =>
    for {
      length <- cursor.get[Long]("length")
      unit   <- cursor.get[String]("unit")
    } yield FiniteDuration(length, TimeUnit.valueOf(unit))
  }

  object Configure {

    private given Prompt.Read[ApiToken] = new Prompt.Read[ApiToken] {
      def read(input: String): Either[String, ApiToken] =
        Either.cond(
          input.startsWith("pk_"),
          ApiToken(input),
          "API token should be non-empty and start with `pk_` prefix"
        )
    }

    private given Prompt.Read[TeamId] = new Prompt.Read[TeamId] {
      def read(input: String): Either[String, TeamId] =
        input.toIntOption.filter(_ > 0).map(TeamId(_)).toRight("Team ID should be a positive number")
    }

    private given Prompt.Read[FiniteDuration] = new Prompt.Read[FiniteDuration] {
      def read(input: String): Either[String, FiniteDuration] =
        Either.catchNonFatal(Duration(input)) match {
          case Right(fd: FiniteDuration) => Right(fd)
          case Right(d)                  => Left("Duration must be finite")
          case Left(d)                   => Left("Cannot parse input as duration")
        }
    }

    def fromPrompt[F[_]: Monad: Console]: F[Config] =
      for {
        apiToken <- Prompt.readWithRetries[F, ApiToken]("ClickUp personal API token [string]: ")
        teamId   <- Prompt.readWithRetries[F, TeamId]("ClickUp Team ID [integer]: ")
        diffDelta <- Prompt.readWithRetries[F, FiniteDuration](
          "Time comparison: maximum allowed difference between time entries [duration]: "
        )
      } yield Config(apiToken, teamId, diffDelta, DefaultZone)

  }

  trait Source[F[_]] {
    def load: F[Config]
    def write(config: Config): F[Unit]
  }

  object Source {

    def cached[F[_]: Async](underlying: Source[F]): F[Source[F]] =
      for {
        current <- underlying.load
        ref     <- Ref[F].of(current)
      } yield new Source[F] {
        def load: F[Config]                = ref.get
        def write(config: Config): F[Unit] = underlying.write(config) >> ref.set(config)
      }

    def createIfMissing[F[_]: Async: Console](configPath: Path, underlying: Source[F]): Source[F] =
      new Source[F] {
        def load: F[Config] = underlying.load.recoverWith { case _: NoSuchFileException | _: DecodingFailure =>
          for {
            _      <- Console[F].println("Config file is missing or corrupted. Creating a new one.")
            config <- Config.Configure.fromPrompt[F]
            _      <- write(config)
            _      <- Console[F].println(s"Created new config at $configPath")
          } yield config
        }

        def write(config: Config): F[Unit] = underlying.write(config)
      }

    def default[F[_]: Async](configPath: Path): Source[F] =
      new Source[F] {
        private val createOrOverwriteFile: Pipe[F, Byte, Nothing] = bytes =>
          Stream.exec(configPath.parent.traverse_(Files[F].createDirectories)) ++
            bytes.through(Files[F].writeAll(configPath, Flags.Write))

        def load: F[Config] =
          for {
            content <- Files[F].readAll(configPath).through(fs2.text.utf8.decode).compile.string
            config  <- Async[F].fromEither(io.circe.parser.decode[Config](content))
          } yield config

        def write(config: Config): F[Unit] =
          Stream
            .emit(Encoder[Config].apply(config).printWith(Printer.spaces2.copy(colonLeft = "")))
            .through(fs2.text.utf8.encode[F])
            .through(createOrOverwriteFile)
            .compile
            .drain
      }

  }

}
