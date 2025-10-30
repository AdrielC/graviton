package graviton.streams.scan

import zio.Chunk
import zio.stream.{ZChannel, ZPipeline, ZStream}

/**
 * A pure description of a left-associated scan that can emit zero or more outputs for each input.
 *
 * The structure mirrors fs2's [[fs2.Scan]], keeping initial emissions, per-element steps, and
 * a finalizer that can flush buffered state once the upstream completes.
 */
final case class Scan[-In, State, +Out](
  initial: Scan.Step[State, Out],
  step: (State, In) => Scan.Step[State, Out],
  onEnd: State => Chunk[Out] = (_: State) => Chunk.empty,
) {

  /** Compose a function on the produced outputs. */
  def mapOut[Out2](f: Out => Out2): Scan[In, State, Out2] =
    Scan(
      initial.map(f),
      (state, in) => step(state, in).map(f),
      state => onEnd(state).map(f),
    )

  /** Contravariantly map the incoming values. */
  def contramap[In2](f: In2 => In): Scan[In2, State, Out] =
    Scan(initial, (state, in2) => step(state, f(in2)), onEnd)

  /** Dimap across both input and output domains. */
  def dimap[In2, Out2](fi: In2 => In)(fo: Out => Out2): Scan[In2, State, Out2] =
    contramap(fi).mapOut(fo)

  /** Combine the outputs of this scan with another that consumes those outputs. */
  def andThen[State2, Out2](that: Scan[Out, State2, Out2]): Scan[In, (State, State2), Out2] =
    val (initialRightState, fromLeftInitial) = that.consume(that.initial.state, initial.emits)
    val initialOutputs                       = that.initial.emits ++ fromLeftInitial
    Scan(
      Scan.Step((initial.state, initialRightState), initialOutputs),
      { case ((leftState, rightState), in) =>
        val leftStep          = step(leftState, in)
        val (nextRight, outs) = that.consume(rightState, leftStep.emits)
        Scan.Step((leftStep.state, nextRight), outs)
      },
      { case (leftState, rightState) =>
        val (rightAfterFlush, flushedOutputs) = that.consume(rightState, onEnd(leftState))
        flushedOutputs ++ that.onEnd(rightAfterFlush)
      },
    )

  /**
   * Consume a chunk of inputs, returning the new state plus all outputs produced while
   * processing the chunk.
   */
  def consume(state: State, chunk: Chunk[In]): (State, Chunk[Out]) =
    chunk.foldLeft((state, Chunk.empty[Out])) { case ((s, acc), next) =>
      val stepResult = step(s, next)
      (stepResult.state, acc ++ stepResult.emits)
    }

  private def channel: ZChannel[Any, Nothing, Chunk[In], Any, Nothing, Chunk[Out], Any] = {
    def loop(state: State): ZChannel[Any, Nothing, Chunk[In], Any, Nothing, Chunk[Out], Any] =
      ZChannel.readWith(
        (input: Chunk[In]) => {
          val (nextState, out) = consume(state, input)
          ZChannel.write(out) *> loop(nextState)
        },
        err => ZChannel.fail(err),
        _ => ZChannel.write(onEnd(state)) *> ZChannel.unit,
      )

    ZChannel.write(initial.emits) *> loop(initial.state)
  }

  /** A `ZPipeline` that applies this scan to an upstream stream. */
  val pipeline: ZPipeline[Any, Nothing, In, Out] = ZPipeline.fromChannel(channel)

  /** Apply this scan to a `ZStream`, preserving the stream's environment and error type. */
  def applyTo[R, E](stream: ZStream[R, E, In]): ZStream[R, E, Out] =
    stream.via(pipeline)
}

object Scan {

  /** Describes the state produced by each scan step. */
  final case class Step[State, +Out](state: State, emits: Chunk[Out]) {
    def map[Out2](f: Out => Out2): Step[State, Out2] =
      Step(state, emits.map(f))
  }

  object Step {
    def pure[State](state: State): Step[State, Nothing] = Step(state, Chunk.empty)
  }

  /** Build a scan from explicit state transitions. */
  def stateful[In, State, Out](
    initialState: State,
    initialOutputs: Chunk[Out] = Chunk.empty,
    onEnd: State => Chunk[Out] = (_: State) => Chunk.empty,
  )(f: (State, In) => (State, Chunk[Out])): Scan[In, State, Out] =
    Scan(
      Step(initialState, initialOutputs),
      (state, in) => {
        val (s, outs) = f(state, in)
        Step(s, outs)
      },
      onEnd,
    )

  /** Left scan that emits the running state starting with `initial`. */
  def foldLeft[In, State](initial: State)(f: (State, In) => State): Scan[In, State, State] =
    stateful(initial, Chunk.single(initial)) { (state, in) =>
      val next = f(state, in)
      (next, Chunk.single(next))
    }

  /** Emit each element unchanged. */
  def identity[A]: Scan[A, Unit, A] =
    stateful[A, Unit, A]((), Chunk.empty)((state, a) => (state, Chunk.single(a)))
}
