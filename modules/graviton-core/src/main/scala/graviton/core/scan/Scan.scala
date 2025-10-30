package graviton.core.scan

import zio.Chunk

/**
 * The Scan interface with hidden state.
 * 
 * A Scan is a stateful streaming transformation that:
 * - Consumes inputs of type I wrapped in F[_]
 * - Produces outputs of type O wrapped in G[_] paired with state
 * - Has an initializer (free-applicative wrapped)
 * - Has a step function (BiKleisli)
 * - Has a flush function that emits final outputs from final state
 * 
 * State S is hidden as a type member and exposed via Aux pattern.
 */
trait Scan[F[_], G[_], I, O]:
  /** The state type - a named-tuple record */
  type S <: Rec
  
  /** Free-applicative initializer */
  def init: InitF[S]
  
  /** Step function: F[I] => G[(S, O)] */
  def step: BiKleisli[F, G, I, (S, O)]
  
  /** Flush final outputs from final state */
  def flush(finalS: S): G[Option[O]]

object Scan:
  /** Aux pattern to expose state type when needed */
  type Aux[F[_], G[_], I, O, SS <: Rec] = Scan[F, G, I, O] { type S = SS }
  
  /** Pure stateless scan - S = Ø, F = G = Id */
  def pure[I, O](f: I => O): Aux[Id, Id, I, O, Ø] =
    new Scan[Id, Id, I, O]:
      type S = Ø
      val init = InitF.now(EmptyTuple)
      val step = BiKleisli[Id, Id, I, (S, O)](i => (EmptyTuple, f(i)))
      def flush(finalS: S) = None
  
  /** Lift pure function into chunked scan */
  def chunked[I, O](f: I => O): Aux[Chunk, Chunk, I, O, Ø] =
    new Scan[Chunk, Chunk, I, O]:
      type S = Ø
      val init = InitF.now(EmptyTuple)
      val step = BiKleisli[Chunk, Chunk, I, (S, O)] { ci =>
        ci.map(i => (EmptyTuple, f(i)))
      }
      def flush(finalS: S) = Chunk.empty
  
  /** Identity scan */
  def identity[F[_], A](F: Map1[F], Ap: Ap1[F]): Aux[F, F, A, A, Ø] =
    new Scan[F, F, A, A]:
      type S = Ø
      val init = InitF.now(EmptyTuple)
      val step = BiKleisli[F, F, A, (S, A)] { fa =>
        F.map(fa)(a => (EmptyTuple, a))
      }
      def flush(finalS: S) = Ap.pure(None)
  
  /** Stateful scan builder */
  def stateful[F[_], G[_], I, O, S0 <: Rec](
    init0: InitF[S0],
    step0: BiKleisli[F, G, I, (S0, O)],
    flush0: S0 => G[Option[O]]
  ): Aux[F, G, I, O, S0] =
    new Scan[F, G, I, O]:
      type S = S0
      val init = init0
      val step = step0
      def flush(finalS: S) = flush0(finalS)
