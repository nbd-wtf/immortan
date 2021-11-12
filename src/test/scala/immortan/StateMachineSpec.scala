package immortan

import immortan.crypto.StateMachine
import immortan.utils.Rx
import immortan.utils.TestUtils._
import org.scalatest.funsuite.AnyFunSuite


class StateMachineSpec extends AnyFunSuite {
  test("State machine interval correctly works") {

    var result: Any = null
    val sm = new StateMachine[String] {
      def doProcess(change: Any): Unit = result = change
      TOTAL_INTERVAL_SECONDS = 5
    }

    sm.delayedCMDWorker.replaceWork("hi")
    WAIT_UNTIL_TRUE(sm.secondsLeft == 2) // 2 seconds have passed
    sm.delayedCMDWorker.replaceWork("hi2")
    WAIT_UNTIL_TRUE(sm.secondsLeft == sm.TOTAL_INTERVAL_SECONDS) // Reset
    WAIT_UNTIL_TRUE(result == null) // First assigned work was discarded, second one is not finished yet
    WAIT_UNTIL_TRUE(result == "hi2") // Second work executed
  }

  test("Blocking observable") {
    var prev: Long = Long.MaxValue
    Rx.ioQueue.map(_ => { Thread.sleep(1000); true }).toBlocking.subscribe(_ => prev = System.currentTimeMillis())
    val next = System.currentTimeMillis()
    assert(next >= prev)
  }
}
