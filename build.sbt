ThisBuild / scalaVersion                        := "3.3.1"
ThisBuild / semanticdbEnabled                   := true
ThisBuild / semanticdbVersion                   := scalafixSemanticdb.revision
ThisBuild / githubWorkflowPublish               := Nil
ThisBuild / githubWorkflowJavaVersions          := Seq(JavaSpec.temurin("21"))
ThisBuild / githubWorkflowOSes                  := binariesMatrix.keys.toSeq
ThisBuild / githubWorkflowTargetBranches        := Seq("**", "!update/**", "!pr/**")
ThisBuild / githubWorkflowTargetTags           ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v")))

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    commands = List("brew install sbt s2n utf8proc"),
    name = Some("Install sbt, s2n, utf8proc"),
    cond = Some("matrix.os == 'macos-14'")
  ),
  WorkflowStep.Run(
    commands = List(
      "sudo apt-get update && sudo apt-get install clang && /home/linuxbrew/.linuxbrew/bin/brew install s2n utf8proc"
    ),
    name = Some("Install s2n, utf8proc"),
    cond = Some("matrix.os == 'ubuntu-latest'")
  ),
  WorkflowStep.Run(
    commands = List(
      "clang --version && ld -ls2n"
    )
  )
)

ThisBuild / githubWorkflowBuildPostamble :=
  binariesMatrix.toSeq.flatMap { case (os, binaryName) =>
    // val condition = s"startsWith(github.ref, 'refs/tags/v') && matrix.os == '$os'"
    val condition = s"matrix.os == '$os'"
    Seq(
      WorkflowStep.Sbt(
        List(s"generateNativeBinary ./$binaryName"),
        name = Some(s"Generate $os native binary"),
        cond = Some(condition),
        env = Map(
          "SCALANATIVE_MODE" -> scala.scalanative.build.Mode.releaseFast.toString(),
          "SCALANATIVE_LTO"  -> scala.scalanative.build.LTO.thin.toString()
        )
      ),
      WorkflowStep.Sbt(
        List(s"generateNativeBinary ./$binaryName"),
        name = Some(s"Generate $os native binary"),
        cond = Some(condition),
        env = Map(
          "SCALANATIVE_MODE" -> scala.scalanative.build.Mode.releaseFast.toString(),
          "SCALANATIVE_LTO"  -> scala.scalanative.build.LTO.thin.toString()
        )
      )
      /*WorkflowStep.Use(
        UseRef.Public("ncipollo", "release-action", "v1"),
        name = Some(s"Upload $binaryName"),
        params = Map(
          "allowUpdates" -> "true",
          "artifacts"    -> binaryName
        ),
        cond = Some(condition)
      )*/
    )
  }

lazy val binariesMatrix = Map(
  "ubuntu-latest" -> "clickup-cli-linux-x86_64"
  // / "macos-14"      -> "clickup-cli-macos-aarch64"
)

lazy val root = project
  .in(file("."))
  .aggregate(cli.jvm, cli.native)
  .settings(name := "clickup-cli-root")
  .settings(noPublishSettings)

lazy val cli = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("./modules/cli"))
  .enablePlugins(BuildInfoPlugin, ScalaNativeBrewedConfigPlugin)
  .settings(
    name                := "clickup-cli",
    Compile / mainClass := Some("io.clickup.Main"),
    run / fork          := true,
    libraryDependencies ++= Seq(
      "org.typelevel"       %%% "cats-effect"         % "3.5.2",
      "com.monovore"        %%% "decline-effect"      % "2.4.1",
      "co.fs2"              %%% "fs2-io"              % "3.9.2",
      "org.gnieh"           %%% "fs2-data-csv"        % "1.9.1",
      "org.http4s"          %%% "http4s-core"         % "0.23.23",
      "org.http4s"          %%% "http4s-client"       % "0.23.23",
      "org.http4s"          %%% "http4s-ember-client" % "0.23.23",
      "org.http4s"          %%% "http4s-circe"        % "0.23.23",
      "org.polyvariant"     %%% "colorize"            % "0.3.2",
      "io.circe"            %%% "circe-parser"        % "0.14.6",
      "io.circe"            %%% "circe-generic"       % "0.14.6",
      "com.disneystreaming" %%% "weaver-cats"         % "0.8.3" % Test,
      "com.disneystreaming" %%% "weaver-scalacheck"   % "0.8.3" % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
    scalacOptions ++= Seq(
      "-source:future",
      "-no-indent", // let's be conservative for a while
      "-old-syntax",
      "-Yretain-trees"
    ),
    buildInfoPackage   := "io.clickup",
    buildInfoOptions   += sbtbuildinfo.BuildInfoOption.PackagePrivate,
    buildInfoKeys      := Seq[BuildInfoKey](version),
    nativeBrewFormulas := Set("s2n", "utf8proc")
  )
  .nativeSettings(
    libraryDependencies += "com.armanbilge" %%% "epollcat" % "0.1.6" // tcp for fs2
  )

lazy val cliNative            = cli.native
lazy val generateNativeBinary = inputKey[Unit]("Generate a native binary")
generateNativeBinary := {
  val log  = streams.value.log
  val args = sbt.complete.Parsers.spaceDelimited("<arg>").parsed
  log.info(s"Compiling binary as $args for ${sys.props.get("os.name")}")
  val binary = (cliNative / Compile / nativeLink).value
  val output = file(args.headOption.getOrElse("./clickup-cli"))

  log.info(s"Writing binary to $output")
  IO.delete(output)
  IO.copyFile(binary, output)
}

lazy val noPublishSettings = Seq(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false
)
