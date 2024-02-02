ThisBuild / scalaVersion      := "3.3.1"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val root = project
  .in(file("."))
  .aggregate(cli.jvm, cli.native)
  .settings(name := "clickup-cli-root")
  .settings(noPublishSettings)

lazy val cli = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("./modules/cli"))
  .enablePlugins(BuildInfoPlugin)
  .nativeEnablePlugins(ScalaNativeBrewedConfigPlugin)
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
    buildInfoPackage := "io.clickup",
    buildInfoOptions += sbtbuildinfo.BuildInfoOption.PackagePrivate,
    buildInfoKeys    := Seq[BuildInfoKey](version)
  )
  .nativeSettings(
    nativeBrewFormulas  := Set("s2n", "utf8proc"),
    nativeConfig        ~= NativeConfigOpts.customize,
    libraryDependencies += "com.armanbilge" %%% "epollcat" % "0.1.6" // tcp for fs2
  )

lazy val cliNative            = cli.native
lazy val generateNativeBinary = inputKey[Unit]("Generate a native binary")
generateNativeBinary := {
  val log    = streams.value.log
  val args   = sbt.complete.Parsers.spaceDelimited("<arg>").parsed
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

lazy val llvmVersion  = NativeConfigOpts.llvmVersion
lazy val brewFormulas = Set("s2n", "utf8proc")
lazy val binariesMatrix = Map(
  "ubuntu-latest" -> "clickup-cli-linux-x86_64",
  "macos-14"      -> "clickup-cli-macos-aarch64"
)

ThisBuild / githubWorkflowJavaVersions          := Seq(JavaSpec.temurin("21"))
ThisBuild / githubWorkflowOSes                  := binariesMatrix.keys.toSeq
ThisBuild / githubWorkflowTargetBranches        := Seq("**", "!update/**", "!pr/**")
ThisBuild / githubWorkflowTargetTags           ++= Seq("v*")
ThisBuild / githubWorkflowPublish               := Nil
ThisBuild / githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v")))

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    commands = List(s"brew install sbt llvm@$llvmVersion ${brewFormulas.mkString(" ")}"),
    name = Some(s"Install sbt, llvm@$llvmVersion ${brewFormulas.mkString(", ")} (MacOS)"),
    cond = Some("startsWith(matrix.os, 'macos')")
  ),
  WorkflowStep.Run(
    commands = List(
      List(
        "sudo apt-get update",
        "sudo apt-get install clang",
        s"/home/linuxbrew/.linuxbrew/bin/brew install ${brewFormulas.mkString(" ")}"
      ).mkString(" && ")
    ),
    name = Some(s"Install ${brewFormulas.mkString(", ")} (Ubuntu)"),
    cond = Some("startsWith(matrix.os, 'ubuntu')")
  ),
  WorkflowStep.Run(
    commands = List(s"""echo "LLVM_BIN=/opt/homebrew/opt/llvm@$llvmVersion/bin" >> $$GITHUB_ENV"""),
    name = Some("Sets env vars for LLVM"),
    cond = Some("startsWith(matrix.os, 'macos')")
  )
)

ThisBuild / githubWorkflowBuildPostamble :=
  binariesMatrix.toSeq.flatMap { case (os, binaryName) =>
    import scala.scalanative.build.Mode

    val isTag       = "startsWith(github.ref, 'refs/tags/v')"
    val osCondition = s"matrix.os == '$os'"
    val completions = "./nix/completions.zsh"
    val artifacts   = s"$binaryName,$completions"

    Seq(
      WorkflowStep.Sbt(
        commands = List(s"generateNativeBinary ./$binaryName"),
        name = Some(s"Generate $os native binary"),
        cond = Some(osCondition),
        env = Map(
          "SCALANATIVE_MODE" -> s"$${{ $isTag && '${Mode.releaseFast}' || '${Mode.debug}' }}"
        )
      ),
      WorkflowStep.Use(
        ref = UseRef.Public("actions", "upload-artifact", "v4"),
        name = Some(s"Upload $binaryName"),
        cond = Some(osCondition),
        params = Map(
          "name"              -> binaryName,
          "path"              -> artifacts,
          "if-no-files-found" -> "error"
        )
      ),
      WorkflowStep.Use(
        UseRef.Public("ncipollo", "release-action", "v1"),
        name = Some(s"Attach $binaryName to a release"),
        cond = Some(s"$isTag && $osCondition"),
        params = Map(
          "allowUpdates" -> "true",
          "artifacts"    -> artifacts
        )
      )
    )
  }
