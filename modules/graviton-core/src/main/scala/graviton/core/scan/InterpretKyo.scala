package graviton.core.scan

import graviton.core.scan.FreeScan.*
import graviton.core.scan.Prim.*
import graviton.core.scan.SafeFunction.*
import graviton.core.scan.SafeFunction.given
import kyo.Tag

import scala.collection.mutable

/**
 * A small, pure interpreter for `FreeScan` that runs on `kyo.Chunk`.
 *
 * This is not a streaming `kyo.Stream` interpreter yet; it's a Kyo-friendly execution
 * mode that:
 * - uses **kyo.Tag** / **kyo.Record** for tensor products
 * - preserves `Fold.flush` semantics (so chunkers/hashes/manifest builders work)
 */
object InterpretKyo:

  def runChunk[A, B](scan: FreeScan[Prim, A, B], inputs: kyo.Chunk[A]): kyo.Chunk[B] =
    val step    = build(Optimize.fuse(scan))
    val builder = List.newBuilder[B]
    step.init()

    inputs.foreach { a =>
      step.onElem(a).foreach(builder += _)
    }

    step.onEnd().foreach(builder += _)
    kyo.Chunk.from(builder.result())

  private trait Step[-A, +B]:
    def init(): Unit
    def onElem(a: A): List[B]
    def onEnd(): List[B]

  private def build[A, B](free: FreeScan[Prim, A, B]): Step[A, B] =
    free match
      case _: FreeScan.Id[Prim, A] @unchecked =>
        new Step[A, B]:
          def init(): Unit     = ()
          def onElem(a: A)     = a.asInstanceOf[B] :: Nil
          def onEnd(): List[B] = Nil

      case embed: FreeScan.Embed[Prim, a, b] @unchecked =>
        val step: Step[a, b] =
          embed.prim match
            case Map1(f) =>
              new Step[a, b]:
                def init(): Unit     = ()
                def onElem(a0: a)    = f(a0) :: Nil
                def onEnd(): List[b] = Nil

            case Filter(p) =>
              new Step[a, b]:
                def init(): Unit     = ()
                def onElem(a0: a)    = if p(a0) then a0.asInstanceOf[b] :: Nil else Nil
                def onEnd(): List[b] = Nil

            case Flat(f) =>
              new Step[a, b]:
                def init(): Unit     = ()
                def onElem(a0: a)    = f(a0).toList
                def onEnd(): List[b] = Nil

            case fold: Fold[a, b, s] =>
              var state: s = null.asInstanceOf[s]
              new Step[a, b]:
                def init(): Unit     = state = fold.init()
                def onElem(a0: a)    =
                  val (next, out) = fold.step(state, a0)
                  state = next
                  out.toList
                def onEnd(): List[b] = fold.flush(state).toList

        step.asInstanceOf[Step[A, B]]

      case seq: FreeScan.Seq[Prim, a, b, c] @unchecked =>
        val left: Step[a, b]  = build(seq.left)
        val right: Step[b, c] = build(seq.right)
        new Step[A, B]:
          def init(): Unit =
            left.init()
            right.init()

          def onElem(in: A): List[B] =
            val out = List.newBuilder[c]
            left
              .onElem(in.asInstanceOf[a])
              .foreach { mid =>
                right.onElem(mid).foreach(out += _)
              }
            out.result().asInstanceOf[List[B]]

          def onEnd(): List[B] =
            val out = List.newBuilder[c]
            left.onEnd().foreach { mid =>
              right.onElem(mid).foreach(out += _)
            }
            right.onEnd().foreach(out += _)
            out.result().asInstanceOf[List[B]]

      case par: FreeScan.Par[Prim, a, b, c, d, l, r] @unchecked =>
        given Tag[a]     = par.inLeftTag
        given Tag[b]     = par.outLeftTag
        given Tag[c]     = par.inRightTag
        given Tag[d]     = par.outRightTag
        given ValueOf[l] = par.leftLabel
        given ValueOf[r] = par.rightLabel

        val left: Step[a, b]  = build(par.left)
        val right: Step[c, d] = build(par.right)

        new Step[Tensor.Pair[l, r, a, c], Tensor.Pair[l, r, b, d]]:
          private val leftBuf  = mutable.ArrayDeque.empty[b]
          private val rightBuf = mutable.ArrayDeque.empty[d]

          def init(): Unit =
            left.init()
            right.init()
            leftBuf.clear()
            rightBuf.clear()

          private def emitPairs(): List[Tensor.Pair[l, r, b, d]] =
            val available = math.min(leftBuf.size, rightBuf.size)
            if available == 0 then Nil
            else
              val out = List.newBuilder[Tensor.Pair[l, r, b, d]]
              var idx = 0
              while idx < available do
                out += Tensor.pack[l, r, b, d](leftBuf.removeHead(), rightBuf.removeHead())
                idx += 1
              out.result()

          def onElem(in: Tensor.Pair[l, r, a, c]): List[Tensor.Pair[l, r, b, d]] =
            left.onElem(Tensor.left(in)).foreach(leftBuf.append)
            right.onElem(Tensor.right(in)).foreach(rightBuf.append)
            emitPairs()

          def onEnd(): List[Tensor.Pair[l, r, b, d]] =
            left.onEnd().foreach(leftBuf.append)
            right.onEnd().foreach(rightBuf.append)
            emitPairs()
