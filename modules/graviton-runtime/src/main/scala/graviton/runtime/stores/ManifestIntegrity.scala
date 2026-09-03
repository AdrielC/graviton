package graviton.runtime.stores

import graviton.core.RefinedTypeExt
import graviton.core.bytes.{HashAlgo, HashBytes, HashInput, Hasher}
import graviton.core.keys.BinaryKey
import graviton.core.manifest.ManifestEntry
import graviton.core.types.{BlockCount, BlockIndex, BlockSize, BlobOffset, FileSize}
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import zio.{Chunk, IO, UIO, ZIO}

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

/** Versioned signed Merkle B-tree root persisted atomically with a manifest. */
final case class ManifestProof private (
  version: Int,
  keyId: ManifestKeyId,
  merkleRoot: HashBytes,
  signature: Chunk[Byte],
)

object ManifestProof:
  val CurrentVersion = 3
  val RootBytes      = HashAlgo.Sha256.hashBytes
  val SignatureBytes = 32

  def make(
    version: Int,
    keyId: ManifestKeyId,
    merkleRoot: Chunk[Byte],
    signature: Chunk[Byte],
  ): Either[String, ManifestProof] =
    for
      _    <- Either.cond(version == CurrentVersion, (), s"unsupported manifest proof version $version")
      root <- HashBytes.fromChunk(merkleRoot).left.map(_.message)
      _    <- Either.cond(root.length == RootBytes, (), s"manifest Merkle root must be $RootBytes bytes")
      _    <- Either.cond(signature.length == SignatureBytes, (), s"manifest signature must be $SignatureBytes bytes")
    yield ManifestProof(version, keyId, root, signature)

final case class ManifestEnvelope(identity: ManifestIdentity, proof: ManifestProof)
final case class StoredManifestAuthentication(chunker: ManifestChunkerId, proof: ManifestProof)

/** Signing boundary suitable for local HMAC, KMS, or HSM implementations. */
trait ManifestKeyService:
  def activeKeyId: UIO[ManifestKeyId]
  def sign(merkleRoot: Chunk[Byte]): IO[ManifestKeyService.Error, Chunk[Byte]]
  def verify(keyId: ManifestKeyId, merkleRoot: Chunk[Byte], signature: Chunk[Byte]): IO[ManifestKeyService.Error, Unit]

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

        override def sign(merkleRoot: Chunk[Byte]): IO[Error, Chunk[Byte]] =
          compute(keys(active), merkleRoot).mapError(Error.SigningFailure.apply)

        override def verify(
          keyId: ManifestKeyId,
          merkleRoot: Chunk[Byte],
          signature: Chunk[Byte],
        ): IO[Error, Unit] =
          ZIO
            .fromOption(keys.get(keyId))
            .orElseFail(Error.UnknownKey(keyId))
            .flatMap(compute(_, merkleRoot).mapError(Error.SigningFailure.apply))
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

/** Streaming Merkle B-tree construction and root verification. */
final class ManifestIntegrity private (keys: ManifestKeyService, hashers: Hasher.Provider):
  def accumulator(identity: ManifestIdentity): IO[StoreError, ManifestIntegrity.Accumulator] =
    accumulator(identity, BlobMetadataV1.default(identity.chunker))

  def accumulator(
    identity: ManifestIdentity,
    metadata: BlobMetadataV1,
  ): IO[StoreError, ManifestIntegrity.Accumulator] =
    makeAccumulator(identity, metadata, verification = false)

  def verificationAccumulator(identity: ManifestIdentity): IO[StoreError, ManifestIntegrity.Accumulator] =
    verificationAccumulator(identity, BlobMetadataV1.default(identity.chunker))

  def verificationAccumulator(
    identity: ManifestIdentity,
    metadata: BlobMetadataV1,
  ): IO[StoreError, ManifestIntegrity.Accumulator] =
    makeAccumulator(identity, metadata, verification = true)

  private def makeAccumulator(
    identity: ManifestIdentity,
    metadata: BlobMetadataV1,
    verification: Boolean,
  ): IO[StoreError, ManifestIntegrity.Accumulator] =
    val operation = if verification then StoreOperation.GetManifest else StoreOperation.PutManifest
    for
      hasher <-
        hashers
          .make(HashAlgo.Sha256)
          .mapError(error => StoreError.BackendFailure(operation, StoreBackend.Runtime, new IllegalStateException(error.message), false))
      count  <- ZIO
                  .fromEither(BlockCount.either(identity.blockCount))
                  .mapError(message => StoreError.CorruptData(operation, s"Invalid manifest block count: $message"))
      tree   <- ManifestMerkleTree.Builder
                  .make(hasher)
                  .mapError(error => StoreError.CorruptData(operation, error.message))
    yield new ManifestIntegrity.Accumulator(identity, metadata, keys, tree, count, verification)

