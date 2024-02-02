import scala.scalanative.build.{NativeConfig, Platform}

ThisBuild / scalaVersion                        := "3.3.1"
ThisBuild / semanticdbEnabled                   := true
ThisBuild / semanticdbVersion                   := scalafixSemanticdb.revision
ThisBuild / githubWorkflowPublish               := Nil
ThisBuild / githubWorkflowJavaVersions          := Seq(JavaSpec.temurin("21"))
ThisBuild / githubWorkflowOSes                  := binariesMatrix.keys.toSeq
ThisBuild / githubWorkflowTargetBranches        := Seq("**", "!update/**", "!pr/**")
ThisBuild / githubWorkflowTargetTags           ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v")))

lazy val brewFormulas = Set("s2n", "utf8proc")
lazy val binariesMatrix = Map(
//  "ubuntu-latest" -> "clickup-cli-linux-x86_64",
  "macos-14" -> "clickup-cli-macos-aarch64"
//  "macos-12" -> "clickup-cli-macos-darwin64"
)
lazy val LLVM_VERSION = "17"

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    commands = List(s"brew install sbt llvm@$LLVM_VERSION ${brewFormulas.mkString(" ")}"),
    name = Some(s"Install sbt, llvm@$LLVM_VERSION ${brewFormulas.mkString(", ")} (MacOS)"),
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
    commands = List(s"""echo "LLVM_BIN=/opt/homebrew/opt/llvm@$LLVM_VERSION/bin" >> $$GITHUB_ENV"""),
    name = Some("Sets env vars for LLVM"),
    cond = Some("startsWith(matrix.os, 'macos')")
  )
)

ThisBuild / githubWorkflowBuildPostamble :=
  binariesMatrix.toSeq.flatMap { case (os, binaryName) =>
    import scala.scalanative.build._
    val isTag       = "startsWith(github.ref, 'refs/tags/v')"
    val osCondition = s"matrix.os == '$os'"

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
          "path"              -> binaryName,
          "if-no-files-found" -> "error"
        )
      ),
      WorkflowStep.Use(
        UseRef.Public("ncipollo", "release-action", "v1"),
        name = Some(s"Attach $binaryName to release"),
        cond = Some(s"$isTag && $osCondition"),
        params = Map(
          "allowUpdates" -> "true",
          "artifacts"    -> binaryName
        )
      )
    )
  }

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
    nativeBrewFormulas := Set("s2n", "utf8proc"),
    nativeConfig ~= usesLibClang
  )
  .nativeSettings(
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

// based on https://github.com/indoorvivants/sn-bindgen/blob/main/build.sbt
def usesLibClang(conf: NativeConfig) = {
  val libraryName = "clang"

  val (llvmInclude, llvmLib) = llvmFolder(conf.clang.toAbsolutePath)

  val arm64 =
    if (sys.props.get("os.arch").contains("aarch64")) Seq("-arch", "arm64") else Nil

  conf
    .withLinkingOptions(
      conf.linkingOptions ++
        Seq("-l" + libraryName) ++
        llvmLib.map("-L" + _) ++ arm64
    )
    .withCompileOptions(
      conf.compileOptions ++ llvmInclude.map("-I" + _) ++ arm64
    )
}

def llvmFolder(clangPath: java.nio.file.Path): (List[String], List[String]) = {
  import java.nio.file.Paths
  if (Platform.isMac) {
    val detected =
      sys.env
        .get("LLVM_BIN")
        .map(Paths.get(_))
        .map(_.getParent)
        .filter(_.toFile.exists)
        .toList

    val speculative =
      if (detected.isEmpty)
        List(
          Paths.get(s"/usr/local/opt/llvm@$LLVM_VERSION"),
          Paths.get("/usr/local/opt/llvm"),
          Paths.get(s"/opt/homebrew/opt/llvm@$LLVM_VERSION"),
          Paths.get("/opt/homebrew/opt/llvm")
        )
      else Nil

    val all = (detected ++ speculative).dropWhile(!_.toFile.exists())

    val includes = all
      .map(_.resolve("include"))
      .map(_.toAbsolutePath.toString)

    val lib = all
      .map(_.resolve("lib"))
      .map(_.toAbsolutePath.toString)

    includes -> lib
  } else {
    // <llvm-path>/bin/clang
    val realPath   = clangPath.toRealPath()
    val binFolder  = realPath.getParent
    val llvmFolder = binFolder.getParent

    if (llvmFolder.toFile.exists())
      List(llvmFolder.resolve("include").toString) -> List(llvmFolder.resolve("lib").toString)
    else
      Nil -> Nil
  }
}
