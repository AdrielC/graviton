package graviton.core.scan

import graviton.core.scan.FreeScan.*
import graviton.core.scan.Prim.*
import graviton.core.manifest.*
import graviton.core.bytes.*
import kyo.Record
import kyo.Record.`~`
import kyo.Tag
import zio.*
import zio.stream.*
import zio.ChunkBuilder

trait LabelEvidence[Label <: String]:
  def value: String

object LabelEvidence:
  inline given literal[Label <: String](using literal: ValueOf[Label]): LabelEvidence[Label] =
    new LabelEvidence[Label]:
      val value: String = literal.value

object Tensor:
  type Pair[L <: String, R <: String, A, B] = Record[(L ~ A) & (R ~ B)]

  def pack[L <: String, R <: String, A, B](left: A, right: B)(using LabelEvidence[L], LabelEvidence[R], Tag[A], Tag[B]): Pair[L, R, A, B] =
    (Record.empty
      & (summon[LabelEvidence[L]].value ~ left)
      & (summon[LabelEvidence[R]].value ~ right)).asInstanceOf[Pair[L, R, A, B]]

  def left[L <: String, R <: String, A, B](pair: Pair[L, R, A, B])(using LabelEvidence[L]): A =
    lookup[A](pair, summon[LabelEvidence[L]].value)

  def right[L <: String, R <: String, A, B](pair: Pair[L, R, A, B])(using LabelEvidence[R]): B =
    lookup[B](pair, summon[LabelEvidence[R]].value)

  def toTuple[L <: String, R <: String, A, B](pair: Pair[L, R, A, B])(using LabelEvidence[L], LabelEvidence[R]): (A, B) =
    (left(pair), right(pair))

  private def lookup[A](record: Record[?], label: String): A =
    record.toMap
      .collectFirst {
        case (field, value) if field.name == label =>
          value.asInstanceOf[A]
      }
      .getOrElse(throw new NoSuchElementException(s"Field '$label' missing in tensor record"))

final case class ScanBranch[A, B, Label <: String](scan: FreeScan[Prim, A, B])

object ScanBranch:
  type AutoLeft  = "_0"
  type AutoRight = "_1"

  def autoLeft[A, B](scan: FreeScan[Prim, A, B]): ScanBranch[A, B, AutoLeft]   = ScanBranch(scan)
  def autoRight[A, B](scan: FreeScan[Prim, A, B]): ScanBranch[A, B, AutoRight] = ScanBranch(scan)

/**
 * Free symmetric monoidal category over primitive scan alphabet `Q`.
 */
sealed trait FreeScan[+Q[_, _], A, B]

object FreeScan:
  final case class Id[Q[_, _], A]()                                                         extends FreeScan[Q, A, A]
  final case class Embed[Q[_, _], A, B](prim: Q[A, B])                                      extends FreeScan[Q, A, B]
  final case class Seq[Q[_, _], A, B, C](left: FreeScan[Q, A, B], right: FreeScan[Q, B, C]) extends FreeScan[Q, A, C]
  final case class Par[Q[_, _], A, B, C, D, L <: String, R <: String](
    left: FreeScan[Q, A, B],
    right: FreeScan[Q, C, D],
  )(
    using val inLeftTag: Tag[A],
    val outLeftTag: Tag[B],
    val inRightTag: Tag[C],
    val outRightTag: Tag[D],
    val leftLabel: LabelEvidence[L],
    val rightLabel: LabelEvidence[R],
  ) extends FreeScan[Q, Tensor.Pair[L, R, A, C], Tensor.Pair[L, R, B, D]]

/**
 * Primitive scan alphabet.
 */
sealed trait Prim[A, B]

object Prim:
  final case class Map1[A, B](f: A => B)                                                             extends Prim[A, B]
  final case class Filter[A](p: A => Boolean)                                                        extends Prim[A, A]
  final case class Flat[A, B](f: A => Chunk[B])                                                      extends Prim[A, B]
  final case class Fold[A, B, S](init: () => S, step: (S, A) => (S, Chunk[B]), flush: S => Chunk[B]) extends Prim[A, B]

/**
 * Volga-style combinator helpers and batteries-included primitives.
 */
