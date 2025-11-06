package graviton.core

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

import scala.collection.immutable.ListMap

import zio.*
import zio.Chunk
import zio.schema.{DeriveSchema, Schema, derived}

import graviton.Hash
import zio.schema.validation.Validation
// import graviton.BlockKey
import graviton.core.model.*

/**
 * Identifier for binary content. Keys can either be content addressed
 * (`CasKey`) or writable user supplied keys such as randomly generated UUIDs
 * or static strings.
 */
sealed trait BinaryKey derives Schema:
  import BinaryKey.*

  /** Render this key as a URI-like string. */
  def renderKey: String = this match
    case CasKey.BlockKey(hash, size)    => s"cas/block/${hash.algo.canonicalName}/${hash.hex}/$size"
    case CasKey.FileKey(hash, size)     => s"cas/file/${hash.algo.canonicalName}/${hash.hex}/$size"
    case WritableKey.Random(id)         => s"uuid/${id.toString}"
    case WritableKey.Static(name)       => s"user/$name"
    case WritableKey.Scoped(scope, key) =>
      val encoded =
        val joined = scope.map { case (k, v) => k + "__" + v.mkString("__") }.mkString("::")
        Base64.getEncoder.encodeToString(joined.getBytes(StandardCharsets.UTF_8))
      s"scoped:$encoded/$key"

object BinaryKey:

  given [K: Schema, A: Schema] => Schema[ListMap[K, A]] =
    Schema[Chunk[(K, A)]].transform(ListMap.from, Chunk.fromIterable)

  /** Content addressed key – represents the digest of some content. */
  enum CasKey[+S] extends BinaryKey:
    case BlockKey(hash: Hash, size: BlockSize) extends CasKey[BlockSize]
    case FileKey(hash: Hash, size: FileSize)   extends CasKey[FileSize]
  end CasKey

  /** Keys that can be written to by clients. */
  sealed trait WritableKey extends BinaryKey

  object WritableKey:
    private[graviton] final case class Random(id: UUID)    extends WritableKey
    private[graviton] final case class Static(key: String) extends WritableKey
    private[graviton] final case class Scoped(
      scope: ListMap[String, NonEmptyChunk[String]],
      key: String,
    ) extends WritableKey

    def random(id: UUID): WritableKey = Random(id)

    def randomF(using Trace): UIO[WritableKey] =
      zio.Random.nextUUID.map(Random(_))

    private def validateKey(key: String): Either[String, String] =
      Option(key)
        .toRight("WritableKey.static: key cannot be null")
        .flatMap(key => Option(key).filter(_.trim.nonEmpty).toRight("WritableKey.static: key must be non-empty"))

    def static(key: String): Either[String, WritableKey] =
      validateKey(key).map(Static(_))

    def scoped(
      scope: ListMap[String, NonEmptyChunk[String]],
      key: String,
    ): zio.prelude.Validation[String, WritableKey] =
      zio.prelude.Validation
        .validate(
          zio.prelude.Validation
            .fromPredicate(scope)(_.nonEmpty)
            .mapError(_ => "WritableKey.scoped: scope must be non-empty"),
          zio.prelude.Validation.fromEither(validateKey(key)),
        )
        .map { case (scope, value) => Scoped(scope.map((k, v) => (k.trim, v)), value.trim) }

  export WritableKey.{Random, Scoped, Static}

  object WritableKeySchemas:
    given Schema[WritableKey] = DeriveSchema.gen[WritableKey]
