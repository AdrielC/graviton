package graviton

import scala.annotation.tailrec
import scala.compiletime.constValue

/** A runtime register for a tuple of state values stored in an Array[Any].
  *\n  *  It provides constant-time indexed access without constructing new tuples,
  *  enabling stateful stream transformations without allocation overhead.
  */
final class TypeRegister[S <: Tuple] private (private val arr: Array[Any], private val offset: Int):
  inline def getAt[N <: Int, A]: A =
    arr(offset + constValue[N]).asInstanceOf[A]

  inline def setAt[N <: Int, A](a: A): Unit =
    arr(offset + constValue[N]) = a.asInstanceOf[Any]

  inline def split[A <: Tuple, B <: Tuple](using ev: S =:= Tuple.Concat[A, B]): (TypeRegister[A], TypeRegister[B]) =
    val sizeA = constValue[Tuple.Size[A]]
    (new TypeRegister[A](arr, offset), new TypeRegister[B](arr, offset + sizeA))

object TypeRegister:
  def init[S <: Tuple](values: S): TypeRegister[S] =
    val arr = new Array[Any](size(values))
    fill(values, arr, 0)
    new TypeRegister[S](arr, 0)

  @tailrec private def fill(t: Tuple, arr: Array[Any], i: Int): Unit = t match
    case EmptyTuple => ()
    case h *: tail =>
      arr(i) = h
      fill(tail, arr, i + 1)

  @tailrec private def size(t: Tuple, acc: Int = 0): Int = t match
    case EmptyTuple   => acc
    case _ *: tail => size(tail, acc + 1)
