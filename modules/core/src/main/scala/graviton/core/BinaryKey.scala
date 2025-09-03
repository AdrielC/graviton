package graviton.core

import zio.*
import zio.schema.*
import java.util.UUID
import scala.collection.immutable.ListMap

sealed trait BinaryKey

object BinaryKey:

  enum Alg:
    case Blake3, Sha256
  final case class CasKey(alg: Alg, hash: Chunk[Byte]) extends BinaryKey

  sealed trait WritableKey extends BinaryKey
  object WritableKey:
    private final case class Rnd(id: UUID) extends WritableKey
    private final case class Static(key: String) extends WritableKey
    private final case class Scoped(
        scope: ListMap[String, NonEmptyChunk[String]],
        key: String
    ) extends WritableKey

    def random(id: UUID): WritableKey =
      Rnd(id)

    def randomF(using zio.Random): UIO[WritableKey] =
      zio.Random.nextUUID.map(Rnd.apply)

    def static(key: String): Either[String, WritableKey] =
      if key == null || key.trim.isEmpty then
        Left("WritableKey.static: key must be non-empty")
      else Right(Static(key.trim))

    def scoped(
        scope: ListMap[String, NonEmptyChunk[String]],
        key: String
    ): Either[String, WritableKey] =
      if key == null || key.trim.isEmpty then
        Left("WritableKey.scoped: key must be non-empty")
      else if scope.isEmpty then
        Left("WritableKey.scoped: scope must be non-empty")
      else if scope.exists { case (k, v) => k.trim.isEmpty || v.isEmpty } then
        Left("WritableKey.scoped: scope keys and value lists must be non-empty")
      else Right(Scoped(scope.map((k, v) => (k.trim, v)), key.trim))
