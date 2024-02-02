import scala.scalanative.build.{NativeConfig, Platform}

// based on https://github.com/indoorvivants/sn-bindgen/blob/main/build.sbt
object NativeConfigOpts {
  val llvmVersion = "17"

  def customize(conf: NativeConfig): NativeConfig = {
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
            Paths.get(s"/usr/local/opt/llvm@$llvmVersion"),
            Paths.get("/usr/local/opt/llvm"),
            Paths.get(s"/opt/homebrew/opt/llvm@$llvmVersion"),
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

}
