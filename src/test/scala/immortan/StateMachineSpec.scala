package immortan

import immortan.crypto.StateMachine
import org.scalatest.funsuite.AnyFunSuite

class StateMachineSpec extends AnyFunSuite {
 test("State mechine interval correctly works") {
   StateMachine.INTERVAL = 5

   var result: Any = null
   val sm = new StateMachine[String] {
     def doProcess(change: Any): Unit = result = change
   }

   assert(sm.secondsLeft == StateMachine.INTERVAL)
   sm.delayedCMDWorker.replaceWork("hi")
   synchronized(wait(3500L))
   assert(sm.secondsLeft == 2) // 2 seconds have passed
   sm.delayedCMDWorker.replaceWork("hi2")
   assert(sm.secondsLeft == StateMachine.INTERVAL) // Reset
   synchronized(wait(4000L))
   assert(result == null) // First assigned work was discarded, second one is not finished yet
   synchronized(wait(1500L))
   assert(result == "hi2") // Second work executed
 }
}