object FS:
  def id[A]: FreeScan[Prim, A, A] = FreeScan.Id()

  def map[A, B](f: A => B): FreeScan[Prim, A, B] = FreeScan.Embed(Map1(f))

  def filter[A](p: A => Boolean): FreeScan[Prim, A, A] = FreeScan.Embed(Filter(p))

  def flat[A, B](f: A => Chunk[B]): FreeScan[Prim, A, B] = FreeScan.Embed(Flat(f))

  def fold[A, B, S](initial: => S)(step: (S, A) => (S, Chunk[B]))(flush: S => Chunk[B]): FreeScan[Prim, A, B] =
    FreeScan.Embed(Fold(() => initial, step, flush))

  def stateful[A, S](initial: => S)(step: (S, A) => S): FreeScan[Prim, A, S] =
    fold[A, S, S](initial) { (s, a) =>
      val next = step(s, a)
      (next, Chunk(next))
    }(_ => Chunk.empty)

  def counter[A]: FreeScan[Prim, A, Long] =
    fold[A, Long, Long](0L) { (count, _) =>
      val next = count + 1
      (next, Chunk(next))
    }(_ => Chunk.empty)

  def byteCounter: FreeScan[Prim, Chunk[Byte], Long] =
    fold[Chunk[Byte], Long, Long](0L) { (total, bytes) =>
      val next = total + bytes.length
      (next, Chunk(next))
    }(_ => Chunk.empty)

  def hashBytes(algo: HashAlgo): FreeScan[Prim, Chunk[Byte], Either[String, Digest]] =
    fold[Chunk[Byte], Either[String, Digest], Hasher](Hasher.unsafeMessageDigest(algo)) { (hasher, chunk) =>
      val updated = hasher.update(chunk.toArray)
      (updated, Chunk.empty)
    }(hasher => Chunk(hasher.result))

  def buildManifest: FreeScan[Prim, ManifestEntry, Manifest] =
    fold[ManifestEntry, Manifest, (List[ManifestEntry], Long)]((Nil, 0L)) { case ((entries, total), entry) =>
      val spanLength = entry.span.length
      val next       = (entry :: entries, total + spanLength)
      (next, Chunk.empty)
    } { case (entries, total) => Chunk(Manifest(entries.reverse, total)) }

  def fixedChunker(frameBytes: Int): FreeScan[Prim, Chunk[Byte], Chunk[Byte]] =
    val safeSize = math.max(1, frameBytes)
    fold[Chunk[Byte], Chunk[Byte], (Array[Byte], Int)]((Array.ofDim[Byte](safeSize), 0)) { case ((buffer, filled), chunk) =>
      val out      = ChunkBuilder.make[Chunk[Byte]]()
      var writeIdx = filled
      var idx      = 0
      while idx < chunk.length do
        buffer(writeIdx) = chunk(idx)
        writeIdx += 1
        if writeIdx == safeSize then
          out += Chunk.fromArray(java.util.Arrays.copyOf(buffer, safeSize))
          writeIdx = 0
        idx += 1
      ((buffer, writeIdx), out.result())
    } { case (buffer, filled) =>
      if filled == 0 then Chunk.empty
      else Chunk(Chunk.fromArray(java.util.Arrays.copyOf(buffer, filled)))
    }

  final case class FastCDCParameters(
    minBytes: Int,
    avgBytes: Int,
    maxBytes: Int,
    smallMask: Int,
    largeMask: Int,
  )

  def fastCDCChunker(parameters: FastCDCParameters): FreeScan[Prim, Chunk[Byte], Chunk[Byte]] =
    val sanitized = FastCDCParameters(
      minBytes = math.max(1, parameters.minBytes),
      avgBytes = math.max(math.max(1, parameters.minBytes), parameters.avgBytes),
      maxBytes = math.max(math.max(1, parameters.avgBytes), parameters.maxBytes),
      smallMask = math.max(1, parameters.smallMask),
      largeMask = math.max(1, parameters.largeMask),
    )
    fold[Chunk[Byte], Chunk[Byte], FastCDCState](new FastCDCState(sanitized)) { (state, chunk) =>
      (state, state.consume(chunk))
    }(_.drain)

  def pair[L <: String, R <: String, A, B](
    left: A,
    right: B,
  )(using LabelEvidence[L], LabelEvidence[R], Tag[A], Tag[B]): Tensor.Pair[L, R, A, B] =
    Tensor.pack(left, right)

  extension [A, B](left: FreeScan[Prim, A, B])
    infix def >>>[C](right: FreeScan[Prim, B, C]): FreeScan[Prim, A, C] = FreeScan.Seq(left, right)

    inline def labelled[Label <: String]: ScanBranch[A, B, Label] = ScanBranch(left)

    infix def ><[C, D](right: FreeScan[Prim, C, D])(using Tag[A], Tag[B], Tag[C], Tag[D]): FreeScan[
      Prim,
      Tensor.Pair[ScanBranch.AutoLeft, ScanBranch.AutoRight, A, C],
      Tensor.Pair[ScanBranch.AutoLeft, ScanBranch.AutoRight, B, D],
    ] =
      combineBranches(ScanBranch.autoLeft(left), ScanBranch.autoRight(right))

    infix def ><[C, D, R <: String](right: ScanBranch[C, D, R])(using Tag[A], Tag[B], Tag[C], Tag[D], LabelEvidence[R]): FreeScan[
      Prim,
      Tensor.Pair[ScanBranch.AutoLeft, R, A, C],
      Tensor.Pair[ScanBranch.AutoLeft, R, B, D],
    ] =
      combineBranches(ScanBranch.autoLeft(left), right)

    def optimize: FreeScan[Prim, A, B] = Optimize.fuse(left)

    def toChannel: ZChannel[Any, Nothing, Chunk[A], Any, Nothing, Chunk[B], Unit] = Compile.toChannel(left)

    def toPipeline: ZPipeline[Any, Nothing, A, B] = ZPipeline.fromChannel(Compile.toChannel(left))

    def runChunk(inputs: Iterable[A]): Chunk[B] = Compile.runChunk(left, inputs)

    def runList(inputs: Iterable[A]): List[B] = runChunk(inputs).toList

  extension [A, B, L <: String](left: ScanBranch[A, B, L])
    infix def ><[C, D](right: FreeScan[Prim, C, D])(using Tag[A], Tag[B], Tag[C], Tag[D], LabelEvidence[L]): FreeScan[
      Prim,
      Tensor.Pair[L, ScanBranch.AutoRight, A, C],
      Tensor.Pair[L, ScanBranch.AutoRight, B, D],
    ] =
      combineBranches(left, ScanBranch.autoRight(right))

    infix def ><[C, D, R <: String](
      right: ScanBranch[C, D, R]
    )(using Tag[A], Tag[B], Tag[C], Tag[D], LabelEvidence[L], LabelEvidence[R]): FreeScan[
      Prim,
      Tensor.Pair[L, R, A, C],
      Tensor.Pair[L, R, B, D],
    ] =
      combineBranches(left, right)

  private def combineBranches[A, B, C, D, L <: String, R <: String](
    left: ScanBranch[A, B, L],
    right: ScanBranch[C, D, R],
  )(using Tag[A], Tag[B], Tag[C], Tag[D], LabelEvidence[L], LabelEvidence[R]): FreeScan[
    Prim,
    Tensor.Pair[L, R, A, C],
    Tensor.Pair[L, R, B, D],
  ] =
    FreeScan.Par(left.scan, right.scan)

