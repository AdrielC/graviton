package graviton.core.scan

import graviton.core.scan.FreeScan.*
import graviton.core.scan.Prim.*
import graviton.core.manifest.*
import graviton.core.bytes.*
import zio.*
import zio.stream.*

import scala.collection.mutable.ListBuffer

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
  final case class Map1[A, B](f: A => B)                                                                   extends Prim[A, B]
  final case class Filter[A](p: A => Boolean)                                                              extends Prim[A, A]
  final case class Flat[A, B](f: A => Iterable[B])                                                         extends Prim[A, B]
  final case class Fold[A, B, S](init: () => S, step: (S, A) => (S, Iterable[B]), flush: S => Iterable[B]) extends Prim[A, B]

/**
 * Volga-style combinator helpers and batteries-included primitives.
 */
object FS:

  def id[A]: FreeScan[Prim, A, A] = FreeScan.Id()

  def map[A, B](f: A => B): FreeScan[Prim, A, B] = FreeScan.Embed(Map1(f))

  def filter[A](p: A => Boolean): FreeScan[Prim, A, A] = FreeScan.Embed(Filter(p))

  def flat[A, B](f: A => Iterable[B]): FreeScan[Prim, A, B] = FreeScan.Embed(Flat(f))

  def fold[A, B, S](initial: => S)(step: (S, A) => (S, Iterable[B]))(flush: S => Iterable[B]): FreeScan[Prim, A, B] =
    FreeScan.Embed(Fold(() => initial, step, flush))

  def stateful[A, S](initial: => S)(step: (S, A) => S): FreeScan[Prim, A, S] =
    fold[A, S, S](initial) { (s, a) =>
      val next = step(s, a)
      (next, next :: Nil)
    }(_ => Nil)

  def counter[A]: FreeScan[Prim, A, Long] =
    fold[A, Long, Long](0L) { (count, _) =>
      val next = count + 1
      (next, next :: Nil)
    }(_ => Nil)

  def byteCounter: FreeScan[Prim, Chunk[Byte], Long] =
    fold[Chunk[Byte], Long, Long](0L) { (total, bytes) =>
      val next = total + bytes.length
      (next, next :: Nil)
    }(_ => Nil)

  def hashBytes(algo: HashAlgo): FreeScan[Prim, Chunk[Byte], Either[String, Digest]] =
    fold[Chunk[Byte], Either[String, Digest], Hasher](Hasher.memory(algo)) { (hasher, chunk) =>
      val updated = hasher.update(chunk.toArray)
      (updated, Nil)
    }(hasher => hasher.result :: Nil)

  def buildManifest: FreeScan[Prim, ManifestEntry, Manifest] =
    fold[ManifestEntry, Manifest, (List[ManifestEntry], Long)]((Nil, 0L)) { case ((entries, total), entry) =>
      val spanLength = entry.span.length
      val next       = (entry :: entries, total + spanLength)
      (next, Nil)
    } { case (entries, total) => Manifest(entries.reverse, total) :: Nil }

  def fixedChunker(frameBytes: Int): FreeScan[Prim, Chunk[Byte], Chunk[Byte]] =
    val safeSize = math.max(1, frameBytes)
    fold[Chunk[Byte], Chunk[Byte], (Array[Byte], Int)]((Array.ofDim[Byte](safeSize), 0)) { case ((buffer, filled), chunk) =>
      val out      = ListBuffer.empty[Chunk[Byte]]
      var writeIdx = filled
      var idx      = 0
      while idx < chunk.length do
        buffer(writeIdx) = chunk(idx)
        writeIdx += 1
        if writeIdx == safeSize then
          out += Chunk.fromArray(java.util.Arrays.copyOf(buffer, safeSize))
          writeIdx = 0
        idx += 1
      ((buffer, writeIdx), out.toList)
    } { case (buffer, filled) =>
      if filled == 0 then Nil
      else Chunk.fromArray(java.util.Arrays.copyOf(buffer, filled)) :: Nil
    }

  extension [A, B](left: FreeScan[Prim, A, B])
    infix def >>>[C](right: FreeScan[Prim, B, C]): FreeScan[Prim, A, C] = FreeScan.Seq(left, right)

    infix def ><[C, D](right: FreeScan[Prim, C, D]): FreeScan[Prim, (A, C), (B, D)] = FreeScan.Par(left, right)

    def optimize: FreeScan[Prim, A, B] = Optimize.fuse(left)

    def toChannel: ZChannel[Any, Nothing, Chunk[A], Any, Nothing, Chunk[B], Unit] =
      Compile.toChannel(left)

    def toPipeline: ZPipeline[Any, Nothing, A, B] = ZPipeline.fromChannel(Compile.toChannel(left))

    def runList(inputs: Iterable[A]): List[B] = Compile.runList(left, inputs)

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
  def onElem(a: A): List[B]
  def onEnd(): List[B]

