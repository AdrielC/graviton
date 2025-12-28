package graviton
package arrow

import zio.Chunk
import zio.ChunkBuilder

import scala.language.implicitConversions
import scala.quoted.*
import kyo.Record

object MacD:

  final case class StopSignal(
    reason: String,
    framesBeforeStop: Chunk[Frame],
    leftover: Chunk[Byte],
    offending: Option[Frame],
  )

  final case class Report(
    id: String,
    frames: Chunk[Frame],
    hashes: Chunk[Int],
    leftover: Chunk[Byte],
    stopped: Boolean,
    reason: Option[String],
  )

  private[graviton] final case class State(values: Map[String, Any]):
    def getOrInit[Label <: String & Singleton, A](slot: StateSlot[Label, A]): (State, A) =
      values.get(slot.label) match
        case Some(existing) => (this, existing.asInstanceOf[A])
        case None           =>
          val next = slot.fresh()
          (copy(values = values.updated(slot.label, next.asInstanceOf[Any])), next)

    def update[Label <: String & Singleton, A](slot: StateSlot[Label, A], value: A): State =
      copy(values = values.updated(slot.label, value.asInstanceOf[Any]))

    def peek[Label <: String & Singleton, A](slot: StateSlot[Label, A]): Option[A] =
      values.get(slot.label).map(_.asInstanceOf[A])

  private object State:
    val empty: State = State(Map.empty)

  sealed trait Prim[-I, +O, C]

  object Prim:
    final case class FixedChunker[Label <: String & Singleton](
      slot: StateSlot[Label, ChunkAccumulator],
      frameBytes: Int,
    ) extends Prim[Chunk[Byte], Chunk[Chunk[Byte]], Field[Label, ChunkAccumulator]]

    final case class WindowAnnotate(window: Int) extends Prim[Chunk[Chunk[Byte]], Chunk[Frame], Any]

    final case class ShortCircuit[Label <: String & Singleton](
      slot: StateSlot[Label, ChunkAccumulator],
      reason: String,
      predicate: Chunk[Frame] => Boolean,
    ) extends Prim[Chunk[Frame], Either[StopSignal, Chunk[Frame]], Any]

    final case class ContinueReport[Label <: String & Singleton](
      slot: StateSlot[Label, ChunkAccumulator],
      id: String,
    ) extends Prim[Chunk[Frame], Report, Any]

    final case class StopReport[Label <: String & Singleton](id: String) extends Prim[StopSignal, Report, Any]

  opaque type Stage[-I, +O] <: (State, I) => (State, O) = (State, I) => (State, O)

  object Stage:

    extension [I, O](stage: Stage[I, O]) def run(state: State, input: I): (State, O) = stage(state, input)

    def apply[I, O](run: (State, I) => (State, O)): Stage[I, O] =
      run

    type :=>:[-I, +O] = Stage[I, O]

    given bundle: ArrowBundle.Aux[Stage, Tuple2, Either] = new ArrowBundle[Stage]:
      final type :*:[+l, +r] = Tuple2[l, r]
      final type :+:[+l, +r] = Either[l, r]

      override def compose[A, B, C](bc: B :=>: C, ab: A :=>: B): A :=>: C = { (state, input) =>
        val (next, mid) = ab(state, input)
        bc(next, mid)
      }

      override def fromFirst[A]: Stage[(A, Any), A] = Stage((state, pair) => (state, pair._1.asInstanceOf[A]))

      override def fromSecond[B]: Stage[(Any, B), B] = Stage((state, pair) => (state, pair._2.asInstanceOf[B]))

      override def toBoth[A, B, C](a2b: Stage[A, B])(a2c: Stage[A, C]): Stage[A, (B, C)] =
        Stage { (state, input) =>
          val (next, leftOut)  = a2b.run(state, input)
          val (finalState, ro) = a2c.run(next, input)
          (finalState, (leftOut, ro))
        }

      override def parallel[A, B, C, D](left: Stage[A, B], right: Stage[C, D]): Stage[(A, C), (B, D)] =
        Stage { (state, input) =>
          val (a, c)           = input
          val (stateAfterL, b) = left.run(state, a)
          val (stateAfterR, d) = right.run(stateAfterL, c)
          (stateAfterR, (b, d))
        }

      def toLeft[A]: (State, A) => (State, Either[A, Nothing]) = { (state, a) => (state, Left(a)) }

      def toRight[B]: (State, B) => (State, Either[Nothing, B]) = { (state, b) => (state, Right(b)) }

      def inLeft[A, B]: Stage[A, Either[A, B]] = Stage((state, value) => (state, Left(value)))

      def inRight[A, B]: Stage[B, Either[A, B]] = Stage((state, value) => (state, Right(value)))

      override def fromEither[A, B, C](left: => Stage[A, C])(right: => Stage[B, C]): Stage[Either[A, B], C] =
        Stage { (state, either) =>
          either match
            case Left(a)  => left.run(state, a)
            case Right(b) => right.run(state, b)
        }

      override def liftArrow[A, B](f: A => B): Stage[A, B] = Stage((state, input) => (state, f(input)))

  given interpreter: FreeArrow.Interpreter[Prim, Tuple2, Either, Stage] = new FreeArrow.Interpreter[Prim, Tuple2, Either, Stage](
    using Stage.bundle
  ):

    def interpret[I, O, C](prim: Prim[I, O, C]): Stage[I, O] =
      prim match
        case p: Prim.FixedChunker[l]     =>
          Stage[Chunk[Byte], Chunk[Chunk[Byte]]] { (state, chunk) =>
            val (withAcc, acc) = state.getOrInit[l, ChunkAccumulator](p.slot)
            val merged         = acc ++ chunk
            val (nextAcc, out) = merged.emit(math.max(1, p.frameBytes))
            val updated        = withAcc.update(p.slot, nextAcc)
            (updated, out)
          }.asInstanceOf[Stage[I, O]]
        case Prim.WindowAnnotate(window) =>
          Stage[Chunk[Chunk[Byte]], Chunk[Frame]] { (state, frames) =>
            (state, annotate(frames, window))
          }.asInstanceOf[Stage[I, O]]
        case p: Prim.ShortCircuit[l]     =>
          Stage[Chunk[Frame], Either[StopSignal, Chunk[Frame]]] { (state, frames) =>
            if p.predicate(frames.asInstanceOf[Chunk[Frame]]) then
              val (s, acc) = state.getOrInit[l, ChunkAccumulator](p.slot)
              val hotFrame = frames.asInstanceOf[Chunk[Frame]].find(_.flagged)
              val signal   = StopSignal(p.reason, frames.asInstanceOf[Chunk[Frame]], acc.buffer, hotFrame)
              (s, Left(signal))
            else (state, Right(frames.asInstanceOf[Chunk[Frame]]))
          }.asInstanceOf[Stage[I, O]]
        case p: Prim.ContinueReport[l]   =>
          Stage[Chunk[Frame], Report] { (state, frames) =>
            val (s, acc) = state.getOrInit[l, ChunkAccumulator](p.slot)
            val frameSeq = frames.asInstanceOf[Chunk[Frame]]
            val hashes   = frameSeq.map(f => hashBytes(f.bytes))
            val report   = Report(p.id, frameSeq, hashes, acc.buffer, stopped = false, reason = None)
            (s, report)
          }.asInstanceOf[Stage[I, O]]
        case p: Prim.StopReport[l]       =>
          Stage[StopSignal, Report] { (state, signal) =>
            val sig    = signal.asInstanceOf[StopSignal]
            val hashes = sig.framesBeforeStop.map(f => hashBytes(f.bytes))
            val report = Report(p.id, sig.framesBeforeStop, hashes, sig.leftover, stopped = true, reason = Some(sig.reason))
            (state, report)
          }.asInstanceOf[Stage[I, O]]

  def windowed(window: Int): FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Chunk[Byte]], Chunk[Frame], Any] =
    FreeArrow.embed(Prim.WindowAnnotate(window))

  def shortCircuit[Label <: String & Singleton](
    chunker: ChunkerHandle[Label, Prim],
    reason: String,
    predicate: Chunk[Frame] => Boolean,
  ): FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Frame], Either[StopSignal, Chunk[Frame]], Any] =
    FreeArrow.embed(Prim.ShortCircuit(chunker.slot, reason, predicate))

  def continueReport[Label <: String & Singleton](
    chunker: ChunkerHandle[Label, Prim],
    id: String,
  ): FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Frame], Report, Any] =
    FreeArrow.embed(Prim.ContinueReport(chunker.slot, id))

  def stopReport[Label <: String & Singleton](id: String): FreeArrow.Aux[Prim, Tuple2, Either, StopSignal, Report, Any] =
    FreeArrow.embed(Prim.StopReport[Label](id))

  transparent inline def macdFlow(
    frameBytes: Int,
    window: Int,
    stopMarker: Chunk[Byte],
    id: String = "macd/frames",
  ) =

    val chunkerScan = chunker(frameBytes)
    val annotate    = windowed(window)
    val hotFrames   = shortCircuit(chunkerScan, s"$id-stop", frames => containsMarker(flatten(frames), stopMarker))
    val continue    = continueReport(chunkerScan, id)
    val halted      = stopReport(s"$id-halted")
    val out         = chunkerScan.arrow >>> annotate >>> hotFrames >>> (halted ||| continue)
    out

  inline def drive[I, O, C](
    program: FreeArrow.Aux[Prim, Tuple2, Either, I, O, C],
    inputs: Iterable[I],
  ): (Chunk[O], ChunkAccumulator) =
    val stage      = program.compile[Stage]
    val builder    = ChunkBuilder.make[O]()
    val finalState =
      inputs.foldLeft(State.empty) { (state, input) =>
        val (next, out) = stage(state, input)
        builder += out
        next
      }
    val leftovers  = finalState.values
      .collectFirst { case (_, acc: ChunkAccumulator) => acc }
      .getOrElse(ChunkAccumulator.empty)
    (builder.result(), leftovers)

  private def annotate(frames: Chunk[Chunk[Byte]], window: Int): Chunk[Frame] =
    val builder  = ChunkBuilder.make[Frame]()
    val ordinals = Iterator.from(0).map(_.toLong)
    val data     = frames.iterator.zip(ordinals)
    val rolling  = scala.collection.mutable.Queue.empty[Long]
    data.foreach { case (bytes, ordinal) =>
      val sum         = bytes.foldLeft(0L)((acc, byte) => acc + (byte & 0xff))
      rolling.enqueue(sum)
      if rolling.size > math.max(1, window) then
        val _ = rolling.dequeue()
      val windowTotal = rolling.sum
      val flagged     = sum % 2 == 0 || bytes.exists(_ < 0)
      builder += Frame(bytes, ordinal, windowTotal, flagged)
    }
    builder.result()

  inline def chunker[l <: String & Singleton](frameBytes: Int): ChunkerHandle[l | "chunker", Prim] =
    ChunkerHandle.make[l, Prim](frameBytes, ((slot, frameBytes) => MacD.Prim.FixedChunker(slot, frameBytes)))()

  private def containsMarker(bytes: Chunk[Byte], marker: Chunk[Byte]): Boolean =
    if marker.isEmpty || marker.length > bytes.length then false
    else
      var idx = 0
      var hit = false
      while idx <= bytes.length - marker.length && !hit do
        var inner = 0
        var local = true
        while inner < marker.length && local do
          if bytes(idx + inner) != marker(inner) then local = false
          inner += 1
        if local then hit = true
        idx += 1
      hit

  private def hashBytes(bytes: Chunk[Byte]): Int =
    bytes.foldLeft(1)((hash, byte) => 31 * hash + byte)

  private def flatten(frames: Chunk[Frame]): Chunk[Byte] =
    val builder = ChunkBuilder.make[Byte]()
    frames.foreach(frame => builder ++= frame.bytes)
    builder.result()
