package graviton.core.scan

/**
 * Free applicative for scan initialization.
 * 
 * Allows composing initializers without forcing effects until interpretation.
 * Supports pure values and applicative composition.
 */
enum InitF[+A]:
  case Pure[A](a: A) extends InitF[A]
  case Map2[A, B, C](fa: InitF[A], fb: InitF[B], f: (A, B) => C) extends InitF[C]

object InitF:
  /** Lift a pure value */
  def pure[A](a: A): InitF[A] = Pure(a)
  
  /** Map over an InitF */
  def map[A, B](fa: InitF[A])(f: A => B): InitF[B] = fa match
    case Pure(a) => Pure(f(a))
    case Map2(x, y, g) => Map2(x, y, (a, b) => f(g(a, b)))
  
  /** Applicative map2 */
  def map2[A, B, C](fa: InitF[A], fb: InitF[B])(f: (A, B) => C): InitF[C] =
    Map2(fa, fb, f)
  
  /** Applicative product */
  def product[A, B](fa: InitF[A], fb: InitF[B]): InitF[(A, B)] =
    map2(fa, fb)((_, _))
  
  /** Interpret to a value (pure evaluation) */
  def interpret[A](fa: InitF[A]): A = fa match
    case Pure(a) => a
    case Map2(x, y, f) => f(interpret(x), interpret(y))
  
  /** Functor instance */
  given Map1[InitF] with
    def map[A, B](fa: InitF[A])(f: A => B): InitF[B] = InitF.map(fa)(f)
  
  /** Applicative instance */
  given Ap1[InitF] with
    def map[A, B](fa: InitF[A])(f: A => B): InitF[B] = InitF.map(fa)(f)
    def pure[A](a: A): InitF[A] = InitF.pure(a)
    def ap[A, B](ff: InitF[A => B])(fa: InitF[A]): InitF[B] =
      InitF.map2(ff, fa)((f, a) => f(a))
