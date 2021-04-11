package immortan.utils

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}


object TestUtils {
  @tailrec def WAIT_UNTIL_TRUE(condition: => Boolean, left: Int = 100): Unit =
    Try(condition) match {
      case Success(true) =>

      case _ if left >= 0 =>
        TestUtils synchronized wait(50L)
        WAIT_UNTIL_TRUE(condition, left - 1)

      case Failure(exception) =>
        throw exception
    }

  @tailrec def WAIT_UNTIL_RESULT[T](provider: => T, left: Int = 100): T =
    Try(provider) match {
      case Success(result) => result

      case Failure(_) if left >= 0 =>
        TestUtils synchronized wait(50L)
        WAIT_UNTIL_RESULT(provider, left - 1)

      case Failure(exception) =>
        throw exception
    }
}
