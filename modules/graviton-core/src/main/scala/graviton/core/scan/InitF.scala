package graviton.core.scan

import scala.collection.mutable

/**
 * Eval-like initialization for scan state.
 * 
 * Controls when and how state is computed:
 * - `Now`: Eager evaluation, value already computed
 * - `Later`: Lazy evaluation with memoization (computed once, cached)
 * - `Always`: Lazy evaluation without memoization (recomputed each time)
 * 
 * This mirrors cats.Eval semantics for explicit control over initialization cost.
 */
enum InitF[+A]:
  /** Eager evaluation - value already computed */
  case Now[A](value: A) extends InitF[A]
  
  /** Lazy evaluation with memoization - computed once, then cached */
  case Later[A](thunk: () => A) extends InitF[A]
  
  /** Lazy evaluation without memoization - recomputed on each access */
  case Always[A](thunk: () => A) extends InitF[A]

object InitF:
  /** Create an eagerly evaluated init */
  def now[A](a: A): InitF[A] = Now(a)
  
  /** Create a lazily evaluated init with memoization */
  def later[A](th: => A): InitF[A] = Later(() => th)
  
  /** Create a lazily evaluated init without memoization */
  def always[A](th: => A): InitF[A] = Always(() => th)
  
  /** Evaluate an InitF to its value, respecting memoization policy */
  def evaluate[A](init: InitF[A])(using cache: mutable.Map[InitF[A], A] = mutable.HashMap.empty): A =
    init match
      case Now(v) => v
      case l @ Later(thunk) => cache.getOrElseUpdate(l, thunk())
      case Always(thunk) => thunk()
  
  /** Map over an InitF */
  def map[A, B](fa: InitF[A])(f: A => B): InitF[B] = fa match
    case Now(a) => Now(f(a))
    case Later(th) => Later(() => f(th()))
    case Always(th) => Always(() => f(th()))
  
  /** Applicative map2 */
  def map2[A, B, C](fa: InitF[A], fb: InitF[B])(f: (A, B) => C): InitF[C] =
    Later(() => f(evaluate(fa), evaluate(fb)))
  
  /** Functor instance */
  given Map1[InitF] with
    def map[A, B](fa: InitF[A])(f: A => B): InitF[B] = InitF.map(fa)(f)
  
  /** Applicative instance */
  given Ap1[InitF] with
    def map[A, B](fa: InitF[A])(f: A => B): InitF[B] = InitF.map(fa)(f)
    def pure[A](a: A): InitF[A] = InitF.now(a)
    def ap[A, B](ff: InitF[A => B])(fa: InitF[A]): InitF[B] =
      InitF.map2(ff, fa)((f, a) => f(a))
