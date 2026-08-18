package graviton.arrow

import zio.prelude.AssociativeCompose
import zio.prelude.experimental.{BothCompose, EitherCompose}

/** Evidence that a result type has an absorbing value. */
trait BottomOf[+A]:
  def value: A

object BottomOf:
  inline def apply[A](using inline bottom: BottomOf[A]): BottomOf[A] = bottom

  transparent inline given unitBottom: BottomOf[Unit] = new:
    val value: Unit = ()

/** Category with an absorbing "zero" arrow. */
trait ComposeZero[=>:[-_, +_]] extends AssociativeCompose[=>:]:
  inline def zero[A, B](using BottomOf[B]): A =>: B

/** Explicit parallel composition operating on independent inputs. */
trait ComposeParallel[=>:[-_, +_]] extends BothCompose[=>:]:
  def parallel[A, B, C, D](left: A =>: B, right: C =>: D): (A :*: C) =>: (B :*: D)

/** Aggregates the categorical structure required to interpret {@link FreeArrow} programs. */
trait ArrowBundle[=>:[-_, +_]] extends ComposeParallel[=>:] with EitherCompose[=>:] with ComposeZero[=>:]:

  def liftArrow[A, B](f: A => B): A =>: B

  inline override def zero[A, B](using BottomOf[B]): A =>: B =
    liftArrow((_: A) => summon[BottomOf[B]].value)

end ArrowBundle

object ArrowBundle:

  type Aux[=>:[-_, +_], Prod[+_, +_], Sum[+_, +_]] = ArrowBundle[=>:] {
    type :*:[+l, +r] = Prod[l, r]
    type :+:[+l, +r] = Sum[l, r]
  }

  given functionBundle: ArrowBundle[Function] = new:

    def identity[A]: A => A = identityFunction.asInstanceOf[A => A]
    type :*:[+l, +r] = (l, r)
    type :+:[+l, +r] = Either[l, r]

    override def compose[A, B, C](bc: B => C, ab: A => B): A => C = bc.compose(ab)

    override def fromFirst[A]: ((A, Any)) => A = _._1

    override def fromSecond[B]: ((Any, B)) => B = _._2

    override def toBoth[A, B, C](a2b: A => B)(a2c: A => C): A => (B, C) =
      a => (a2b(a), a2c(a))

    override def parallel[A, B, C, D](left: A => B, right: C => D): ((A, C)) => (B, D) = { case (a, c) => (left(a), right(c)) }

    override def toLeft[A]: A => Either[A, Nothing] = Left(_)

    override def toRight[B]: B => Either[Nothing, B] = Right(_)

    override def fromEither[A, B, C](left: => A => C)(right: => B => C): Either[A, B] => C = {
      case Left(a)  => left(a)
      case Right(b) => right(b)
    }

    override def liftArrow[A, B](f: A => B): A => B = f

    private val identityFunction: Any => Any = a => a.asInstanceOf[Any]
