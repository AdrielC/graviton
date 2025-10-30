package graviton.core.scan

import zio.Chunk

/**
 * Free Symmetric Monoidal Category for Scans.
 *
 * Inspired by Volga's FreeCat design with feature-based hierarchy:
 * - Pure: stateless transformations
 * - Stateful: with accumulating state
 * - Effectful: with ZIO effects
 * - Full: stateful + effectful
 *
 * Structure:
 * - Uses type-level tags for I/O/State
 * - Proper categorical operators (>>>, &&&, |||)
 * - State tracked at type level
 * - Single interpreter, multiple capabilities
 */

/** Type-level tags for scan types */
object tags:
  /** Object type (input/output) */
  sealed trait Obj[A]
  
  /** Unit type (empty state/no output) */
  sealed trait One
  
  /** Product type (state composition) */
  sealed trait Tensor[A, B]
  
  /** State type */
  sealed trait State[S]
  
  /** Chunk wrapper */
  sealed trait Chunked[A]

import tags.*

/** Feature levels for scans */
object features:
  /** Pure stateless transformations */
  sealed trait Pure
  
  /** Stateful (accumulating state) */
  sealed trait Stateful extends Pure
  
  /** Effectful (ZIO) */
  sealed trait Effectful extends Pure
  
  /** Full (stateful + effectful) */
  sealed trait Full extends Stateful with Effectful

import features.*

/**
 * Free Scan structure.
 *
 * Type parameters:
 * - U[_]: Universe of objects
 * - Q[_, _]: Primitive scan type
 * - F: Feature level (Pure/Stateful/Effectful/Full)
 * - In: Input type
 * - Out: Output type
 * - S: State type (hidden, tracked at type-level)
 */
sealed trait FreeScan[U[_], +Q[_, _], F, In, Out, S]

object FreeScan:
  
  /**
   * Identity scan (passes through).
   */
  final case class Id[U[_], In]()(using
    val inp: U[Obj[In]]
  ) extends FreeScan[U, Nothing, Pure, In, In, One]
  
  /**
   * Embed a primitive scan.
   */
  final case class Embed[U[_], Q[_, _], F, In, Out, S](
    prim: Q[In, Out]
  )(using
    val inp: U[Obj[In]],
    val out: U[Obj[Out]],
    val state: U[State[S]]
  ) extends FreeScan[U, Q, F, In, Out, S]
  
  /**
   * Sequential composition (>>>).
   */
  final case class Sequential[U[_], Q[_, _], F1, F2, In, Mid, Out, S1, S2](
    first: FreeScan[U, Q, F1, In, Mid, S1],
    second: FreeScan[U, Q, F2, Mid, Out, S2]
  )(using
    val inp: U[Obj[In]],
    val mid: U[Obj[Mid]],
    val out: U[Obj[Out]],
    val s1: U[State[S1]],
    val s2: U[State[S2]]
  ) extends FreeScan[U, Q, F1 | F2, In, Out, Tensor[S1, S2]]
  
  /**
   * Parallel composition (&&&).
   * 
   * Broadcast input to both scans, combine outputs.
   */
  final case class Fanout[U[_], Q[_, _], F1, F2, In, Out1, Out2, S1, S2](
    left: FreeScan[U, Q, F1, In, Out1, S1],
    right: FreeScan[U, Q, F2, In, Out2, S2]
  )(using
    val inp: U[Obj[In]],
    val o1: U[Obj[Out1]],
    val o2: U[Obj[Out2]],
    val s1: U[State[S1]],
    val s2: U[State[S2]]
  ) extends FreeScan[U, Q, F1 | F2, In, (Out1, Out2), Tensor[S1, S2]]
  
  /**
   * Product (***).
   * 
   * Run two scans in parallel on tuple input.
   */
  final case class Product[U[_], Q[_, _], F1, F2, In1, In2, Out1, Out2, S1, S2](
    left: FreeScan[U, Q, F1, In1, Out1, S1],
    right: FreeScan[U, Q, F2, In2, Out2, S2]
  )(using
    val i1: U[Obj[In1]],
    val i2: U[Obj[In2]],
    val o1: U[Obj[Out1]],
    val o2: U[Obj[Out2]],
    val s1: U[State[S1]],
    val s2: U[State[S2]]
  ) extends FreeScan[U, Q, F1 | F2, (In1, In2), (Out1, Out2), Tensor[S1, S2]]
  
  /**
   * Choice (|||).
   * 
   * Route Either input through left or right scan.
   */
  final case class Choice[U[_], Q[_, _], F1, F2, InL, InR, OutL, OutR, S1, S2](
    left: FreeScan[U, Q, F1, InL, OutL, S1],
    right: FreeScan[U, Q, F2, InR, OutR, S2]
  )(using
    val il: U[Obj[InL]],
    val ir: U[Obj[InR]],
    val ol: U[Obj[OutL]],
    val or: U[Obj[OutR]],
    val s1: U[State[S1]],
    val s2: U[State[S2]]
  ) extends FreeScan[U, Q, F1 | F2, Either[InL, InR], Either[OutL, OutR], Tensor[S1, S2]]
  
  /**
   * Map output (functor).
   */
  final case class MapOut[U[_], Q[_, _], F, In, Out1, Out2, S](
    base: FreeScan[U, Q, F, In, Out1, S],
    f: Out1 => Out2
  )(using
    val inp: U[Obj[In]],
    val o1: U[Obj[Out1]],
    val o2: U[Obj[Out2]],
    val state: U[State[S]]
  ) extends FreeScan[U, Q, F, In, Out2, S]
  
  /**
   * Contramap input.
   */
  final case class ContramapIn[U[_], Q[_, _], F, In1, In2, Out, S](
    base: FreeScan[U, Q, F, In2, Out, S],
    f: In1 => In2
  )(using
    val i1: U[Obj[In1]],
    val i2: U[Obj[In2]],
    val out: U[Obj[Out]],
    val state: U[State[S]]
  ) extends FreeScan[U, Q, F, In1, Out, S]
  
  /**
   * Filter/collect outputs.
   */
  final case class Collect[U[_], Q[_, _], F, In, Out1, Out2, S](
    base: FreeScan[U, Q, F, In, Out1, S],
    pf: PartialFunction[Out1, Out2]
  )(using
    val inp: U[Obj[In]],
    val o1: U[Obj[Out1]],
    val o2: U[Obj[Out2]],
    val state: U[State[S]]
  ) extends FreeScan[U, Q, F, In, Out2, S]
  
  // Smart constructors
  
  def id[U[_], In](using U[Obj[In]]): FreeScan[U, Nothing, Pure, In, In, One] =
    Id()
  
  def embed[U[_], Q[_, _], F, In, Out, S](
    prim: Q[In, Out]
  )(using U[Obj[In]], U[Obj[Out]], U[State[S]]): FreeScan[U, Q, F, In, Out, S] =
    Embed(prim)
  
  def arr[U[_], In, Out](
    f: In => Out
  )(using U[Obj[In]], U[Obj[Out]]): FreeScan[U, Nothing, Pure, In, Out, One] =
    MapOut(Id(), f)