private object Compile:

  def toChannel[A, B](free: FreeScan[Prim, A, B]): ZChannel[Any, Nothing, Chunk[A], Any, Nothing, Chunk[B], Unit] =
    val step = build(Optimize.fuse(free))

    lazy val loop: ZChannel[Any, Nothing, Chunk[A], Any, Nothing, Chunk[B], Unit] =
      ZChannel.readWith(
        (chunk: Chunk[A]) =>
          val buffer     = ListBuffer.empty[B]
          chunk.foreach { a =>
            val out = step.onElem(a)
            buffer ++= out
          }
          val writeChunk =
            if buffer.nonEmpty then ZChannel.write(Chunk.fromIterable(buffer))
            else ZChannel.unit
          writeChunk *> loop
        ,
        (_: Any) =>
          val tail = step.onEnd()
          if tail.nonEmpty then ZChannel.write(Chunk.fromIterable(tail))
          else ZChannel.unit
        ,
        (_: Any) => ZChannel.unit,
      )

    step.init()
    loop

  def runList[A, B](free: FreeScan[Prim, A, B], inputs: Iterable[A]): List[B] =
    val step    = build(Optimize.fuse(free))
    val builder = ListBuffer.empty[B]
    step.init()
    inputs.foreach(a => builder ++= step.onElem(a))
    builder ++= step.onEnd()
    builder.toList

  private def build[A, B](free: FreeScan[Prim, A, B]): Step[A, B] =
    free match
      case _: FreeScan.Id[Prim, A] @unchecked =>
        new Step[A, B]:
          def init(): Unit          = ()
          def onElem(a: A): List[B] = List(a.asInstanceOf[B])
          def onEnd(): List[B]      = Nil

      case embed: FreeScan.Embed[Prim, a, b] @unchecked =>
        val step: Step[a, b] =
          embed.prim match
            case Map1(f) =>
              new Step[a, b]:
                def init(): Unit           = ()
                def onElem(a0: a): List[b] = List(f(a0))
                def onEnd(): List[b]       = Nil

            case Filter(p) =>
              new Step[a, b]:
                def init(): Unit           = ()
                def onElem(a0: a): List[b] =
                  if p(a0) then List(a0)
                  else Nil
                def onEnd(): List[b]       = Nil

            case Flat(f) =>
              new Step[a, b]:
                def init(): Unit           = ()
                def onElem(a0: a): List[b] = f(a0).toList
                def onEnd(): List[b]       = Nil

            case fold: Fold[a, b, s] =>
              var state: s = null.asInstanceOf[s]
              new Step[a, b]:
                def init(): Unit           = state = fold.init()
                def onElem(a0: a): List[b] =
                  val (next, out) = fold.step(state, a0)
                  state = next
                  out.toList
                def onEnd(): List[b]       = fold.flush(state).toList

        step.asInstanceOf[Step[A, B]]

      case seq: FreeScan.Seq[Prim, a, b, c] @unchecked =>
        val left: Step[a, b]  = build(seq.left)
        val right: Step[b, c] = build(seq.right)
        val step: Step[a, c]  =
          new Step[a, c]:
            def init(): Unit =
              left.init()
              right.init()

            def onElem(a0: a): List[c] =
              val acc = ListBuffer.empty[c]
              val mid = left.onElem(a0)
              mid.foreach(value => acc ++= right.onElem(value))
              acc.toList

            def onEnd(): List[c] =
              val acc = ListBuffer.empty[c]
              left.onEnd().foreach(value => acc ++= right.onElem(value))
              acc ++= right.onEnd()
              acc.toList

        step.asInstanceOf[Step[A, B]]

      case par: FreeScan.Par[Prim, a, b, c, d] @unchecked =>
        val left: Step[a, b]           = build(par.left)
        val right: Step[c, d]          = build(par.right)
        val step: Step[(a, c), (b, d)] =
          new Step[(a, c), (b, d)]:
            private var leftBuf: List[b]  = Nil
            private var rightBuf: List[d] = Nil

            def init(): Unit =
              left.init()
              right.init()
              leftBuf = Nil
              rightBuf = Nil

            private def drain(): List[(b, d)] =
              val acc = ListBuffer.empty[(b, d)]
              while leftBuf.nonEmpty && rightBuf.nonEmpty do
                acc += ((leftBuf.head, rightBuf.head))
                leftBuf = leftBuf.tail
                rightBuf = rightBuf.tail
              acc.toList

            def onElem(in: (a, c)): List[(b, d)] =
              val (la, rc) = in
              leftBuf = leftBuf ::: left.onElem(la)
              rightBuf = rightBuf ::: right.onElem(rc)
              drain()

            def onEnd(): List[(b, d)] =
              leftBuf = leftBuf ::: left.onEnd()
              rightBuf = rightBuf ::: right.onEnd()
              drain()

        step.asInstanceOf[Step[A, B]]

      case _: FreeScan.Bra[Prim, a, b] @unchecked =>
        val step: Step[(a, b), (b, a)] =
          new Step[(a, b), (b, a)]:
            def init(): Unit                       = ()
            def onElem(pair: (a, b)): List[(b, a)] = List((pair._2, pair._1))
            def onEnd(): List[(b, a)]              = Nil

        step.asInstanceOf[Step[A, B]]

      case _: FreeScan.AssocL[Prim, a, b, c] @unchecked =>
        val step: Step[(a, (b, c)), ((a, b), c)] =
          new Step[(a, (b, c)), ((a, b), c)]:
            def init(): Unit                                  = ()
            def onElem(value: (a, (b, c))): List[((a, b), c)] =
              val (a0, (b0, c0)) = value
              List(((a0, b0), c0))
            def onEnd(): List[((a, b), c)]                    = Nil

        step.asInstanceOf[Step[A, B]]

      case _: FreeScan.AssocR[Prim, a, b, c] @unchecked =>
        val step: Step[((a, b), c), (a, (b, c))] =
          new Step[((a, b), c), (a, (b, c))]:
            def init(): Unit                                  = ()
            def onElem(value: ((a, b), c)): List[(a, (b, c))] =
              val ((a0, b0), c0) = value
              List((a0, (b0, c0)))
            def onEnd(): List[(a, (b, c))]                    = Nil

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