/**
 * Peephole optimizer for local primitive fusion.
 */
object Optimize:

  def fuse[A, B](scan: FreeScan[Prim, A, B]): FreeScan[Prim, A, B] =
    scan match
      case seq: FreeScan.Seq[Prim, a, b, c] @unchecked          =>
        val left  = fuse(seq.left)
        val right = fuse(seq.right)
        (left, right) match
          case (FreeScan.Embed(Map1(f1)), FreeScan.Embed(Map1(f2)))                       =>
            FreeScan.Embed(Map1(f1.andThen(f2))).asInstanceOf[FreeScan[Prim, A, B]]
          case (FreeScan.Embed(filter1: Filter[t]), FreeScan.Embed(filterAny: Filter[?])) =>
            val filter2 = filterAny.asInstanceOf[Filter[t]]
            val merged  = Filter[t](value => filter1.p(value) && filter2.p(value))
            FreeScan.Embed(merged).asInstanceOf[FreeScan[Prim, A, B]]
          case _                                                                          => FreeScan.Seq(left, right).asInstanceOf[FreeScan[Prim, A, B]]
      case par: FreeScan.Par[Prim, a, b, c, d, l, r] @unchecked =>
        FreeScan
          .Par(
            fuse(par.left),
            fuse(par.right),
          )(using par.inLeftTag, par.outLeftTag, par.inRightTag, par.outRightTag, par.leftLabel, par.rightLabel)
          .asInstanceOf[FreeScan[Prim, A, B]]
      case other                                                => other

