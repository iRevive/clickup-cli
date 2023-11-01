package io.clickup.util

import org.polyvariant.colorize.*
import org.polyvariant.colorize.string.ColorizedString

object color {
  extension (string: String) {
    inline def hexColor(hex: String): ColorizedString = {
      val i = Integer.decode(hex).intValue()
      string.rgb((i >> 16) & 0xFF, (i >> 8) & 0xFF, i & 0xFF)
    }
  }
}
