package immortan.electrum

import castor._

case object PoisonPill

abstract class CastorStateMachineActorWithSetState[T]()(implicit ac: Context)
    extends StateMachineActor[T]() {
  var state0: State = null
  def setState(newState: State): Unit = {
    state0 = newState
  }
}

abstract class CastorStateMachineActorWithData[T, D]()(implicit ac: Context)
    extends SimpleActor[T]() {
  class State(run0: ((T, D)) => (State, D)) {
    def run = run0
  }
  protected[this] def initialState: State
  private[this] var state0: State = null
  protected[this] def state = {
    if (state0 == null) state0 = initialState
    state0
  }
  protected[this] def initialData: D
  private[this] var data0: D = initialData
  protected[this] def data = data0

  def run(msg: T): Unit = {
    val (nextState, nextData) = state.run((msg, data))
    state0 = nextState
    data0 = nextData
  }

  def setStateAndData(newState: State, newData: D): Unit = {
    state0 = newState
    data0 = newData
  }
}

abstract class CastorStateMachineActorWithState[T]()(implicit
    ac: Context
) extends SimpleActor[T]() {
  class State(run0: ((T, State)) => State) {
    def run = run0
  }
  protected[this] def initialState: State
  private[this] var state0: State = null
  protected[this] def state = {
    if (state0 == null) state0 = initialState
    state0
  }

  def run(msg: T): Unit = {
    state0 = state.run((msg, state0))
  }
}
