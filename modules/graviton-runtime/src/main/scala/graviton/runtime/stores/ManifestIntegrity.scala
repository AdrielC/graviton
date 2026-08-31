package graviton.runtime.stores

import graviton.core.RefinedTypeExt
import graviton.core.keys.BinaryKey
import graviton.core.manifest.ManifestEntry
import graviton.core.types.FileSize
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import zio.{Chunk, IO, UIO, ZIO}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

type ManifestChunkerId = ManifestChunkerId.T
object ManifestChunkerId extends RefinedTypeExt[String, MinLength[1] & MaxLength[120]]

type ManifestKeyId = ManifestKeyId.T
object ManifestKeyId extends RefinedTypeExt[String, graviton.core.types.IdentifierConstraint & MaxLength[120]]

/** Semantic manifest identity authenticated independently of block payloads. */
final case class ManifestIdentity(
  blob: BinaryKey.Blob,
  totalSize: FileSize,
  blockCount: Int,
  chunker: ManifestChunkerId,
)

/** Versioned proof persisted atomically with a manifest. */
final case class ManifestProof private (
  version: Int,
  keyId: ManifestKeyId,
  canonicalDigest: Chunk[Byte],
  signature: Chunk[Byte],
)

object ManifestProof:
  val CurrentVersion = 2
  val DigestBytes    = 32
  val SignatureBytes = 32

  def make(
    version: Int,
    keyId: ManifestKeyId,
    canonicalDigest: Chunk[Byte],
    signature: Chunk[Byte],
  ): Either[String, ManifestProof] =
    for
      _ <- Either.cond(version == CurrentVersion, (), s"unsupported manifest proof version $version")
      _ <- Either.cond(canonicalDigest.length == DigestBytes, (), s"manifest canonical digest must be $DigestBytes bytes")
      _ <- Either.cond(signature.length == SignatureBytes, (), s"manifest signature must be $SignatureBytes bytes")
    yield ManifestProof(version, keyId, canonicalDigest, signature)

final case class ManifestEnvelope(identity: ManifestIdentity, proof: ManifestProof)
final case class StoredManifestAuthentication(chunker: ManifestChunkerId, proof: ManifestProof)

/** Signing boundary suitable for local HMAC, KMS, or HSM implementations. */
trait ManifestKeyService:
  def activeKeyId: UIO[ManifestKeyId]
  def sign(canonicalDigest: Chunk[Byte]): IO[ManifestKeyService.Error, Chunk[Byte]]
  def verify(keyId: ManifestKeyId, canonicalDigest: Chunk[Byte], signature: Chunk[Byte]): IO[ManifestKeyService.Error, Unit]

object ManifestKeyService:
  sealed abstract class Error(message: String, cause: Throwable | Null = null) extends Exception(message, cause)
  object Error:
    final case class UnknownKey(keyId: ManifestKeyId)       extends Error(s"manifest verification key '${keyId.value}' is unavailable")
    final case class SigningFailure(underlying: Throwable)  extends Error("manifest signing failed", underlying)
    final case class InvalidSignature(keyId: ManifestKeyId) extends Error(s"manifest signature from key '${keyId.value}' is invalid")

  final class HmacKey private (private[ManifestKeyService] val bytes: Array[Byte]):
    override def toString: String = "HmacKey(<redacted>)"

  object HmacKey:
    def fromBytes(bytes: Array[Byte]): Either[String, HmacKey] =
      Either.cond(bytes.length >= 32 && bytes.length <= 64, new HmacKey(bytes.clone()), "manifest HMAC key must be 32..64 bytes")

    def generate: UIO[HmacKey] = ZIO.succeed {
      val bytes = new Array[Byte](32)
      new SecureRandom().nextBytes(bytes)
      new HmacKey(bytes)
    }

  def hmac(active: ManifestKeyId, keys: Map[ManifestKeyId, HmacKey]): Either[String, ManifestKeyService] =
    keys.get(active).toRight(s"active manifest key '${active.value}' is absent").map { _ =>
      new ManifestKeyService:
        override val activeKeyId: UIO[ManifestKeyId] = ZIO.succeed(active)

        override def sign(canonicalDigest: Chunk[Byte]): IO[Error, Chunk[Byte]] =
          compute(keys(active), canonicalDigest).mapError(Error.SigningFailure.apply)

        override def verify(
          keyId: ManifestKeyId,
          canonicalDigest: Chunk[Byte],
          signature: Chunk[Byte],
        ): IO[Error, Unit] =
          ZIO
            .fromOption(keys.get(keyId))
            .orElseFail(Error.UnknownKey(keyId))
            .flatMap(compute(_, canonicalDigest).mapError(Error.SigningFailure.apply))
            .flatMap(expected =>
              ZIO
                .fail(Error.InvalidSignature(keyId))
                .unless(MessageDigest.isEqual(expected.toArray, signature.toArray))
                .unit
            )
    }

  private def compute(key: HmacKey, digest: Chunk[Byte]): ZIO[Any, Throwable, Chunk[Byte]] =
    ZIO.attempt {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(new SecretKeySpec(key.bytes, "HmacSHA256"))
      Chunk.fromArray(mac.doFinal(digest.toArray))
    }

