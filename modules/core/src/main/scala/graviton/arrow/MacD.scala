package graviton.arrow

import zio.Chunk
import zio.ChunkBuilder

import scala.NamedTuple
import scala.language.implicitConversions
import scala.quoted.*

object MacD:

  type Field[Label <: String, A] = NamedTuple.NamedTuple[(Label *: EmptyTuple), (A *: EmptyTuple)]

  final case class Frame(bytes: Chunk[Byte], ordinal: Long, rolling: Long, flagged: Boolean)

  final case class ChunkAccumulator(buffer: Chunk[Byte]):
    def ++(chunk: Chunk[Byte]): ChunkAccumulator = ChunkAccumulator(buffer ++ chunk)

    def emit(frameBytes: Int): (ChunkAccumulator, Chunk[Chunk[Byte]]) =
      var remaining = buffer
      val builder   = ChunkBuilder.make[Chunk[Byte]]()
      while remaining.length >= frameBytes do
        val (emitChunk, tail) = remaining.splitAt(frameBytes)
        builder += emitChunk
        remaining = tail
      (ChunkAccumulator(remaining), builder.result())

  object ChunkAccumulator:
    val empty: ChunkAccumulator = ChunkAccumulator(Chunk.empty)

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

  private final case class State(values: Map[String, Any]):
    def getOrInit[A](slot: StateSlot[?, A]): (State, A) =
      values.get(slot.label) match
        case Some(existing) => (this, existing.asInstanceOf[A])
        case None =>
          val next = slot.fresh()
          (copy(values = values.updated(slot.label, next.asInstanceOf[Any])), next)

    def update[A](slot: StateSlot[?, A], value: A): State =
      copy(values = values.updated(slot.label, value.asInstanceOf[Any]))

    def peek[A](slot: StateSlot[?, A]): Option[A] =
      values.get(slot.label).map(_.asInstanceOf[A])

  private object State:
    val empty: State = State(Map.empty)

  sealed trait Prim[-I, +O, C <: NamedTuple.AnyNamedTuple]

  object Prim:
    final case class FixedChunker[Label <: String](
      slot: StateSlot[Label, ChunkAccumulator],
      frameBytes: Int,
    ) extends Prim[Chunk[Byte], Chunk[Chunk[Byte]], Field[Label, ChunkAccumulator]]

    final case class WindowAnnotate(window: Int) extends Prim[Chunk[Chunk[Byte]], Chunk[Frame], NamedTuple.Empty]

    final case class ShortCircuit[Label <: String](
      slot: StateSlot[Label, ChunkAccumulator],
      reason: String,
      predicate: Chunk[Frame] => Boolean,
    ) extends Prim[Chunk[Frame], Either[StopSignal, Chunk[Frame]], NamedTuple.Empty]

    final case class ContinueReport[Label <: String](
      slot: StateSlot[Label, ChunkAccumulator],
      id: String,
    ) extends Prim[Chunk[Frame], Report, NamedTuple.Empty]

    final case class StopReport(id: String) extends Prim[StopSignal, Report, NamedTuple.Empty]

  final case class Stage[-I, +O](run: (State, I) => (State, O))

  object Stage:
    given ArrowBundle.Aux[Stage, Tuple2, Either] with
      override type :*:[+l, +r] = (l, r)
      override type :+:[+l, +r] = Either[l, r]

      override def compose[A, B, C](bc: Stage[B, C], ab: Stage[A, B]): Stage[A, C] =
        Stage { (state, input) =>
          val (next, mid) = ab.run(state, input)
          bc.run(next, mid)
        }

      override def identity[A]: Stage[A, A] = Stage((state, input) => (state, input))

      override def zero[A, B](using bottom: BottomOf[B]): Stage[A, B] =
        Stage((state, _) => (state, bottom.value))

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
          val (a, c)          = input
          val (stateAfterL, b) = left.run(state, a)
          val (stateAfterR, d) = right.run(stateAfterL, c)
          (stateAfterR, (b, d))
        }

      override def inLeft[A, B]: Stage[A, Either[A, B]] = Stage((state, value) => (state, Left(value)))

      override def inRight[A, B]: Stage[B, Either[A, B]] = Stage((state, value) => (state, Right(value)))

      override def fromEither[A, B, C](left: => Stage[A, C])(right: => Stage[B, C]): Stage[Either[A, B], C] =
        Stage { (state, either) =>
          either match
            case Left(a)  => left.run(state, a)
            case Right(b) => right.run(state, b)
        }

      override def liftArrow[A, B](f: A => B): Stage[A, B] = Stage((state, input) => (state, f(input)))

  given interpreter: FreeArrow.Interpreter[Prim, Tuple2, Either, Stage] with
    val bundle: ArrowBundle.Aux[Stage, Tuple2, Either] = summon[ArrowBundle[Stage]]

    def interpret[I, O, C <: NamedTuple.AnyNamedTuple](prim: Prim[I, O, C]): Stage[I, O] =
      prim match
        case Prim.FixedChunker(slot, frameBytes) =>
          Stage { (state, chunk) =>
            val (withAcc, acc) = state.getOrInit(slot)
            val merged         = acc ++ chunk
            val (nextAcc, out) = merged.emit(math.max(1, frameBytes))
            val updated        = withAcc.update(slot, nextAcc)
            (updated, out.asInstanceOf[O])
          }
        case Prim.WindowAnnotate(window) =>
          Stage { (state, frames) =>
            (state, annotate(frames, window).asInstanceOf[O])
          }
        case Prim.ShortCircuit(slot, reason, predicate) =>
          Stage { (state, frames) =>
            if predicate(frames.asInstanceOf[Chunk[Frame]]) then
              val (s, acc) = state.getOrInit(slot)
              val hotFrame = frames.asInstanceOf[Chunk[Frame]].find(_.flagged)
              val signal   = StopSignal(reason, frames.asInstanceOf[Chunk[Frame]], acc.buffer, hotFrame)
              (s, Left(signal).asInstanceOf[O])
            else
              (state, Right(frames.asInstanceOf[Chunk[Frame]]).asInstanceOf[O])
          }
        case Prim.ContinueReport(slot, id) =>
          Stage { (state, frames) =>
            val (s, acc) = state.getOrInit(slot)
            val frameSeq = frames.asInstanceOf[Chunk[Frame]]
            val hashes   = frameSeq.map(f => hashBytes(f.bytes))
            val report   = Report(id, frameSeq, hashes, acc.buffer, stopped = false, reason = None)
            (s, report.asInstanceOf[O])
          }
        case Prim.StopReport(id) =>
          Stage { (state, signal) =>
            val sig     = signal.asInstanceOf[StopSignal]
            val hashes  = sig.framesBeforeStop.map(f => hashBytes(f.bytes))
            val report  = Report(id, sig.framesBeforeStop, hashes, sig.leftover, stopped = true, reason = Some(sig.reason))
            (state, report.asInstanceOf[O])
          }

  final case class ChunkerHandle[Label <: String](
    slot: StateSlot[Label, ChunkAccumulator],
    arrow: FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Byte], Chunk[Chunk[Byte]], Field[Label, ChunkAccumulator]],
  )

  given [Label <: String]: Conversion[ChunkerHandle[Label], FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Byte], Chunk[Chunk[Byte]], Field[Label, ChunkAccumulator]]] =
    _.arrow

  inline def chunker(frameBytes: Int): ChunkerHandle[?] = ${ chunkerImpl('frameBytes) }

  def windowed(window: Int): FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Chunk[Byte]], Chunk[Frame], NamedTuple.Empty] =
    FreeArrow.embed(Prim.WindowAnnotate(window))

  def shortCircuit[Label <: String](
    chunker: ChunkerHandle[Label],
    reason: String,
    predicate: Chunk[Frame] => Boolean,
  ): FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Frame], Either[StopSignal, Chunk[Frame]], NamedTuple.Empty] =
    FreeArrow.embed(Prim.ShortCircuit(chunker.slot, reason, predicate))

  def continueReport[Label <: String](
    chunker: ChunkerHandle[Label],
    id: String,
  ): FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Frame], Report, NamedTuple.Empty] =
    FreeArrow.embed(Prim.ContinueReport(chunker.slot, id))

  def stopReport(id: String): FreeArrow.Aux[Prim, Tuple2, Either, StopSignal, Report, NamedTuple.Empty] =
    FreeArrow.embed(Prim.StopReport(id))

  def macdFlow(
    frameBytes: Int,
    window: Int,
    stopMarker: Chunk[Byte],
    id: String = "macd/frames",
  ): FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Byte], Report, ?] =
    val chunker    = chunker(frameBytes)
    val annotate   = windowed(window)
    val hotFrames  = shortCircuit(chunker, s"$id-stop", frames => containsMarker(flatten(frames), stopMarker))
    val continue   = continueReport(chunker, id)
    val halted     = stopReport(s"$id-halted")
    chunker.arrow >>> annotate >>> hotFrames >>> (halted ||| continue)

  def drive[I, O](
    program: FreeArrow.Aux[Prim, Tuple2, Either, I, O, ?],
    inputs: Iterable[I],
  ): (Chunk[O], ChunkAccumulator) =
    val stage   = program.compile(using interpreter)
    val builder = ChunkBuilder.make[O]()
    val finalState =
      inputs.foldLeft(State.empty) { (state, input) =>
        val (next, out) = stage.run(state, input)
        builder += out
        next
      }
    val leftovers = finalState.values.collectFirst { case (_, acc: ChunkAccumulator) => acc }.getOrElse(ChunkAccumulator.empty)
    (builder.result(), leftovers)

  private def annotate(frames: Chunk[Chunk[Byte]], window: Int): Chunk[Frame] =
    val builder = ChunkBuilder.make[Frame]()
    val ordinals = Iterator.from(0).map(_.toLong)
    val data     = frames.iterator.zip(ordinals)
    val rolling  = scala.collection.mutable.Queue.empty[Long]
    data.foreach { case (chunk, ordinal) =>
      val sum = chunk.foldLeft(0L)((acc, byte) => acc + (byte & 0xff))
      rolling.enqueue(sum)
      if rolling.size > math.max(1, window) then rolling.dequeue()
      val windowTotal = rolling.sum
      val flagged     = sum % 2 == 0 || chunk.exists(_ < 0)
      builder += Frame(chunk, ordinal, windowTotal, flagged)
    }
    builder.result()

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

  private def chunkerImpl(frameBytesExpr: Expr[Int])(using Quotes): Expr[ChunkerHandle[?]] =
    import quotes.reflect.*
    val labelValue = StateNaming.derive("chunker")
    val labelType  = TypeRepr.constType(StringConstant(labelValue))
    labelType.asType match
      case '[label] =>
        '{
          val slot = StateSlot[label, MacD.ChunkAccumulator](labelValue, MacD.ChunkAccumulator.empty)
          val arrow = FreeArrow.embed[
            Prim,
            Tuple2,
            Either,
            Chunk[Byte],
            Chunk[Chunk[Byte]],
            Field[label, MacD.ChunkAccumulator],
          ](Prim.FixedChunker[label](slot, $frameBytesExpr))
          ChunkerHandle[Label = label](slot, arrow)
        }
