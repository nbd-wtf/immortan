package immortan

import immortan.utils.TestUtils._
import immortan.crypto.StateMachine
import org.scalatest.funsuite.AnyFunSuite


class StateMachineSpec extends AnyFunSuite {
 test("State mechine interval correctly works") {
   StateMachine.INTERVAL = 5

   var result: Any = null
   val sm = new StateMachine[String] {
     def doProcess(change: Any): Unit = result = change
   }

   WAIT_UNTIL_TRUE(sm.secondsLeft == StateMachine.INTERVAL)
   sm.delayedCMDWorker.replaceWork("hi")
   WAIT_UNTIL_TRUE(sm.secondsLeft == 2) // 2 seconds have passed
   sm.delayedCMDWorker.replaceWork("hi2")
   WAIT_UNTIL_TRUE(sm.secondsLeft == StateMachine.INTERVAL) // Reset
   WAIT_UNTIL_TRUE(result == null) // First assigned work was discarded, second one is not finished yet
   WAIT_UNTIL_TRUE(result == "hi2") // Second work executed
 }
}
