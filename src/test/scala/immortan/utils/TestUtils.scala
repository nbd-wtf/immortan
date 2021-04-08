package immortan.utils

import scala.annotation.tailrec

object TestUtils {
  @tailrec def WAIT_UNTIL_TRUE(condition: => Boolean, left: Int = 400): Unit = {
    val result = try condition catch { case _: Throwable => false }

    if (!result) {
      if (left >= 0) {
        this synchronized wait(50L)
        WAIT_UNTIL_TRUE(condition, left - 1)
      } else {
        throw new RuntimeException
      }
    }
  }

  @tailrec def WAIT_UNTIL_RESULT[T](provider: => T, left: Int = 400): T = {
    val result = try Some(provider) catch { case _: Throwable => None }

    result match {
      case Some(result) => result

      case None if left >= 0 =>
        this synchronized wait(50L)
        WAIT_UNTIL_RESULT(provider, left - 1)

      case None =>
        throw new RuntimeException
    }
  }
}
