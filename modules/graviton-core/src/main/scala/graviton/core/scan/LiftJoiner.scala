package graviton.core.scan

import zio.*

/**
 * Lift from a lower capability arrow to a higher one.
 *
 * Functorial laws:
 * - lift(id) = id
 * - lift(g ∘ f) = lift(g) ∘ lift(f)
 *
 * Hierarchy:
 * - Pure (Function1) is the base
 * - KleisliZIO adds effects (ZIO[R, E, *])
 * - RWSTZIO adds state and writer
 */
trait Lift[Low[_, _], High[_, _]]:
  def lift[A, B](f: Low[A, B]): High[A, B]

object Lift:
  
  def apply[Low[_, _], High[_, _]](using l: Lift[Low, High]): Lift[Low, High] = l
  
  /**
   * Identity lift (every arrow lifts to itself).
   */
  given identityLift[Op[_, _]]: Lift[Op, Op] with
    def lift[A, B](f: Op[A, B]): Op[A, B] = f
  
  /**
   * Lift Pure to KleisliZIO.
   */
  given pureToKleisli[R, E]: Lift[Function1, [A, B] =>> KleisliZIO[R, E, A, B]] with
    def lift[A, B](f: A => B): KleisliZIO[R, E, A, B] =
      KleisliZIO(a => ZIO.succeed(f(a)))
  
  /**
   * Lift KleisliZIO to RWSTZIO.
   */
  given kleisliToRWST[R, E, W: Monoid, S]: Lift[
    [A, B] =>> KleisliZIO[R, E, A, B],
    [A, B] =>> RWSTZIO[R, E, W, S, A, B]
  ] with
    def lift[A, B](f: KleisliZIO[R, E, A, B]): RWSTZIO[R, E, W, S, A, B] =
      RWSTZIO((s, a) => f.run(a).map(b => (s, b, summon[Monoid[W]].empty)))
  
  /**
   * Lift Pure to RWSTZIO (transitive).
   */
  given pureToRWST[R, E, W: Monoid, S]: Lift[
    Function1,
    [A, B] =>> RWSTZIO[R, E, W, S, A, B]
  ] with
    def lift[A, B](f: A => B): RWSTZIO[R, E, W, S, A, B] =
      RWSTZIO((s, a) => ZIO.succeed((s, f(a), summon[Monoid[W]].empty)))

/**
 * Joiner computes the least upper bound (LUB) of two arrow capabilities.
 *
 * When composing scans with different capabilities, the Joiner promotes
 * both to a common arrow type that supports both capabilities.
 *
 * Laws:
 * - Coherence: lift1(f) >>> lift2(g) = lift1(f >>> lift_to_op2(g))
 * - Commutativity: join(Op1, Op2) ≅ join(Op2, Op1)
 *
 * Lattice:
 *         RWST
 *        /    \
 *    Kleisli  (other state arrows)
 *        \    /
 *         Pure
 */
trait Joiner[Op1[_, _], Op2[_, _]]:
  
  /** The least upper bound arrow type */
  type Out[_, _]
  
  /** ArrowKind instance for Out */
  def AK: ArrowKind[Out]
  
  /** Lift from Op1 to Out */
  def lift1[A, B](f: Op1[A, B]): Out[A, B]
  
  /** Lift from Op2 to Out */
  def lift2[A, B](g: Op2[A, B]): Out[A, B]

