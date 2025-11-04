package graviton.core.scan

import graviton.core.scan.FreeScan.*
import graviton.core.scan.Prim.*
import graviton.core.manifest.*
import graviton.core.bytes.*
import zio.*
import zio.stream.*
import zio.ChunkBuilder

/**
 * Symmetric monoidal product (⊗) abstraction for scan inputs/outputs.
 */
trait Prod:
  type ⊗[A, B]
  def tup[A, B](a: A, b: B): A ⊗ B
  def fst[A, B](p: A ⊗ B): A
  def snd[A, B](p: A ⊗ B): B

object Prod:
  given tuples: Prod with
    type ⊗[A, B] = (A, B)
    def tup[A, B](a: A, b: B): (A, B) = (a, b)
    def fst[A, B](p: (A, B)): A       = p._1
    def snd[A, B](p: (A, B)): B       = p._2

/**
 * Symmetric monoidal sum (⊕) abstraction for future ArrowChoice support.
 */
trait Sum:
  type ⊕[A, B]
  def inl[A, B](a: A): A ⊕ B
  def inr[A, B](b: B): A ⊕ B
  def fold[A, B, C](value: A ⊕ B)(fa: A => C, fb: B => C): C

object Sum:
  given either: Sum with
    type ⊕[A, B] = Either[A, B]
    def inl[A, B](a: A): Either[A, B]                              = Left(a)
    def inr[A, B](b: B): Either[A, B]                              = Right(b)
    def fold[A, B, C](value: Either[A, B])(fa: A => C, fb: B => C) = value.fold(fa, fb)

/**
 * Splitting / zipping helper for state threading.
 */
trait SZip[S, L, R]:
  def split(state: S): (L, R)
  def join(left: L, right: R): S

object SZip:
  given pair[L, R]: SZip[(L, R), L, R] with
    def split(state: (L, R)): (L, R)    = state
    def join(left: L, right: R): (L, R) = (left, right)

/**
 * Object universe for Volga-style scans.
 */
sealed trait UObj[A]

object UObj:
  final case class Scala[A]()                                  extends UObj[A]
  case object One                                              extends UObj[Unit]
  final case class Tensor[A, B](left: UObj[A], right: UObj[B]) extends UObj[(A, B)]

/**
 * Free symmetric monoidal category over primitive scan alphabet `Q`.
 */
sealed trait FreeScan[+Q[_, _], A, B]

