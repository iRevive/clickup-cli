package io.clickup.util

import cats.Show

object color {

  extension (string: String) {
    inline def cyan: String    = withColor(Console.CYAN)
    inline def green: String   = withColor(Console.GREEN)
    inline def red: String     = withColor(Console.RED)
    inline def magenta: String = withColor(Console.MAGENTA)
    inline def yellow: String  = withColor(Console.YELLOW)

    inline def hexColor(hex: String): String = {
      val i = Integer.decode(hex).intValue()
      rgb((i >> 16) & 0xFF, (i >> 8) & 0xFF, i & 0xFF)
    }

    inline def rgb(red: Int, green: Int, blue: Int): String =
      s"\u001b[38;2;$red;$green;${blue}m$string\u001b[0m"

    private inline def withColor(color: String) = color + string + Console.RESET
  }

  extension [A](a: A) {
    inline def cyan(using Show[A]): String    = Show[A].show(a).cyan
    inline def green(using Show[A]): String   = Show[A].show(a).green
    inline def red(using Show[A]): String     = Show[A].show(a).red
    inline def magenta(using Show[A]): String = Show[A].show(a).magenta
    inline def yellow(using Show[A]): String  = Show[A].show(a).yellow
  }

}
