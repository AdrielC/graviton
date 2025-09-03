package graviton.collections

/** Operations for types that have discrete successors and predecessors. */
trait Discrete[A]:
  def succ(x: A): A
  def pred(x: A): A

  /** Returns true if `x` and `y` are consecutive. */
  def adj(x: A, y: A): Boolean = succ(x) == y

  /** Inverts the direction of successor and predecessor. */
  def inverse: Discrete[A] =
    val self = this
    new Discrete[A]:
      def succ(x: A): A = self.pred(x)
      def pred(x: A): A = self.succ(x)

object Discrete:
  inline def apply[A](using d: Discrete[A]): Discrete[A] = d

  def inverse[A](d: Discrete[A]): Discrete[A] = d.inverse

  given integralDiscrete[I](using I: Integral[I]): Discrete[I] with
    import I.*
    def succ(x: I): I = x + I.one
    def pred(x: I): I = x - I.one
