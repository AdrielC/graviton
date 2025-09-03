package graviton.core

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

import scala.collection.immutable.ListMap

import zio.*
import zio.Chunk
import zio.schema.{DeriveSchema, Schema}

import graviton.Hash

/** Identifier for binary content. Keys can either be content addressed
  * (`CasKey`) or writable user supplied keys such as randomly generated UUIDs
  * or static strings.
  */
sealed trait BinaryKey:
  import BinaryKey.*

  /** Render this key as a URI-like string. */
  def renderKey: String = this match
    case CasKey(hash)        => s"hash://${hash.algo.canonicalName}/${hash.hex}"
    case WritableKey.Rnd(id) => s"uuid://${id.toString}"
    case WritableKey.Static(name) => s"user://$name"
    case WritableKey.Scoped(scope, key) =>
      val encoded =
        val joined = scope
          .map { case (k, v) => k + "__" + v.mkString("__") }
          .mkString("::")
        Base64.getEncoder.encodeToString(
          joined.getBytes(StandardCharsets.UTF_8)
        )
      s"scoped://$encoded/$key"

object BinaryKey:
  given [K: Schema, A: Schema]: Schema[ListMap[K, A]] =
    Schema[Chunk[(K, A)]].transform(ListMap.from, Chunk.fromIterable)

  /** Content addressed key – represents the digest of some content. */
  final case class CasKey(hash: Hash) extends BinaryKey

  /** Keys that can be written to by clients. */
  sealed trait WritableKey extends BinaryKey

  object WritableKey:
    final case class Rnd(id: UUID) extends WritableKey
    final case class Static(key: String) extends WritableKey
    final case class Scoped(
        scope: ListMap[String, NonEmptyChunk[String]],
        key: String
    ) extends WritableKey

    def random(id: UUID): WritableKey = Rnd(id)

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

  export WritableKey.{Rnd, Scoped, Static}

  given Schema[BinaryKey] = DeriveSchema.gen[BinaryKey]
  object WritableKeySchemas:
    given Schema[WritableKey] = DeriveSchema.gen[WritableKey]
