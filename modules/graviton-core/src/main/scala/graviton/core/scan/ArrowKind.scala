package graviton.core.scan

import zio.*

/**
 * Arrow typeclass for capability-aware scan composition.
 *
 * An Arrow is a generalization of functions that supports:
 * - Identity and composition (Category)
 * - Lifting pure functions (arr)
 * - Products (first, second)
 *
 * Instances:
 * - Pure: Op[A, B] = A => B
 * - KleisliZIO: Op[A, B] = A => ZIO[R, E, B]
 * - RWSTZIO: Op[A, B] = (S, A) => ZIO[R, E, (S, B, W)]
 *
 * Laws:
 * - Category identity: id >>> f == f == f >>> id
 * - Category associativity: (f >>> g) >>> h == f >>> (g >>> h)
 * - Arrow identity: arr(id) == id
 * - Arrow composition: arr(f >>> g) == arr(f) >>> arr(g)
 * - Arrow extension: first(arr(f)) == arr(first(f))
 * - Arrow functor: first(f >>> g) == first(f) >>> first(g)
 * - Arrow exchange: first(f) >>> arr(assoc) == arr(assoc) >>> second(f)
 */
trait ArrowKind[Op[_, _]]:
  
  /** Identity arrow */
  def id[A]: Op[A, A]
  
  /** Sequential composition: f >>> g = g ∘ f */
  def compose[A, B, C](f: Op[A, B], g: Op[B, C]): Op[A, C]
  
  /** Lift a pure function into the arrow */
  def arr[A, B](f: A => B): Op[A, B]
  
  /** Apply arrow to first component of a pair */
  def first[A, B, C](fab: Op[A, B]): Op[(A, C), (B, C)]
  
  /** Apply arrow to second component of a pair (derived) */
  def second[A, B, C](fab: Op[A, B]): Op[(C, A), (C, B)] =
    compose(compose(arr(swap[C, A]), first(fab)), arr(swap[B, C]))
  
  /** Split: run two arrows in parallel on a pair */
  def split[A, B, C, D](fab: Op[A, B], fcd: Op[C, D]): Op[(A, C), (B, D)] =
    compose(first[A, B, C](fab), second[C, D, B](fcd))
  
  /** Fanout: run two arrows on the same input, produce pair */
  def fanout[A, B, C](fab: Op[A, B], fac: Op[A, C]): Op[A, (B, C)] =
    compose(arr[A, (A, A)](a => (a, a)), split(fab, fac))
  
  /** Infix operator for composition */
  extension [A, B](f: Op[A, B])
    infix def >>>[C](g: Op[B, C]): Op[A, C] = compose(f, g)
    infix def &&&[C](g: Op[A, C]): Op[A, (B, C)] = fanout(f, g)
    infix def ***[C, D](g: Op[C, D]): Op[(A, C), (B, D)] = split(f, g)

object ArrowKind:
  
  def apply[Op[_, _]](using ak: ArrowKind[Op]): ArrowKind[Op] = ak
  
  // Helper for swap
  private def swap[A, B](pair: (A, B)): (B, A) = (pair._2, pair._1)

/**
 * Pure function arrow (most basic instance).
 */
given ArrowKind[Function1] with
  def id[A]: A => A = identity
  
  def compose[A, B, C](f: A => B, g: B => C): A => C = 
    f andThen g
  
  def arr[A, B](f: A => B): A => B = 
    f
  
  def first[A, B, C](fab: A => B): ((A, C)) => (B, C) =
    case (a, c) => (fab(a), c)

/**
 * Kleisli arrow for ZIO effects.
 * Op[A, B] = A => ZIO[R, E, B]
 */
final case class KleisliZIO[R, E, A, B](run: A => ZIO[R, E, B])

object KleisliZIO:
  
  given [R, E]: ArrowKind[[A, B] =>> KleisliZIO[R, E, A, B]] with
    
    def id[A]: KleisliZIO[R, E, A, A] = 
      KleisliZIO(a => ZIO.succeed(a))
    
    def compose[A, B, C](
      f: KleisliZIO[R, E, A, B], 
      g: KleisliZIO[R, E, B, C]
    ): KleisliZIO[R, E, A, C] =
      KleisliZIO(a => f.run(a).flatMap(g.run))
    
    def arr[A, B](f: A => B): KleisliZIO[R, E, A, B] =
      KleisliZIO(a => ZIO.succeed(f(a)))
    
    def first[A, B, C](fab: KleisliZIO[R, E, A, B]): KleisliZIO[R, E, (A, C), (B, C)] =
      KleisliZIO { case (a, c) => fab.run(a).map(b => (b, c)) }

/**
 * RWST arrow for Reader-Writer-State-Transformer.
 * Op[A, B] = (S, A) => ZIO[R, E, (S, B, W)]
 * 
 * This is the most powerful arrow, supporting:
 * - Reader environment (R)
 * - Error channel (E)
 * - Mutable state (S)
 * - Writer/log output (W)
 */