object ManifestIntegrity:
  def apply(keys: ManifestKeyService, hashers: Hasher.Provider = Hasher.Provider.default()): ManifestIntegrity =
    new ManifestIntegrity(keys, hashers)

  final class Accumulator private[stores] (
    identity: ManifestIdentity,
    metadata: BlobMetadataV1,
    keys: ManifestKeyService,
    tree: ManifestMerkleTree.Builder,
    expectedBlockCount: BlockCount,
    verification: Boolean,
  ):
    def update(entry: ManifestEntry): IO[StoreError, Unit] =
      for
        observed <- tree.entryCount
        _        <- ZIO
                      .fail(validationMessage("Manifest contains more entries than declared"))
                      .when(observed >= expectedBlockCount)
        block    <- ZIO
                      .fromEither(
                        entry.key match
                          case value: BinaryKey.Block => Right(value)
                          case other                  => Left(s"Manifest entry $observed is not a block key: $other")
                      )
                      .mapError(validationMessage)
        size     <- ZIO.fromEither(blockSize(block)).mapError(validationMessage)
        end      <- ZIO.fromEither(endOffset(entry.span.startInclusive, size)).mapError(validationMessage)
        _        <- ZIO
                      .fail(
                        validationMessage(
                          s"Manifest entry $observed ends at ${entry.span.endInclusive}, expected $end from block size $size"
                        )
                      )
                      .unless(entry.span.endInclusive == end)
        index    <- ZIO.fromEither(BlockIndex.either(observed.toLong)).mapError(validationMessage)
        _        <- tree.add(index, block, entry.span.startInclusive).mapError(error => validationMessage(error.message))
      yield ()

    def prove: IO[StoreError, ManifestProof] =
      finishRoot.flatMap { root =>
        for
          keyId     <- keys.activeKeyId
          signature <- keys
                         .sign(root)
                         .mapError(error => StoreError.BackendFailure(StoreOperation.PutManifest, StoreBackend.Runtime, error, false))
          proof     <- ZIO
                         .fromEither(ManifestProof.make(ManifestProof.CurrentVersion, keyId, root, signature))
                         .mapError(StoreError.CorruptData(StoreOperation.PutManifest, _))
        yield proof
      }

    def verify(proof: ManifestProof): IO[StoreError, Unit] =
      finishRoot.flatMap { root =>
        ZIO
          .fail(StoreError.CorruptData(StoreOperation.GetManifest, "manifest Merkle root does not match its stored proof"))
          .unless(MessageDigest.isEqual(root.toArray, proof.merkleRoot.toArray)) *>
          keys
            .verify(proof.keyId, root, proof.signature)
            .mapError(error => StoreError.CorruptData(StoreOperation.GetManifest, error.getMessage, error))
      }

    private def finishRoot: IO[StoreError, Chunk[Byte]] =
      for
        encodedMetadata <- ZIO
                             .fromEither(BlobMetadataV1.encode(metadata))
                             .mapError(validationMessage)
        root            <- tree
                             .finish(
                               HashInput.concat(
                                 Chunk(
                                   ManifestMerkleTree.text(identity.blob.bits.render),
                                   ManifestMerkleTree.int64(identity.totalSize),
                                   ManifestMerkleTree.int32(expectedBlockCount),
                                   ManifestMerkleTree.text(identity.chunker.value),
                                   ManifestMerkleTree.int32(encodedMetadata.length),
                                   HashInput.bytes(encodedMetadata),
                                 )
                               ),
                               expectedBlockCount,
                               identity.totalSize,
                             )
                             .mapError(error => validationMessage(error.message))
      yield root

    private def blockSize(block: BinaryKey.Block): Either[String, BlockSize] =
      Either
        .cond(block.bits.size <= Int.MaxValue.toLong, block.bits.size.toInt, s"Block size ${block.bits.size} exceeds Int capacity")
        .flatMap(BlockSize.either)

    private def endOffset(start: BlobOffset, size: BlockSize): Either[String, BlobOffset] =
      try BlobOffset.either(java.lang.Math.addExact(start, size.toLong - 1L))
      catch case _: ArithmeticException => Left(s"Manifest entry at offset $start exceeds the supported offset range")

    private def validationError(error: Throwable): StoreError =
      if verification then StoreError.CorruptData(StoreOperation.GetManifest, error.getMessage, error)
      else StoreError.fromThrowable(StoreOperation.PutManifest)(error)

    private def validationMessage(message: String): StoreError =
      validationError(new IllegalArgumentException(message))
