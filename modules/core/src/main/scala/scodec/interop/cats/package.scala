package scodec.interop

import scala.annotation.tailrec

import scodec.bits.*
import scodec.*

import _root_.cats.kernel.{Eq, Monoid, Semigroup}

import _root_.cats.*
import _root_.cats.implicits.*

package object cats:

  private[cats] abstract class CatsInstancesLowPriority {
    given DecoderSemigroupInstance: [A] => (Semigroup[A]) => Semigroup[Decoder[A]] =
      new DecoderSemigroup[A]()
  }

  private[cats] abstract class CatsInstances extends CatsInstancesLowPriority {

    given BitVectorOrderInstance: Order[BitVector]   = Order.fromComparable[BitVector]
    given BitVectorShowInstance: Show[BitVector]     = Show.fromToString
    given BitVectorMonoidInstance: Monoid[BitVector] = new Monoid[BitVector] {
      def empty                               = BitVector.empty
      def combine(x: BitVector, y: BitVector) = x ++ y
    }

    given ByteVectorOrderInstance: Order[ByteVector]   = Order.fromComparable[ByteVector]
    given ByteVectorShowInstance: Show[ByteVector]     = Show.fromToString
    given ByteVectorMonoidInstance: Monoid[ByteVector] = new Monoid[ByteVector] {
      def empty                                 = ByteVector.empty
      def combine(x: ByteVector, y: ByteVector) = x ++ y
    }

    given ErrEqInstance: Eq[Err]     = Eq.fromUniversalEquals
    given ErrShowInstance: Show[Err] = Show.fromToString

    given AttemptMonadErrorTraverseInstance: MonadError[Attempt, Err] & Traverse[Attempt] =
      new MonadError[Attempt, Err] with Traverse[Attempt] {
        def pure[A](a: A)                                            = Attempt.successful(a)
        def flatMap[A, B](fa: Attempt[A])(f: A => Attempt[B])        = fa.flatMap(f)
        def raiseError[A](e: Err)                                    = Attempt.failure(e)
        def handleErrorWith[A](fa: Attempt[A])(f: Err => Attempt[A]) =
          fa.fold(f, Attempt.successful)

        @tailrec
        override def tailRecM[A, B](a: A)(f: A => Attempt[Either[A, B]]): Attempt[B] =
          f(a) match {
            case fail @ Attempt.Failure(_)    => fail
            case Attempt.Successful(Left(a))  => tailRecM(a)(f)
            case Attempt.Successful(Right(b)) => Attempt.Successful(b)
          }

        def traverse[G[_], A, B](
          fa: Attempt[A]
        )(f: A => G[B])(using G: Applicative[G]): G[Attempt[B]] =
          fa match {
            case fail @ Attempt.Failure(_) => G.pure(fail)
            case Attempt.Successful(value) => G.map(f(value))(Attempt.Successful(_))
          }

        def foldLeft[A, B](fa: Attempt[A], b: B)(f: (B, A) => B): B = fa match {
          case Attempt.Failure(_)        => b
          case Attempt.Successful(value) => f(b, value)
        }

        def foldRight[A, B](fa: Attempt[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
          fa match {
            case Attempt.Failure(_)        => lb
            case Attempt.Successful(value) => f(value, lb)
          }
      }

    private[cats] val AttemptMonadErrorInstance: MonadError[Attempt, Err] =
      AttemptMonadErrorTraverseInstance

    given AttemptEqInstance: [A: Eq] => Eq[Attempt[A]] = Eq.instance { (l, r) =>
      l match {
        case Attempt.Successful(la) =>
          r match {
            case Attempt.Successful(ra) => Eq[A].eqv(la, ra)
            case Attempt.Failure(re)    => false
          }
        case Attempt.Failure(le)    =>
          r match {
            case Attempt.Successful(ra) => false
            case Attempt.Failure(re)    => Eq[Err].eqv(le, re)
          }
      }
    }

    given AttemptShowInstance: [A: Show] => Show[Attempt[A]] = Show.show {
      case Attempt.Successful(a) => show"Successful($a)"
      case Attempt.Failure(e)    => show"Successful($e)"
    }

    given DecodeResultTraverseComonadInstance: Traverse[DecodeResult] & Comonad[DecodeResult] = new Traverse[DecodeResult]
      with Comonad[DecodeResult] {
      def extract[A](fa: DecodeResult[A])                                                        = fa.value
      def coflatMap[A, B](fa: DecodeResult[A])(f: DecodeResult[A] => B)                          = DecodeResult(f(fa), fa.remainder)
      override def map[A, B](fa: DecodeResult[A])(f: A => B)                                     = fa.map(f)
      def traverse[G[_], A, B](fa: DecodeResult[A])(f: A => G[B])(using G: Applicative[G])       =
        G.map(f(fa.value))(b => DecodeResult(b, fa.remainder))
      def foldLeft[A, B](fa: DecodeResult[A], b: B)(f: (B, A) => B): B                           = f(b, fa.value)
      def foldRight[A, B](fa: DecodeResult[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = f(fa.value, lb)
    }
    given DecodeResultEqInstance: [A: Eq] => Eq[DecodeResult[A]]                              = Eq.instance { (l, r) =>
      l.value === r.value && l.remainder === r.remainder
    }

    given DecodeResultShowInstance: [A: Show] => Show[DecodeResult[A]] = Show.show { res =>
      s"DecodeResult(${res.value.show},${res.remainder.show})"
    }

    given DecoderMonadErrorInstance: MonadError[Decoder, Err] = new MonadError[Decoder, Err] {

      def pure[A](a: A) = new Decoder[A] {
        def decode(b: BitVector) = Attempt.successful(DecodeResult(a, b))
      }

      def flatMap[A, B](fa: Decoder[A])(f: A => Decoder[B]) = fa.flatMap(f)

      override def tailRecM[A, B](a: A)(f: A => Decoder[Either[A, B]]): Decoder[B] = new Decoder[B] {
        override def decode(bits: BitVector): Attempt[DecodeResult[B]] =
          f(a).decode(bits).flatMap { (dr: DecodeResult[Either[A, B]]) =>
            AttemptMonadErrorInstance.tailRecM(dr) { (dr: DecodeResult[Either[A, B]]) =>
              dr.value match {
                case Left(a)  => f(a).decode(dr.remainder).map(Left(_))
                case Right(b) => Attempt.successful(Right(DecodeResult(b, dr.remainder)))
              }
            }
          }
      }

      def raiseError[A](e: Err): Decoder[A] = new Decoder[A] {
        def decode(bits: BitVector): Attempt[DecodeResult[A]] = Attempt.failure(e)
      }

      def handleErrorWith[A](fa: Decoder[A])(f: Err => Decoder[A]): Decoder[A] = new Decoder[A] {
        def decode(bits: BitVector): Attempt[DecodeResult[A]] =
          fa.decode(bits).fold(f(_).decode(bits), Attempt.Successful(_))
      }
    }

    private[cats] val DecoderMonadInstance: Monad[Decoder] = DecoderMonadErrorInstance

    given DecoderMonoidInstance: [A] => (A: Monoid[A]) => Monoid[Decoder[A]] =
      new DecoderSemigroup[A]() with Monoid[Decoder[A]] {
        def empty = DecoderMonadErrorInstance.pure(A.empty)
      }

    given DecoderShowInstance: [A] => Show[Decoder[A]] = Show.fromToString

    given EncoderContravariantInstance: Contravariant[Encoder] = new Contravariant[Encoder] {
      def contramap[A, B](fa: Encoder[A])(f: B => A): Encoder[B] = fa.contramap(f)
    }
    given EncoderShowInstance: [A] => Show[Encoder[A]]         = Show.fromToString

    given CodecInvariantInstance: Invariant[Codec] = new Invariant[Codec] {
      def imap[A, B](fa: Codec[A])(f: A => B)(g: B => A): Codec[B] =
        fa.xmap(f, g)
    }
    given CodecShowInstance: [A] => Show[Codec[A]] = Show.fromToString
  }

  private class DecoderSemigroup[A](using A: Semigroup[A]) extends Semigroup[Decoder[A]] {
    def combine(x: Decoder[A], y: Decoder[A]) = new Decoder[A] {
      private lazy val yy         = y
      def decode(bits: BitVector) = (for {
        first  <- x
        second <- yy
      } yield A.combine(first, second)).decode(bits)
    }
  }

end cats