/**
 * Extension methods for scan composition.
 */
extension [U[_], Q[_, _], F1, In, Mid, S1](scan: FreeScan[U, Q, F1, In, Mid, S1])
  
  /**
   * Sequential composition (>>>).
   */
  infix def >>>[F2, Out, S2](
    next: FreeScan[U, Q, F2, Mid, Out, S2]
  )(using
    U[Obj[In]], U[Obj[Mid]], U[Obj[Out]], U[State[S1]], U[State[S2]]
  ): FreeScan[U, Q, F1 | F2, In, Out, Tensor[S1, S2]] =
    FreeScan.Sequential(scan, next)
  
  /**
   * Fanout (&&&).
   */
  infix def &&&[F2, Out2, S2](
    other: FreeScan[U, Q, F2, In, Out2, S2]
  )(using
    U[Obj[In]], U[Obj[Mid]], U[Obj[Out2]], U[State[S1]], U[State[S2]]
  ): FreeScan[U, Q, F1 | F2, In, (Mid, Out2), Tensor[S1, S2]] =
    FreeScan.Fanout(scan, other)
  
  /**
   * Map output.
   */
  def map[Out](
    f: Mid => Out
  )(using U[Obj[In]], U[Obj[Mid]], U[Obj[Out]], U[State[S1]]): FreeScan[U, Q, F1, In, Out, S1] =
    FreeScan.MapOut(scan, f)
  
  /**
   * Contramap input.
   */
  def contramap[In0](
    f: In0 => In
  )(using U[Obj[In0]], U[Obj[In]], U[Obj[Mid]], U[State[S1]]): FreeScan[U, Q, F1, In0, Mid, S1] =
    FreeScan.ContramapIn(scan, f)

/**
 * Primitive scan type for embedding.
 *
 * This is what users implement, then embed into FreeScan.
 */
trait PrimScan[In, Out, S]:
  /** Initial state */
  def init: S
  
  /** Step function: (state, input) => (newState, outputs) */
  def step(state: S, input: In): (S, Chunk[Out])
  
  /** Flush: finalState => trailing outputs */
  def flush(state: S): Chunk[Out]

