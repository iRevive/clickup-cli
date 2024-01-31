ThisBuild / scalaVersion               := "3.3.1"
ThisBuild / semanticdbEnabled          := true
ThisBuild / semanticdbVersion          := scalafixSemanticdb.revision
ThisBuild / githubWorkflowPublish      := Nil
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("21"))
ThisBuild / githubWorkflowOSes         := binariesMatrix.keys.toSeq
ThisBuild / githubWorkflowTargetTags  ++= Seq("v*")

ThisBuild / githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v")))
ThisBuild / githubWorkflowBuildPostamble :=
  binariesMatrix.toSeq.flatMap { case (os, binaryName) =>
    // val condition = s"startsWith(github.ref, 'refs/tags/v') && matrix.os == '$os'"
    val condition = s"matrix.os == '$os'"
    Seq(
      WorkflowStep.Sbt(
        List(s"generateNativeBinary ./$binaryName"),
        name = Some(s"Generate $os native binary"),
        cond = Some(condition)
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
  "ubuntu-latest" -> "clickup-cli-linux-x86_64",
  "macos-14"      -> "clickup-cli-macos-aarch64"
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
  .enablePlugins(BuildInfoPlugin)
  .settings(generateBinarySettings)
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
    libraryDependencies += "com.armanbilge" %%% "epollcat" % "0.1.6" // tcp for fs2
  )

lazy val generateBinarySettings = {
  val generateNativeBinary = inputKey[Unit]("Generate a native binary")

  Seq(
    generateNativeBinary := {
      val log    = streams.value.log
      val args   = sbt.complete.Parsers.spaceDelimited("<arg>").parsed
      val binary = (LocalProject("cliNative") / Compile / nativeLink).value
      val output = file(args.headOption.getOrElse("./clickup-cli"))

      log.info(s"Writing binary to $output")
      IO.delete(output)
      IO.copyFile(binary, output)
    }
  )
}

lazy val noPublishSettings = Seq(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false
)
