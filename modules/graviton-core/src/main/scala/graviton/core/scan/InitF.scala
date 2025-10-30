package graviton.core.scan

import zio.*

/**
 * Invariant-Monoidal (IM) constructor for initial state - simplified version.
 */
trait InitF[F[_]]:
  def unit: F[Unit]
  def product[A, B](fa: F[A], fb: F[B]): F[(A, B)]
  def imap[A, B](fa: F[A])(f: A => B)(g: B => A): F[B]
  
  def pure[A](a: A): F[A] = imap(unit)(_ => a)(_ => ())

object InitF:
  def apply[F[_]](using initF: InitF[F]): InitF[F] = initF
  def evaluate[F[_], A](fa: F[A])(using ev: Eval[F]): A = ev.eval(fa)

trait Eval[F[_]]:
  def eval[A](fa: F[A]): A

/**
 * EvalInit: Pure eager/lazy initialization.
 */
enum EvalInit[+A]:
  case Now(value: A)
  case Later(thunk: () => A)

object EvalInit:
  given InitF[EvalInit] with
    def unit: EvalInit[Unit] = Now(())
    def product[A, B](fa: EvalInit[A], fb: EvalInit[B]): EvalInit[(A, B)] =
      (fa, fb) match
        case (Now(a), Now(b)) => Now((a, b))
        case _ => Later(() => (eval(fa), eval(fb)))
    def imap[A, B](fa: EvalInit[A])(f: A => B)(g: B => A): EvalInit[B] =
      fa match
        case Now(a) => Now(f(a))
        case Later(thunk) => Later(() => f(thunk()))
  
  given Eval[EvalInit] with
    def eval[A](fa: EvalInit[A]): A = EvalInit.eval(fa)
  
  def eval[A](fa: EvalInit[A]): A =
    fa match
      case Now(a) => a
      case Later(thunk) => thunk()
  
  def now[A](a: A): EvalInit[A] = Now(a)
  def later[A](thunk: => A): EvalInit[A] = Later(() => thunk)

/**
 * ZInit: Effectful initialization with ZIO.
 */
enum ZInit[+A]:
  case Now(value: A)
  case Later(effect: UIO[A])

object ZInit:
  given InitF[ZInit] with
    def unit: ZInit[Unit] = Now(())
    def product[A, B](fa: ZInit[A], fb: ZInit[B]): ZInit[(A, B)] =
      (fa, fb) match
        case (Now(a), Now(b)) => Now((a, b))
        case (Now(a), Later(effB)) => Later(effB.map(b => (a, b)))
        case (Later(effA), Now(b)) => Later(effA.map(a => (a, b)))
        case (Later(effA), Later(effB)) => Later(effA.zip(effB))
    def imap[A, B](fa: ZInit[A])(f: A => B)(g: B => A): ZInit[B] =
      fa match
        case Now(a) => Now(f(a))
        case Later(eff) => Later(eff.map(f))
  
  def toZIO[A](za: ZInit[A]): UIO[A] =
    za match
      case Now(a) => ZIO.succeed(a)
      case Later(eff) => eff
  
  def now[A](a: A): ZInit[A] = Now(a)
  def later[A](effect: UIO[A]): ZInit[A] = Later(effect)

/**
 * Natural transformation from InitF carrier to arrow Op.
 */
trait InitToOp[F[_], Op[_, _]]:
  def apply[A](fa: F[A]): Op[Unit, A]

object InitToOp:
  given evalToPure: InitToOp[EvalInit, Function1] with
    def apply[A](fa: EvalInit[A]): Unit => A =
      _ => EvalInit.eval(fa)
  
  given [R, E]: InitToOp[ZInit, [A, B] =>> KleisliZIO[R, E, A, B]] with
    def apply[A](fa: ZInit[A]): KleisliZIO[R, E, Unit, A] =
      KleisliZIO(_ => ZInit.toZIO(fa).asInstanceOf[ZIO[R, E, A]])
  
  given [R, E]: InitToOp[EvalInit, [A, B] =>> KleisliZIO[R, E, A, B]] with
    def apply[A](fa: EvalInit[A]): KleisliZIO[R, E, Unit, A] =
      KleisliZIO(_ => ZIO.succeed(EvalInit.eval(fa)))
