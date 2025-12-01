package graviton
package core

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

import scala.collection.immutable.ListMap
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.refineEither
import zio.*
import zio.{Chunk, NonEmptyChunk}
import zio.schema.{DeriveSchema, Schema, derived}
import zio.prelude.ZValidation as ZIOValidation

import graviton.Hash
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
    case CasKey.BlockKey(hash, size)    => s"cas/block/${hash.bytes.algo.canonicalName}/${hash.bytes.bytes.hex}/$size"
    case CasKey.FileKey(hash, size)     => s"cas/file/${hash.algo.canonicalName}/${hash.bytes.bytes.hex}/$size"
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
  enum CasKey[+S <: Sized[?, ?, ?]#T] extends BinaryKey:
    case BlockKey(hash: Hash.SingleHash, size: BlockSize) extends CasKey[BlockSize] 
    case FileKey(hash: Hash.SingleHash, size: FileSize)   extends CasKey[FileSize]
  end CasKey

  object CasKey:
    final case class BlockKeyT(hash: Hash.SingleHash, size: BlockSize)
    final case class FileKeyT(hash: Hash.SingleHash, size: FileSize)
    given Schema[CasKey.BlockKey] = DeriveSchema.gen[CasKey.BlockKeyT]
      .transform(h => CasKey.BlockKey(h.hash, h.size), b => CasKey.BlockKeyT(hash = b.hash, size = b.size))

    given Schema[CasKey.FileKey] = DeriveSchema.gen[CasKey.FileKeyT]
      .transform(h => CasKey.FileKey(h.hash, h.size), b => CasKey.FileKeyT(hash = b.hash, size = b.size))
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
        .flatMap(key => key.refineEither[Not[Empty] & Match["^[^/]+$"] & Not[EndWith["/"]] & Match["^"]])

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
  end WritableKey

  object const:
    type IsProtocol = Match["^file|s3|http|https|ftp|ftps|sftp|scp|rsync|smb|nfs|cifs|afp|file|gs|az|ad|ssh|telnet|graviton|jdbc$"]
    type IsBucket   = Match["^[^/]+$"]
    type IsPath     = Match["^[^/a-zA-Z0-9._-]+$"]
    type IsNonEmpty = Length[Greater[0]]
  end const

  type ProtocolString = ProtocolString.T
  object ProtocolString extends SubtypeExt[String, const.IsProtocol]

  type BucketString = BucketString.T
  object BucketString extends SubtypeExt[String, const.IsBucket]

  type PathString = PathString.T
  object PathString extends SubtypeExt[String, const.IsPath]

  export WritableKey.{Random, Scoped, Static}

  object WritableKeySchemas:
    given Schema[WritableKey] = DeriveSchema.gen[WritableKey]

import BinaryKey.{ProtocolString, BucketString, PathString}

final case class BlobLocator(protocol: ProtocolString, bucket: BucketString, path: NonEmptyChunk[PathString])

object BlobLocator:
  inline def apply(protocol: String, bucket: String, path: String*): zio.prelude.ZValidation[Nothing, String, BlobLocator] =

    val protocolValidation = ZIOValidation.fromEither(ProtocolString.either(protocol))
    val bucketValidation   = ZIOValidation.fromEither(BucketString.either(bucket))
    val pathValidation     = ZIOValidation.fromEither(refinePathSegments(path.toList))

    ZIOValidation
      .validate(protocolValidation, bucketValidation, pathValidation)
      .map { case (proto, buck, segments) =>
        BlobLocator(proto, buck, segments)
      }

  private def refinePathSegments(segments: List[String]): Either[String, NonEmptyChunk[PathString]] =
    segments match
      case Nil          => Left("BlobLocator: at least one path segment is required")
      case head :: tail =>
        for
          refinedHead <- PathString.either(head)
          refinedTail <- tail
                           .foldLeft[Either[String, List[PathString]]](Right(Nil)) { (acc, segment) =>
                             acc.flatMap(list => PathString.either(segment).map(refined => refined :: list))
                           }
                           .map(_.reverse)
        yield NonEmptyChunk(refinedHead, refinedTail*)