private final class FastCDCState(params: FS.FastCDCParameters):
  private val buffer: Array[Byte] = Array.ofDim[Byte](params.maxBytes)
  private var size: Int           = 0
  private var fingerprint: Int    = 0

  def consume(chunk: Chunk[Byte]): Chunk[Chunk[Byte]] =
    val out = ChunkBuilder.make[Chunk[Byte]]()
    var idx = 0
    while idx < chunk.length do
      append(chunk(idx), out)
      idx += 1
    out.result()

  def drain: Chunk[Chunk[Byte]] =
    if size == 0 then Chunk.empty
    else Chunk.single(emitBlock())

  private def append(byte: Byte, out: ChunkBuilder[Chunk[Byte]]): Unit =
    if size == buffer.length then out += emitBlock()
    buffer(size) = byte
    size += 1
    fingerprint = ((fingerprint << 1) + FastCDCGearTable(byte & 0xff))
    if shouldCut then out += emitBlock()

  private def shouldCut: Boolean =
    if size < params.minBytes then false
    else if size >= params.maxBytes then true
    else if size >= params.avgBytes then (fingerprint & params.largeMask) == 0
    else (fingerprint & params.smallMask) == 0

  private def emitBlock(): Chunk[Byte] =
    val copy = java.util.Arrays.copyOf(buffer, size)
    size = 0
    fingerprint = 0
    Chunk.fromArray(copy)

private val FastCDCGearTable: Array[Int] =
  val random = new java.util.Random(0x1eedbeefL)
  Array.fill(256)(random.nextInt())

private trait Step[-A, +B]:
  def init(): Unit
  def onElem(a: A): Chunk[B]
  def onEnd(): Chunk[B]