object PrimScan:
  
  /**
   * Pure stateless scan (arr).
   */
  def pure[In, Out](f: In => Out): PrimScan[In, Out, Unit] =
    new PrimScan[In, Out, Unit]:
      def init = ()
      def step(state: Unit, input: In) = ((), Chunk.single(f(input)))
      def flush(state: Unit) = Chunk.empty
  
  /**
   * Stateful scan (fold).
   */
  def fold[In, S](z: S)(f: (S, In) => S): PrimScan[In, S, S] =
    new PrimScan[In, S, S]:
      def init = z
      def step(state: S, input: In) =
        val s2 = f(state, input)
        (s2, Chunk.single(s2))
      def flush(state: S) = Chunk.empty
  
  /**
   * Accumulating scan (no outputs until flush).
   */
  def accum[In, S, Out](
    z: S
  )(
    step0: (S, In) => S
  )(
    finalize: S => Out
  ): PrimScan[In, Out, S] =
    new PrimScan[In, Out, S]:
      def init = z
      def step(state: S, input: In) = (step0(state, input), Chunk.empty)
      def flush(state: S) = Chunk.single(finalize(state))

/**
 * Interpreter for FreeScan.
 *
 * Converts FreeScan to a concrete execution strategy.
 */
trait ScanInterpreter[U[_]]:
  
  /**
   * Run a scan on a stream of inputs.
   */
  def run[Q[_, _], F, In, Out, S](
    scan: FreeScan[U, Q, F, In, Out, S],
    inputs: Iterable[In]
  )(
    embedPrim: [I, O, SS] => Q[I, O] => PrimScan[I, O, SS]
  ): (S, Vector[Out])

/**
 * Pure interpreter (for testing/reference).
 */
object PureInterpreter extends ScanInterpreter[FreeU]:
  
  def run[Q[_, _], F, In, Out, S](
    scan: FreeScan[FreeU, Q, F, In, Out, S],
    inputs: Iterable[In]
  )(
    embedPrim: [I, O, SS] => Q[I, O] => PrimScan[I, O, SS]
  ): (S, Vector[Out]) =
    scan match
      case FreeScan.Id() =>
        (().asInstanceOf[S], inputs.toVector.asInstanceOf[Vector[Out]])
      
      case e: FreeScan.Embed[?, ?, ?, ?, ?, ?] =>
        val prim = embedPrim(e.prim).asInstanceOf[PrimScan[In, Out, S]]
        var state = prim.init
        val outputs = Vector.newBuilder[Out]
        
        inputs.foreach { input =>
          val (s2, chunk) = prim.step(state, input)
          state = s2
          outputs ++= chunk
        }
        outputs ++= prim.flush(state)
        
        (state, outputs.result())
      
      case seq: FreeScan.Sequential[?, ?, ?, ?, ?, ?, ?, ?, ?] =>
        val (s1, mid) = run(seq.first.asInstanceOf[FreeScan[FreeU, Q, F, In, Any, Any]], inputs)(embedPrim)
        val (s2, out) = run(seq.second.asInstanceOf[FreeScan[FreeU, Q, F, Any, Out, Any]], mid)(embedPrim)
        ((s1, s2).asInstanceOf[S], out)
      
      case fan: FreeScan.Fanout[?, ?, ?, ?, ?, ?, ?, ?, ?] =>
        val (s1, out1) = run(fan.left.asInstanceOf[FreeScan[FreeU, Q, F, In, Any, Any]], inputs)(embedPrim)
        val (s2, out2) = run(fan.right.asInstanceOf[FreeScan[FreeU, Q, F, In, Any, Any]], inputs)(embedPrim)
        val combined = out1.zip(out2).asInstanceOf[Vector[Out]]
        ((s1, s2).asInstanceOf[S], combined)
      
      case m: FreeScan.MapOut[?, ?, ?, ?, ?, ?, ?] =>
        val (state, outs) = run(m.base.asInstanceOf[FreeScan[FreeU, Q, F, In, Any, S]], inputs)(embedPrim)
        (state, outs.map(m.f.asInstanceOf[Any => Out]))
      
      case c: FreeScan.ContramapIn[?, ?, ?, ?, ?, ?, ?] =>
        val mappedInputs = inputs.map(c.f.asInstanceOf[In => Any])
        run(c.base.asInstanceOf[FreeScan[FreeU, Q, F, Any, Out, S]], mappedInputs)(embedPrim)
      
      case col: FreeScan.Collect[?, ?, ?, ?, ?, ?, ?] =>
        val (state, outs) = run(col.base.asInstanceOf[FreeScan[FreeU, Q, F, In, Any, S]], inputs)(embedPrim)
        (state, outs.collect(col.pf.asInstanceOf[PartialFunction[Any, Out]]))
      
      case _ => throw new UnsupportedOperationException("Not implemented")

/**
 * Free universe for scan objects.
 */
sealed trait FreeU[X]

object FreeU:
  given objInstance[A]: FreeU[Obj[A]] = new FreeU[Obj[A]] {}
  given oneInstance: FreeU[One] = new FreeU[One] {}
  given tensorInstance[A, B]: FreeU[Tensor[A, B]] = new FreeU[Tensor[A, B]] {}
  given stateInstance[S]: FreeU[State[S]] = new FreeU[State[S]] {}
