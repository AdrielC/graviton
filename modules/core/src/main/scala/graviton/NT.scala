package graviton

import scala.compiletime.{constValue, constValueTuple, summonInline}
import scala.deriving.Mirror
import zio.Chunk

/**
 * Named Tuple utilities for typed splits, carry slots, and type-driven labels.
 */
object NT:
  inline def splitAB[A <: Tuple, B <: Tuple](ab: Tuple.Concat[A, B]): (A, B) =
    inline val n: Int = constValue[Tuple.Size[A]]
    val parts          = ab.splitAt(n)
    (parts._1.asInstanceOf[A], parts._2.asInstanceOf[B])

  type Carry[C] = (carry: Option[C])

  inline def labelsOf[S <: Tuple]: Chunk[String] =
    val m     = summonInline[Mirror.ProductOf[S]]
    val t     = constValueTuple[m.MirroredElemLabels]
    val iter  = t.asInstanceOf[Product].productIterator
    val array = iter.asInstanceOf[Iterator[String]].toArray
    Chunk.fromArray(array)

