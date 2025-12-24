package graviton.arrow

import scala.annotation.targetName

final case class StateSlot[Label <: String & Singleton, A](label: Label, initial: () => A):
  def fresh(): A = initial()

object StateSlot:

  @targetName("make")
  def apply[Label <: String & Singleton](label: Label)[A](initial: => A)
  : StateSlot[Label, A] =
    new StateSlot(label, () => initial)