object Joiner:
  
  type Aux[Op1[_, _], Op2[_, _], O[_, _]] = Joiner[Op1, Op2] { type Out[A, B] = O[A, B] }
  
  def apply[Op1[_, _], Op2[_, _]](using j: Joiner[Op1, Op2]): Joiner[Op1, Op2] = j
  
  /**
   * Identity joiner: Op with itself.
   */
  given identityJoiner[Op[_, _]](using ak: ArrowKind[Op]): Joiner[Op, Op] with
    type Out[A, B] = Op[A, B]
    def AK: ArrowKind[Out] = ak
    def lift1[A, B](f: Op[A, B]): Out[A, B] = f
    def lift2[A, B](g: Op[A, B]): Out[A, B] = g
  
  /**
   * Pure + Pure = Pure
   */
  given purePure: Joiner[Function1, Function1] with
    type Out[A, B] = A => B
    def AK: ArrowKind[Out] = summon[ArrowKind[Function1]]
    def lift1[A, B](f: A => B): Out[A, B] = f
    def lift2[A, B](g: A => B): Out[A, B] = g
  
  /**
   * Pure + Kleisli = Kleisli
   */
  given pureKleisli[R, E]: Joiner[Function1, [A, B] =>> KleisliZIO[R, E, A, B]] with
    type Out[A, B] = KleisliZIO[R, E, A, B]
    def AK: ArrowKind[Out] = summon[ArrowKind[[A, B] =>> KleisliZIO[R, E, A, B]]]
    def lift1[A, B](f: A => B): Out[A, B] = 
      KleisliZIO(a => ZIO.succeed(f(a)))
    def lift2[A, B](g: KleisliZIO[R, E, A, B]): Out[A, B] = g
  
  /**
   * Kleisli + Pure = Kleisli (symmetric)
   */
  given kleisliPure[R, E]: Joiner[[A, B] =>> KleisliZIO[R, E, A, B], Function1] with
    type Out[A, B] = KleisliZIO[R, E, A, B]
    def AK: ArrowKind[Out] = summon[ArrowKind[[A, B] =>> KleisliZIO[R, E, A, B]]]
    def lift1[A, B](f: KleisliZIO[R, E, A, B]): Out[A, B] = f
    def lift2[A, B](g: A => B): Out[A, B] = 
      KleisliZIO(a => ZIO.succeed(g(a)))
  
  /**
   * Kleisli + Kleisli = Kleisli (same R, E)
   */
  given kleisliKleisli[R, E]: Joiner[
    [A, B] =>> KleisliZIO[R, E, A, B],
    [A, B] =>> KleisliZIO[R, E, A, B]
  ] with
    type Out[A, B] = KleisliZIO[R, E, A, B]
    def AK: ArrowKind[Out] = summon[ArrowKind[[A, B] =>> KleisliZIO[R, E, A, B]]]
    def lift1[A, B](f: KleisliZIO[R, E, A, B]): Out[A, B] = f
    def lift2[A, B](g: KleisliZIO[R, E, A, B]): Out[A, B] = g
  
  /**
   * Pure + RWST = RWST
   */
  given pureRWST[R, E, W: Monoid, S]: Joiner[
    Function1,
    [A, B] =>> RWSTZIO[R, E, W, S, A, B]
  ] with
    type Out[A, B] = RWSTZIO[R, E, W, S, A, B]
    def AK: ArrowKind[Out] = summon[ArrowKind[[A, B] =>> RWSTZIO[R, E, W, S, A, B]]]
    def lift1[A, B](f: A => B): Out[A, B] =
      RWSTZIO((s, a) => ZIO.succeed((s, f(a), summon[Monoid[W]].empty)))
    def lift2[A, B](g: RWSTZIO[R, E, W, S, A, B]): Out[A, B] = g
  
  /**
   * RWST + Pure = RWST (symmetric)
   */
  given rwstPure[R, E, W: Monoid, S]: Joiner[
    [A, B] =>> RWSTZIO[R, E, W, S, A, B],
    Function1
  ] with
    type Out[A, B] = RWSTZIO[R, E, W, S, A, B]
    def AK: ArrowKind[Out] = summon[ArrowKind[[A, B] =>> RWSTZIO[R, E, W, S, A, B]]]
    def lift1[A, B](f: RWSTZIO[R, E, W, S, A, B]): Out[A, B] = f
    def lift2[A, B](g: A => B): Out[A, B] =
      RWSTZIO((s, a) => ZIO.succeed((s, g(a), summon[Monoid[W]].empty)))
  
  /**
   * Kleisli + RWST = RWST
   */
  given kleisliRWST[R, E, W: Monoid, S]: Joiner[
    [A, B] =>> KleisliZIO[R, E, A, B],
    [A, B] =>> RWSTZIO[R, E, W, S, A, B]
  ] with
    type Out[A, B] = RWSTZIO[R, E, W, S, A, B]
    def AK: ArrowKind[Out] = summon[ArrowKind[[A, B] =>> RWSTZIO[R, E, W, S, A, B]]]
    def lift1[A, B](f: KleisliZIO[R, E, A, B]): Out[A, B] =
      RWSTZIO((s, a) => f.run(a).map(b => (s, b, summon[Monoid[W]].empty)))
    def lift2[A, B](g: RWSTZIO[R, E, W, S, A, B]): Out[A, B] = g
  
  /**
   * RWST + Kleisli = RWST (symmetric)
   */
  given rwstKleisli[R, E, W: Monoid, S]: Joiner[
    [A, B] =>> RWSTZIO[R, E, W, S, A, B],
    [A, B] =>> KleisliZIO[R, E, A, B]
  ] with
    type Out[A, B] = RWSTZIO[R, E, W, S, A, B]
    def AK: ArrowKind[Out] = summon[ArrowKind[[A, B] =>> RWSTZIO[R, E, W, S, A, B]]]
    def lift1[A, B](f: RWSTZIO[R, E, W, S, A, B]): Out[A, B] = f
    def lift2[A, B](g: KleisliZIO[R, E, A, B]): Out[A, B] =
      RWSTZIO((s, a) => g.run(a).map(b => (s, b, summon[Monoid[W]].empty)))
  
  /**
   * RWST + RWST = RWST (same params)
   */
  given rwstRWST[R, E, W: Monoid, S]: Joiner[
    [A, B] =>> RWSTZIO[R, E, W, S, A, B],
    [A, B] =>> RWSTZIO[R, E, W, S, A, B]
  ] with
    type Out[A, B] = RWSTZIO[R, E, W, S, A, B]
    def AK: ArrowKind[Out] = summon[ArrowKind[[A, B] =>> RWSTZIO[R, E, W, S, A, B]]]
    def lift1[A, B](f: RWSTZIO[R, E, W, S, A, B]): Out[A, B] = f
    def lift2[A, B](g: RWSTZIO[R, E, W, S, A, B]): Out[A, B] = g

/**
 * Helper for capability-aware composition.
 */
object CapabilityPromotion:
  
  /**
   * Promote and compose two operations with different capabilities.
   */
  def composeLifted[Op1[_, _], Op2[_, _], A, B, C](
    f: Op1[A, B],
    g: Op2[B, C]
  )(using j: Joiner[Op1, Op2]): j.Out[A, C] =
    j.AK.compose(j.lift1(f), j.lift2(g))
  
  /**
   * Promote and fanout two operations with different capabilities.
   */
  def fanoutLifted[Op1[_, _], Op2[_, _], A, B, C](
    f: Op1[A, B],
    g: Op2[A, C]
  )(using j: Joiner[Op1, Op2]): j.Out[A, (B, C)] =
    j.AK.fanout(j.lift1(f), j.lift2(g))
