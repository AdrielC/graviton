package graviton.core

import zio.*
import zio.schema.*
import scala.collection.immutable.ListMap

sealed trait BinaryKeyMatcher
object BinaryKeyMatcher:
  case object AnyKey extends BinaryKeyMatcher
  final case class ByAlg(alg: BinaryKey.Alg) extends BinaryKeyMatcher
  final case class ByCasPrefix(prefix: Chunk[Byte]) extends BinaryKeyMatcher
  final case class ByScope(prefix: ListMap[String, Option[String]])
      extends BinaryKeyMatcher
  final case class And(left: BinaryKeyMatcher, right: BinaryKeyMatcher)
      extends BinaryKeyMatcher
  final case class Or(left: BinaryKeyMatcher, right: BinaryKeyMatcher)
      extends BinaryKeyMatcher
  final case class Not(inner: BinaryKeyMatcher) extends BinaryKeyMatcher

  extension (m: BinaryKeyMatcher)
    def &&(that: BinaryKeyMatcher): BinaryKeyMatcher = And(m, that)
    def ||(that: BinaryKeyMatcher): BinaryKeyMatcher = Or(m, that)
    def unary_! : BinaryKeyMatcher = Not(m)