final case class RWSTZIO[R, E, W, S, A, B](
  run: (S, A) => ZIO[R, E, (S, B, W)]
)

object RWSTZIO:
  
  given [R, E, W: Monoid, S]: ArrowKind[[A, B] =>> RWSTZIO[R, E, W, S, A, B]] with
    
    def id[A]: RWSTZIO[R, E, W, S, A, A] =
      RWSTZIO((s, a) => ZIO.succeed((s, a, summon[Monoid[W]].empty)))
    
    def compose[A, B, C](
      f: RWSTZIO[R, E, W, S, A, B],
      g: RWSTZIO[R, E, W, S, B, C]
    ): RWSTZIO[R, E, W, S, A, C] =
      RWSTZIO { (s0, a) =>
        for
          (s1, b, w1) <- f.run(s0, a)
          (s2, c, w2) <- g.run(s1, b)
        yield (s2, c, summon[Monoid[W]].combine(w1, w2))
      }
    
    def arr[A, B](f: A => B): RWSTZIO[R, E, W, S, A, B] =
      RWSTZIO((s, a) => ZIO.succeed((s, f(a), summon[Monoid[W]].empty)))
    
    def first[A, B, C](fab: RWSTZIO[R, E, W, S, A, B]): RWSTZIO[R, E, W, S, (A, C), (B, C)] =
      RWSTZIO { (s, pair) =>
        val (a, c) = pair
        fab.run(s, a).map { case (s2, b, w) => (s2, (b, c), w) }
      }

/**
 * Monoid typeclass for Writer outputs.
 */
trait Monoid[W]:
  def empty: W
  def combine(w1: W, w2: W): W

object Monoid:
  given Monoid[Unit] with
    def empty: Unit = ()
    def combine(w1: Unit, w2: Unit): Unit = ()
  
  given Monoid[String] with
    def empty: String = ""
    def combine(w1: String, w2: String): String = w1 + w2
  
  given [A]: Monoid[List[A]] with
    def empty: List[A] = Nil
    def combine(w1: List[A], w2: List[A]): List[A] = w1 ++ w2
  
  given [A]: Monoid[Chunk[A]] with
    def empty: Chunk[A] = Chunk.empty
    def combine(w1: Chunk[A], w2: Chunk[A]): Chunk[A] = w1 ++ w2

/**
 * ArrowChoice extends Arrow with sum type routing.
 * 
 * Allows branching based on Either[A, B].
 */
trait ArrowChoice[Op[_, _]] extends ArrowKind[Op]:
  
  /** Apply arrow to Left branch */
  def left[A, B, C](fab: Op[A, B]): Op[Either[A, C], Either[B, C]]
  
  /** Apply arrow to Right branch (derived) */
  def right[A, B, C](fab: Op[A, B]): Op[Either[C, A], Either[C, B]] =
    compose(compose(arr(swapEither[C, A]), left(fab)), arr(swapEither[B, C]))
  
  /** Choice: route Either through two arrows */
  def choice[A, B, C](fab: Op[A, C], fbc: Op[B, C]): Op[Either[A, B], C] =
    compose(left[A, C, B](fab).asInstanceOf[Op[Either[A, B], Either[C, B]]], 
            right[B, C, C](fbc).asInstanceOf[Op[Either[C, B], C]])
  
  /** Infix choice operator */
  extension [A, B](f: Op[A, B])
    infix def |||[C](g: Op[C, B]): Op[Either[A, C], B] = choice(f, g)
    infix def +++[C, D](g: Op[C, D]): Op[Either[A, C], Either[B, D]] =
      left[A, B, C](f).asInstanceOf[Op[Either[A, C], Either[B, C]]].>>>(
        right[C, D, B](g).asInstanceOf[Op[Either[B, C], Either[B, D]]]
      )

object ArrowChoice:
  
  private def swapEither[A, B](e: Either[A, B]): Either[B, A] =
    e.fold(a => Right(a), b => Left(b))
  
  given ArrowChoice[Function1] with
    export ArrowKind.given.{id, compose, arr, first}
    
    def left[A, B, C](fab: A => B): Either[A, C] => Either[B, C] =
      case Left(a) => Left(fab(a))
      case Right(c) => Right(c)
  
  given [R, E]: ArrowChoice[[A, B] =>> KleisliZIO[R, E, A, B]] with
    export KleisliZIO.given[R, E].{id, compose, arr, first}
    
    def left[A, B, C](fab: KleisliZIO[R, E, A, B]): KleisliZIO[R, E, Either[A, C], Either[B, C]] =
      KleisliZIO {
        case Left(a) => fab.run(a).map(Left(_))
        case Right(c) => ZIO.succeed(Right(c))
      }
