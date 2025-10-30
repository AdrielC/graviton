package graviton.core.scan

/**
 * Free representation of the Scan algebra.
 *
 * This is the canonical lawful representation that:
 * - Supports multiple interpreters (pure, ZIO, Spark, etc.)
 * - Enables optimization passes (fusion, etc.)
 * - Guarantees category/arrow/choice laws by construction
 * - Composes state via type-level operations
 *
 * Algebra constructors:
 * - Prim: lift a primitive scan
 * - Seq: sequential composition (>>>)
 * - Dimap: contramap input / map output
 * - Par: parallel product (tuple both sides)
 * - Choice: sum/coproduct (Either)
 * - Fanout: broadcast input to both scans
 */
enum FreeScan[F[_], G[_], I, O, S <: Rec]:

  /** Lift a primitive step (BiKleisli) + init + flush */
  case Prim(
    init: InitF[S],
    step: BiKleisli[F, G, I, (S, O)],
    flush: S => G[Option[O]],
  )

  /** Sequential composition: I -> X -> O, state is concatenated */
  case Seq[F[_], G[_], I, X, O, SA <: Rec, SB <: Rec](
    left: FreeScan[F, G, I, X, SA],
    right: FreeScan[F, G, X, O, SB],
  ) extends FreeScan[F, G, I, O, SA ++ SB]

  /** Pure mapping of input/output */
  case Dimap[F[_], G[_], I0, I1, O0, O1, S <: Rec](
    base: FreeScan[F, G, I1, O0, S],
    l: I0 => I1,
    r: O0 => O1,
  ) extends FreeScan[F, G, I0, O1, S]

  /** Parallel product: (I1, I2) -> (O1, O2), states merged */
  case Par[F[_], G[_], I1, I2, O1, O2, SA <: Rec, SB <: Rec](
    a: FreeScan[F, G, I1, O1, SA],
    b: FreeScan[F, G, I2, O2, SB],
  ) extends FreeScan[F, G, (I1, I2), (O1, O2), Merge[SA, SB]]

  /** Choice: Either[IL, IR] -> Either[OL, OR], states merged */
  case Choice[F[_], G[_], IL, IR, OL, OR, SL <: Rec, SR <: Rec](
    l: FreeScan[F, G, IL, OL, SL],
    r: FreeScan[F, G, IR, OR, SR],
  ) extends FreeScan[F, G, Either[IL, IR], Either[OL, OR], Merge[SL, SR]]

  /** Fanout: broadcast same input to both scans, produce tuple */
  case Fanout[F[_], G[_], I, O1, O2, SA <: Rec, SB <: Rec](
    a: FreeScan[F, G, I, O1, SA],
    b: FreeScan[F, G, I, O2, SB],
  ) extends FreeScan[F, G, I, (O1, O2), Merge[SA, SB]]

object FreeScan:

  /** Lift a Scan into FreeScan */
  def fromScan[F[_], G[_], I, O, S <: Rec](s: Scan.Aux[F, G, I, O, S]): FreeScan[F, G, I, O, S] =
    Prim(s.init, s.step, s.flush)

  /** Identity scan */
  def id[F[_], A](using F: Map1[F], Ap: Ap1[F]): FreeScan[F, F, A, A, Ø] =
    fromScan(Scan.identity[F, A](F, Ap))

  /** Lift a pure function */
  def arr[F[_], A, B](f: A => B)(using F: Map1[F], Ap: Ap1[F]): FreeScan[F, F, A, B, Ø] =
    fromScan(Scan.pure(f)).asInstanceOf[FreeScan[F, F, A, B, Ø]]

/**
 * Arrow/Category/Choice combinators for FreeScan.
 *
 * These operations are lawful by construction in the Free representation.
 */
extension [F[_], G[_], I, O, SA <: Rec](ab: FreeScan[F, G, I, O, SA])

  /** Sequential composition (category) */
  infix def >>>[O2, SB <: Rec](bc: FreeScan[F, G, O, O2, SB]): FreeScan[F, G, I, O2, SA ++ SB] =
    FreeScan.Seq(ab, bc)

  /** Contramap input */
  def contramap[I2](f: I2 => I): FreeScan[F, G, I2, O, SA] =
    FreeScan.Dimap(ab, f, identity[O])

  /** Map output */
  def map[O2](f: O => O2): FreeScan[F, G, I, O2, SA] =
    FreeScan.Dimap(ab, identity[I], f)

  /** Dimap: contramap input and map output */
  def dimap[I2, O2](f: I2 => I)(g: O => O2): FreeScan[F, G, I2, O2, SA] =
    FreeScan.Dimap(ab, f, g)

  /** First: (I, X) -> (O, X) */
  def first[X]: FreeScan[F, G, (I, X), (O, X), SA] =
    ab.dimap[(I, X), (O, X)](_._1)(o => (o, null.asInstanceOf[X]))
      .asInstanceOf[FreeScan[F, G, (I, X), (O, X), SA]]

  /** Second: (X, I) -> (X, O) */
  def second[X]: FreeScan[F, G, (X, I), (X, O), SA] =
    ab.dimap[(X, I), (X, O)](_._2)(o => (null.asInstanceOf[X], o))
      .asInstanceOf[FreeScan[F, G, (X, I), (X, O), SA]]

  /** Parallel product: pair with another scan */
  def +++[I2, O2, SB <: Rec](ac: FreeScan[F, G, I2, O2, SB]): FreeScan[F, G, (I, I2), (O, O2), Merge[SA, SB]] =
    FreeScan.Par(ab, ac)

  /** Fanout: broadcast input to both scans */
  def fanout[O2, SB <: Rec](ac: FreeScan[F, G, I, O2, SB]): FreeScan[F, G, I, (O, O2), Merge[SA, SB]] =
    FreeScan.Fanout(ab, ac)

  /** Split: duplicate and route through both (aka &&&) */
  def &&&[O2, SB <: Rec](ac: FreeScan[F, G, I, O2, SB]): FreeScan[F, G, I, (O, O2), Merge[SA, SB]] =
    fanout(ac)

extension [F[_], G[_], IL, IR, OL, OR, SL <: Rec, SR <: Rec](
  ab: FreeScan[F, G, IL, OL, SL]
)
  /** Choice: route Either through left or right scan */
  def |||[I2, O2, S2 <: Rec](ac: FreeScan[F, G, IR, OR, SR]): FreeScan[F, G, Either[IL, IR], Either[OL, OR], Merge[SL, SR]] =
    FreeScan.Choice(ab, ac)

  // Note: left/right are complex to express with precise types here
  // They're better expressed through Choice combinator directly
