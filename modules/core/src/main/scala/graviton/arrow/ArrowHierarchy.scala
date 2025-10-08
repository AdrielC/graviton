package graviton.arrow

/** Evidence that a result type has an absorbing value. */
trait BottomOf[A]:
  def value: A

object BottomOf:
  inline def apply[A](using bottom: BottomOf[A]): BottomOf[A] = bottom

  given unitBottom: BottomOf[Unit] with
    def value: Unit = ()

/** Minimal associative composition structure. */
trait AssociativeCompose[=>:[-_, +_]]:
  def compose[A, B, C](bc: B =>: C, ab: A =>: B): A =>: C

/** Category structure with an identity arrow. */
trait ComposeIdentity[=>:[-_, +_]] extends AssociativeCompose[=>:]:
  def identity[A]: A =>: A

/** Category with an absorbing "zero" arrow. */
trait ComposeZero[=>:[-_, +_]] extends ComposeIdentity[=>:]:
  def zero[A, B](using BottomOf[B]): A =>: B

/** Cartesian product-style composition for arrows. */
trait BothCompose[=>:[-_, +_]] extends ComposeIdentity[=>:]:

  type :*:[+_, +_]

  def fromFirst[A]: (A :*: Any) =>: A
  def fromSecond[B]: (Any :*: B) =>: B
  def toBoth[A, B, C](a2b: A =>: B)(a2c: A =>: C): A =>: (B :*: C)

/** Explicit parallel composition operating on independent inputs. */
trait ComposeParallel[=>:[-_, +_]] extends BothCompose[=>:]:
  def parallel[A, B, C, D](left: A =>: B, right: C =>: D): (A :*: C) =>: (B :*: D)

/** Sum (coproduct) support for arrows. */
trait EitherCompose[=>:[-_, +_]] extends ComposeIdentity[=>:]:

  type :+:[+_, +_]

  def inLeft[A, B]: A =>: (A :+: B)
  def inRight[A, B]: B =>: (A :+: B)
  def fromEither[A, B, C](left: => A =>: C)(right: => B =>: C): (A :+: B) =>: C

/** Aggregates the categorical structure required to interpret {@link FreeArrow} programs. */
trait ArrowBundle[=>:[-_, +_]] extends ComposeParallel[=>:] with EitherCompose[=>:] with ComposeZero[=>:]:

  def liftArrow[A, B](f: A => B): A =>: B

object ArrowBundle:

  type Aux[=>:[-_, +_], Prod[+_, +_], Sum[+_, +_]] = ArrowBundle[=>:] {
    type :*:[+l, +r] = Prod[l, r]
    type :+:[+l, +r] = Sum[l, r]
  }

  given functionBundle: ArrowBundle[Function] with
    type :*:[+l, +r] = (l, r)
    type :+:[+l, +r] = Either[l, r]

    override def compose[A, B, C](bc: B => C, ab: A => B): A => C = bc.compose(ab)

    override def identity[A]: A => A = (a: A) => a

    override def zero[A, B](using bottom: BottomOf[B]): A => B = _ => bottom.value

    override def fromFirst[A]: ((A, Any)) => A = _._1

    override def fromSecond[B]: ((Any, B)) => B = _._2

    override def toBoth[A, B, C](a2b: A => B)(a2c: A => C): A => (B, C) =
      a => (a2b(a), a2c(a))

    override def parallel[A, B, C, D](left: A => B, right: C => D): ((A, C)) => (B, D) = { case (a, c) => (left(a), right(c)) }

    override def inLeft[A, B]: A => Either[A, B] = Left(_)

    override def inRight[A, B]: B => Either[A, B] = Right(_)

    override def fromEither[A, B, C](left: => A => C)(right: => B => C): Either[A, B] => C = {
      case Left(a)  => left(a)
      case Right(b) => right(b)
    }

    override def liftArrow[A, B](f: A => B): A => B = f
