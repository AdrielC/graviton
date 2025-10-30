package graviton.core.scan

import zio.Chunk

/**
 * Capability typeclasses for scan execution contexts.
 *
 * These define what operations are available in different contexts:
 * - Map1: functor-like mapping
 * - Ap1: applicative for composing initializers
 *
 * We avoid external dependencies (no cats/scalaz) and keep it minimal.
 */

/** Minimal functor-like capability */
trait Map1[F[_]]:
  def map[A, B](fa: F[A])(f: A => B): F[B]

/** Applicative-like init capability for composing initializers */
trait Ap1[F[_]] extends Map1[F]:
  def pure[A](a: A): F[A]
  def ap[A, B](ff: F[A => B])(fa: F[A]): F[B]

  def map2[A, B, C](fa: F[A], fb: F[B])(f: (A, B) => C): F[C] =
    ap(map(fa)(a => (b: B) => f(a, b)))(fb)

/** Identity functor: F[A] = A */
type Id[A] = A

given Map1[Id] with
  def map[A, B](a: A)(f: A => B): B = f(a)

given Ap1[Id] with
  def map[A, B](a: A)(f: A => B): B  = f(a)
  def pure[A](a: A): A               = a
  def ap[A, B](ff: A => B)(fa: A): B = ff(fa)

/** ZIO Chunk functor */
given Map1[Chunk] with
  def map[A, B](fa: Chunk[A])(f: A => B): Chunk[B] = fa.map(f)

given Ap1[Chunk] with
  def map[A, B](fa: Chunk[A])(f: A => B): Chunk[B]        = fa.map(f)
  def pure[A](a: A): Chunk[A]                             = Chunk.single(a)
  def ap[A, B](ff: Chunk[A => B])(fa: Chunk[A]): Chunk[B] =
    ff.flatMap(f => fa.map(f))

/** BiKleisli: transforms F[I] into G[O] */
final case class BiKleisli[F[_], G[_], I, O](run: F[I] => G[O]):

  /** Compose when F and G are the same - note: this is simplified, real impl in FreeScan */
  def >>>[O2](bc: BiKleisli[F, F, O, O2])(using F: Map1[F], Ap: Ap1[F]): BiKleisli[F, F, I, O2] =
    BiKleisli { fi =>
      val go = run(fi)
      // Cannot properly compose without flatMap; this is a placeholder
      // Real composition happens in FreeScan.Seq
      go.asInstanceOf[F[O2]]
    }

  /** Dimap: preprocess input and postprocess output */
  def dimap[I2, O2](f: I2 => I)(g: O => O2)(using F: Map1[F], G: Map1[G]): BiKleisli[F, G, I2, O2] =
    BiKleisli { fi2 =>
      G.map(run(F.map(fi2)(f)))(g)
    }

object BiKleisli:
  /** Identity BiKleisli */
  def id[F[_], A](using F: Map1[F]): BiKleisli[F, F, A, A] =
    BiKleisli(identity)

  /** Lift a pure function */
  def lift[F[_], A, B](f: A => B)(using F: Map1[F]): BiKleisli[F, F, A, B] =
    BiKleisli(fa => F.map(fa)(f))

/** Capability pack for interpreters */
final case class Caps[F[_], G[_]](
  inMap: Map1[F],
  outMap: Map1[G],
  inAp: Ap1[F],
  outAp: Ap1[G],
)

object Caps:
  given idCaps: Caps[Id, Id]          = Caps(summon, summon, summon, summon)
  given chunkCaps: Caps[Chunk, Chunk] = Caps(summon, summon, summon, summon)