object FreeScan:
  final case class Id[Q[_, _], A]()                                                            extends FreeScan[Q, A, A]
  final case class Embed[Q[_, _], A, B](prim: Q[A, B])                                         extends FreeScan[Q, A, B]
  final case class Seq[Q[_, _], A, B, C](left: FreeScan[Q, A, B], right: FreeScan[Q, B, C])    extends FreeScan[Q, A, C]
  final case class Par[Q[_, _], A, B, C, D](left: FreeScan[Q, A, B], right: FreeScan[Q, C, D]) extends FreeScan[Q, (A, C), (B, D)]
  final case class Bra[Q[_, _], A, B]()                                                        extends FreeScan[Q, (A, B), (B, A)]
  final case class AssocL[Q[_, _], A, B, C]()                                                  extends FreeScan[Q, (A, (B, C)), ((A, B), C)]
  final case class AssocR[Q[_, _], A, B, C]()                                                  extends FreeScan[Q, ((A, B), C), (A, (B, C))]

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
    fold[Chunk[Byte], Either[String, Digest], Hasher](Hasher.memory(algo)) { (hasher, chunk) =>
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

  extension [A, B](left: FreeScan[Prim, A, B])
    infix def >>>[C](right: FreeScan[Prim, B, C]): FreeScan[Prim, A, C] = FreeScan.Seq(left, right)

    infix def ><[C, D](right: FreeScan[Prim, C, D]): FreeScan[Prim, (A, C), (B, D)] = FreeScan.Par(left, right)

    def optimize: FreeScan[Prim, A, B] = Optimize.fuse(left)

    def toChannel: ZChannel[Any, Nothing, Chunk[A], Any, Nothing, Chunk[B], Unit] = Compile.toChannel(left)

    def toPipeline: ZPipeline[Any, Nothing, A, B] = ZPipeline.fromChannel(Compile.toChannel(left))

    def runChunk(inputs: Iterable[A]): Chunk[B] = Compile.runChunk(left, inputs)

    def runList(inputs: Iterable[A]): List[B] = runChunk(inputs).toList

/**
 * Peephole optimizer for local primitive fusion.
 */
object Optimize:

  def fuse[A, B](scan: FreeScan[Prim, A, B]): FreeScan[Prim, A, B] =
    scan match
      case seq: FreeScan.Seq[Prim, a, b, c] @unchecked    =>
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
      case par: FreeScan.Par[Prim, a, b, c, d] @unchecked =>
        FreeScan.Par(fuse(par.left), fuse(par.right)).asInstanceOf[FreeScan[Prim, A, B]]
      case other                                          => other

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

      case par: FreeScan.Par[Prim, a, b, c, d] @unchecked =>
        val left: Step[a, b]           = build(par.left)
        val right: Step[c, d]          = build(par.right)
        val step: Step[(a, c), (b, d)] =
          new Step[(a, c), (b, d)]:
            private var leftBuf: Chunk[b]  = Chunk.empty
            private var rightBuf: Chunk[d] = Chunk.empty

            def init(): Unit =
              left.init()
              right.init()
              leftBuf = Chunk.empty
              rightBuf = Chunk.empty

            private def emitPairs(): Chunk[(b, d)] =
              val available = math.min(leftBuf.length, rightBuf.length)
              if available == 0 then Chunk.empty
              else
                val builder = ChunkBuilder.make[(b, d)]()
                var idx     = 0
                while idx < available do
                  builder += ((leftBuf(idx), rightBuf(idx)))
                  idx += 1
                leftBuf = leftBuf.drop(available)
                rightBuf = rightBuf.drop(available)
                builder.result()

            def onElem(in: (a, c)): Chunk[(b, d)] =
              val (la, rc) = in
              leftBuf = leftBuf ++ left.onElem(la)
              rightBuf = rightBuf ++ right.onElem(rc)
              emitPairs()

            def onEnd(): Chunk[(b, d)] =
              leftBuf = leftBuf ++ left.onEnd()
              rightBuf = rightBuf ++ right.onEnd()
              emitPairs()

        step.asInstanceOf[Step[A, B]]

      case _: FreeScan.Bra[Prim, a, b] @unchecked =>
        val step: Step[(a, b), (b, a)] =
          new Step[(a, b), (b, a)]:
            def init(): Unit                        = ()
            def onElem(pair: (a, b)): Chunk[(b, a)] = Chunk((pair._2, pair._1))
            def onEnd(): Chunk[(b, a)]              = Chunk.empty

        step.asInstanceOf[Step[A, B]]

      case _: FreeScan.AssocL[Prim, a, b, c] @unchecked =>
        val step: Step[(a, (b, c)), ((a, b), c)] =
          new Step[(a, (b, c)), ((a, b), c)]:
            def init(): Unit                                   = ()
            def onElem(value: (a, (b, c))): Chunk[((a, b), c)] =
              val (a0, (b0, c0)) = value
              Chunk(((a0, b0), c0))
            def onEnd(): Chunk[((a, b), c)]                    = Chunk.empty

        step.asInstanceOf[Step[A, B]]

      case _: FreeScan.AssocR[Prim, a, b, c] @unchecked =>
        val step: Step[((a, b), c), (a, (b, c))] =
          new Step[((a, b), c), (a, (b, c))]:
            def init(): Unit                                   = ()
            def onElem(value: ((a, b), c)): Chunk[(a, (b, c))] =
              val ((a0, b0), c0) = value
              Chunk((a0, (b0, c0)))
            def onEnd(): Chunk[(a, (b, c))]                    = Chunk.empty

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
