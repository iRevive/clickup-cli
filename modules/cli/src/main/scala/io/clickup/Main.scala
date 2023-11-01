package io.clickup

import cats.{MonadThrow, Parallel}
import cats.data.OptionT
import cats.effect.{Async, ExitCode, IO, Resource}
import cats.effect.std.Console
import cats.effect.unsafe.IORuntime
import cats.syntax.functor.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.{Files, Path}
import fs2.io.net.Network
import io.clickup.api.ApiClient

object Main extends CommandIOApp(
      name = "clickup-cli",
      header = "clickup-cli: Operate ClickUp from a terminal",
      version = BuildInfo.version
    ) {

  override protected def runtime: IORuntime = RuntimePlatform.default

  def main: Opts[IO[ExitCode]] =
    Choice.opts.map { choice =>
      makeProgram[IO].use(_.run(choice)).as(ExitCode.Success)
    }

  private def makeProgram[F[_]: Async: Parallel: Console: Network: Files]: Resource[F, Runner[F]] =
    for {
      configPath <- Resource.eval(configPath[F])
      configSource <- Resource.eval(
        Config.Source.cached(Config.Source.createIfMissing(configPath, Config.Source.default(configPath)))
      )
      api <- ApiClient.create[F]
      cli <- Resource.pure(new Cli[F](api, configSource))
    } yield Runner.create(cli)

  private def configPath[F[_]: MonadThrow]: F[Path] = {
    val xdgConfig = sys.env.get("XDG_CONFIG_HOME").map(Path(_))

    val homeConfig =
      for {
        home <- MonadThrow[F].fromEither(sys.env.get("HOME").toRight(new RuntimeException("HOME is not defined?")))
      } yield Path(home).resolve(".config")

    OptionT
      .fromOption[F](xdgConfig)
      .getOrElseF(homeConfig)
      .map(_.resolve("clickup-cli").resolve("config.json"))
  }
}
