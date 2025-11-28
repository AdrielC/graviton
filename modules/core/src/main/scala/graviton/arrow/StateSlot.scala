package graviton.arrow

final case class StateSlot[Label <: String, A](label: String, initial: () => A):
  def fresh(): A = initial()

object StateSlot:

  inline def apply[Label <: String, A](label: String, initial: => A): StateSlot[Label, A] =
    new StateSlot(label, () => initial)