private object Compile:

  def toChannel[A, B](free: FreeScan[Prim, A, B]): ZChannel[Any, Nothing, Chunk[A], Any, Nothing, Chunk[B], Unit] =
    val step = build(Optimize.fuse(free))

    def loop: ZChannel[Any, Nothing, Chunk[A], Any, Nothing, Chunk[B], Unit] =
      ZChannel.readWith(
        (chunk: Chunk[A]) =>
          val builder  = ChunkBuilder.make[B]()
          chunk.foreach { a =>
            val out = step.onElem(a)
            out.foreach(value => builder += value)
          }
          val outChunk = builder.result()
          val emit     =
            if outChunk.isEmpty then ZChannel.unit else ZChannel.writeChunk(Chunk(outChunk))
          emit *> loop
        ,
        (_: Any) => ZChannel.unit,
        (_: Any) =>
          val tail = step.onEnd()
          if tail.isEmpty then ZChannel.unit else ZChannel.writeChunk(Chunk(tail)),
      )

    step.init()
    loop

  def runChunk[A, B](free: FreeScan[Prim, A, B], inputs: Iterable[A]): Chunk[B] =
    val step    = build(Optimize.fuse(free))
    val builder = ChunkBuilder.make[B]()
    step.init()
    inputs.foreach { a =>
      val chunk = step.onElem(a)
      chunk.foreach(value => builder += value)
    }
    val tail    = step.onEnd()
    tail.foreach(value => builder += value)
    builder.result()

  private def build[A, B](free: FreeScan[Prim, A, B]): Step[A, B] =
    free match
      case _: FreeScan.Id[Prim, A] @unchecked =>
        new Step[A, B]:
          def init(): Unit           = ()
          def onElem(a: A): Chunk[B] = Chunk(a.asInstanceOf[B])
          def onEnd(): Chunk[B]      = Chunk.empty

      case embed: FreeScan.Embed[Prim, a, b] @unchecked =>
        val step: Step[a, b] =
          embed.prim match
            case Map1(f) =>
              new Step[a, b]:
                def init(): Unit            = ()
                def onElem(a0: a): Chunk[b] = Chunk(f(a0))
                def onEnd(): Chunk[b]       = Chunk.empty

            case Filter(p) =>
              new Step[a, b]:
                def init(): Unit            = ()
                def onElem(a0: a): Chunk[b] =
                  if p(a0) then Chunk(a0)
                  else Chunk.empty
                def onEnd(): Chunk[b]       = Chunk.empty

            case Flat(f) =>
              new Step[a, b]:
                def init(): Unit            = ()
                def onElem(a0: a): Chunk[b] = f(a0)
                def onEnd(): Chunk[b]       = Chunk.empty

            case fold: Fold[a, b, s] =>
              var state: s = null.asInstanceOf[s]
              new Step[a, b]:
                def init(): Unit            = state = fold.init()
                def onElem(a0: a): Chunk[b] =
                  val (next, out) = fold.step(state, a0)
                  state = next
                  out
                def onEnd(): Chunk[b]       = fold.flush(state)

        step.asInstanceOf[Step[A, B]]

      case seq: FreeScan.Seq[Prim, a, b, c] @unchecked =>
        val left: Step[a, b]  = build(seq.left)
        val right: Step[b, c] = build(seq.right)
        val step: Step[a, c]  =
          new Step[a, c]:
            def init(): Unit =
              left.init()
              right.init()

            def onElem(a0: a): Chunk[c] =
              val builder = ChunkBuilder.make[c]()
              val mid     = left.onElem(a0)
              mid.foreach { value =>
                val outs = right.onElem(value)
                outs.foreach(out => builder += out)
              }
              builder.result()

            def onEnd(): Chunk[c] =
              val builder = ChunkBuilder.make[c]()
              left.onEnd().foreach { value =>
                val outs = right.onElem(value)
                outs.foreach(out => builder += out)
              }
              val tail    = right.onEnd()
              tail.foreach(out => builder += out)
              builder.result()

        step.asInstanceOf[Step[A, B]]

      case par: FreeScan.Par[Prim, a, b, c, d, l, r] @unchecked =>
        given Tag[b]                                                     = par.outLeftTag
        given Tag[d]                                                     = par.outRightTag
        given LabelEvidence[l]                                           = par.leftLabel
        given LabelEvidence[r]                                           = par.rightLabel
        val left: Step[a, b]                                             = build(par.left)
        val right: Step[c, d]                                            = build(par.right)
        val step: Step[Tensor.Pair[l, r, a, c], Tensor.Pair[l, r, b, d]] =
          new Step[Tensor.Pair[l, r, a, c], Tensor.Pair[l, r, b, d]]:
            private var leftBuf: Chunk[b]  = Chunk.empty
            private var rightBuf: Chunk[d] = Chunk.empty

            def init(): Unit =
              left.init()
              right.init()
              leftBuf = Chunk.empty
              rightBuf = Chunk.empty

            private def emitPairs(): Chunk[Tensor.Pair[l, r, b, d]] =
              val available = math.min(leftBuf.length, rightBuf.length)
              if available == 0 then Chunk.empty
              else
                val builder = ChunkBuilder.make[Tensor.Pair[l, r, b, d]]()
                var idx     = 0
                while idx < available do
                  builder += Tensor.pack[l, r, b, d](leftBuf(idx), rightBuf(idx))
                  idx += 1
                leftBuf = leftBuf.drop(available)
                rightBuf = rightBuf.drop(available)
                builder.result()

            def onElem(in: Tensor.Pair[l, r, a, c]): Chunk[Tensor.Pair[l, r, b, d]] =
              leftBuf = leftBuf ++ left.onElem(Tensor.left(in))
              rightBuf = rightBuf ++ right.onElem(Tensor.right(in))
              emitPairs()

            def onEnd(): Chunk[Tensor.Pair[l, r, b, d]] =
              leftBuf = leftBuf ++ left.onEnd()
              rightBuf = rightBuf ++ right.onEnd()
              emitPairs()

        step.asInstanceOf[Step[A, B]]

final case class HiddenRes[R](acquire: UIO[R], release: R => UIO[Unit])

object CompileRes:
  def toChannel[A, B, R](
    resource: HiddenRes[R],
    adapt: (R, FreeScan[Prim, A, B]) => FreeScan[Prim, A, B],
    free: FreeScan[Prim, A, B],
  ): ZChannel[Any, Nothing, Chunk[A], Any, Nothing, Chunk[B], Unit] =
    ZChannel.unwrapScoped {
      ZIO.acquireRelease(resource.acquire)(resource.release).map { r =>
        Compile.toChannel(adapt(r, free))
      }
    }