/** Streaming canonicalization and proof verification. */
final class ManifestIntegrity private (keys: ManifestKeyService):
  def accumulator(identity: ManifestIdentity): IO[StoreError, ManifestIntegrity.Accumulator] =
    accumulator(identity, BlobMetadataV1.default(identity.chunker))

  def accumulator(
    identity: ManifestIdentity,
    metadata: BlobMetadataV1,
  ): IO[StoreError, ManifestIntegrity.Accumulator] =
    ZIO
      .attempt(new ManifestIntegrity.Accumulator(identity, metadata, keys, verification = false))
      .mapError(StoreError.fromThrowable(StoreOperation.PutManifest))

  def verificationAccumulator(identity: ManifestIdentity): IO[StoreError, ManifestIntegrity.Accumulator] =
    verificationAccumulator(identity, BlobMetadataV1.default(identity.chunker))

  def verificationAccumulator(
    identity: ManifestIdentity,
    metadata: BlobMetadataV1,
  ): IO[StoreError, ManifestIntegrity.Accumulator] =
    ZIO
      .attempt(new ManifestIntegrity.Accumulator(identity, metadata, keys, verification = true))
      .mapError(error => StoreError.CorruptData(StoreOperation.GetManifest, error.getMessage, error))

object ManifestIntegrity:
  private val Domain = "graviton-manifest-proof-v2".getBytes(StandardCharsets.US_ASCII)

  def apply(keys: ManifestKeyService): ManifestIntegrity = new ManifestIntegrity(keys)

  final class Accumulator private[stores] (
    identity: ManifestIdentity,
    metadata: BlobMetadataV1,
    keys: ManifestKeyService,
    verification: Boolean,
  ):
    private val digest = MessageDigest.getInstance("SHA-256")
    private var index  = 0
    private var offset = 0L
    private var done   = false

    initialize()

    def update(entry: ManifestEntry): IO[StoreError, Unit] =
      ZIO
        .attempt {
          ensureOpen()
          if index >= identity.blockCount then throw new IllegalArgumentException("manifest contains more entries than declared")
          val block  = entry.key match
            case value: BinaryKey.Block => value
            case other                  => throw new IllegalArgumentException(s"manifest entry $index is not a block key: $other")
          val start  = entry.span.startInclusive.value
          val end    = entry.span.endInclusive.value
          val length = java.lang.Math.addExact(java.lang.Math.subtractExact(end, start), 1L)
          if start != offset then throw new IllegalArgumentException(s"manifest entry $index starts at $start, expected $offset")
          if length != block.bits.size then
            throw new IllegalArgumentException(s"manifest entry $index length $length does not match block ${block.bits.size}")

          putLong(index.toLong)
          putText(block.bits.render)
          putLong(start)
          putLong(end)
          index = java.lang.Math.addExact(index, 1)
          offset = java.lang.Math.addExact(offset, length)
        }
        .mapError(validationError)

    def prove: IO[StoreError, ManifestProof] =
      finishDigest.flatMap { canonical =>
        for
          keyId     <- keys.activeKeyId
          signature <- keys
                         .sign(canonical)
                         .mapError(error => StoreError.BackendFailure(StoreOperation.PutManifest, StoreBackend.Runtime, error, false))
          proof     <- ZIO
                         .fromEither(ManifestProof.make(ManifestProof.CurrentVersion, keyId, canonical, signature))
                         .mapError(StoreError.CorruptData(StoreOperation.PutManifest, _))
        yield proof
      }

    def verify(proof: ManifestProof): IO[StoreError, Unit] =
      finishDigest.flatMap { canonical =>
        ZIO
          .fail(StoreError.CorruptData(StoreOperation.GetManifest, "manifest canonical digest does not match its stored proof"))
          .unless(MessageDigest.isEqual(canonical.toArray, proof.canonicalDigest.toArray)) *>
          keys
            .verify(proof.keyId, canonical, proof.signature)
            .mapError(error => StoreError.CorruptData(StoreOperation.GetManifest, error.getMessage, error))
      }

    private def finishDigest: IO[StoreError, Chunk[Byte]] =
      ZIO
        .attempt {
          ensureOpen()
          if index != identity.blockCount then
            throw new IllegalArgumentException(s"manifest entry count mismatch: expected ${identity.blockCount}, observed $index")
          if offset != identity.totalSize.value then
            throw new IllegalArgumentException(s"manifest size mismatch: expected ${identity.totalSize.value}, observed $offset")
          done = true
          Chunk.fromArray(digest.digest())
        }
        .mapError(validationError)

    private def validationError(error: Throwable): StoreError =
      if verification then StoreError.CorruptData(StoreOperation.GetManifest, error.getMessage, error)
      else StoreError.fromThrowable(StoreOperation.PutManifest)(error)

    private def initialize(): Unit =
      digest.update(Domain)
      putText(identity.blob.bits.render)
      putLong(identity.totalSize.value)
      putInt(identity.blockCount)
      putText(identity.chunker.value)
      val encodedMetadata = BlobMetadataV1
        .encode(metadata)
        .fold(message => throw new IllegalArgumentException(message), value => value)
      putInt(encodedMetadata.length)
      digest.update(encodedMetadata.toArray)

    private def putText(value: String): Unit =
      val bytes = value.getBytes(StandardCharsets.UTF_8)
      putInt(bytes.length)
      digest.update(bytes)

    private def putInt(value: Int): Unit   = digest.update(ByteBuffer.allocate(4).putInt(value).array())
    private def putLong(value: Long): Unit = digest.update(ByteBuffer.allocate(8).putLong(value).array())
    private def ensureOpen(): Unit         = if done then throw new IllegalStateException("manifest proof accumulator is already complete")
