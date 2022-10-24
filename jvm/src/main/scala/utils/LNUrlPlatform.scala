package immortan.utils

import com.google.common.base.CharMatcher

object AsciiCharMatcher {
  def matches(char: Char): Boolean = CharMatcher.ascii.matches(char)
}
