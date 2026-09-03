package graviton.core.bytes

import scala.compiletime.testing.typeCheckErrors
import graviton.core.types.Algo
import graviton.core.macros.Interpolators.*
import zio.{Chunk, NonEmptyChunk}
import zio.test.*

object HasherSpec extends ZIOSpecDefault:

  private final case class Framed(header: Chunk[Byte], payload: Chunk[Byte])

  private given Hashable[Framed] =
    Hashable.instance(value => HashInput.segments(value.header, value.payload))

  override def spec: Spec[Any, Any] =
    suite("Hasher")(
      test("reset clears both digest state and the tracked input size") {
        val hasher = Hasher.systemDefault.toOption.get
        val _      = hasher.update("before-reset")
        val _      = hasher.digest

        hasher.reset
        val sizeAfterReset = hasher.inputSize
        val _              = hasher.update("x")
        val bits           = hasher.digestKeyBits.toOption.get

        assertTrue(
          sizeAfterReset == 0L,
          bits.size == 1L,
        )
      },
      test("dispatches structured values through their Hashable instance") {
        val framed   = Framed(Chunk[Byte](1, 2), Chunk[Byte](3, 4, 5))
        val expected = Hash(HashAlgo.Sha256)(Chunk[Byte](1, 2, 3, 4, 5))
        val actual   = Hash(HashAlgo.Sha256)(framed)

        assertTrue(actual == expected)
      },
      test("does not provide a mutable array Hashable instance") {
        val errors = typeCheckErrors("summon[Hashable[Array[Byte]]]")
        assertTrue(errors.nonEmpty)
      },
      test("tracks encoded bytes rather than UTF-16 character count") {
        val hasher = Hasher.systemDefault.toOption.get
        val _      = hasher.update("π")

        assertTrue(hasher.inputSize == 2L)
      },
      test("returns the digest and observed byte length as one sealed result") {
        val hasher = Hasher.systemDefault.toOption.get
        val _      = hasher.update("π")
        val result = hasher.hashed

        assertTrue(
          result.exists(_.hash.algo == HashAlgo.runtimeDefault),
          result.exists(_.hash.bytes.length == HashAlgo.runtimeDefault.hashBytes),
          result.exists(_.size == 2L),
        )
      },
      test("finalization resets digest state and byte count together") {
        val hasher = Hasher.systemDefault.toOption.get
        val _      = hasher.update("payload")
        val first  = hasher.hashed
        val second = hasher.hashed

        assertTrue(
          first.exists(_.size == 7L),
          second.exists(_.size == 0L),
          first.map(_.hash) != second.map(_.hash),
          hasher.inputSize == 0L,
        )
      },
      test("Hasher.Provider creates fresh isolated hashers through the ZIO environment") {
        (for
          first  <- Hasher.Provider.make(HashAlgo.Sha256)
          second <- Hasher.Provider.make(HashAlgo.Sha256)
          _       = first.update("first")
        yield assertTrue(
          first ne second,
          first.inputSize == 5L,
          second.inputSize == 0L,
        )).provide(Hasher.Provider.layer)
      },
      test("HashAlgo keying uses the encoded byte length from the hasher") {
        val result = HashAlgo.Sha256("π")

        assertTrue(result.exists(_.size == 2L))
      },
      test("bounds hashes to the supported algorithm result interval") {
        val narrowed                           = HashBytes.fromChunk(Chunk.fill(20)(0.toByte))
        val widened: Either[HashError, Digest] = narrowed

        assertTrue(
          widened.isRight,
          Digest.fromChunk(Chunk.fill(16)(0.toByte)).isRight,
          HashBytes.fromChunk(Chunk.fill(16)(0.toByte)).isLeft,
          Digest.fromChunk(Chunk.fill(32)(0.toByte)).isRight,
          Digest.fromChunk(Chunk.fill(33)(0.toByte)).isLeft,
          HashBytes.fromChunk(Chunk.fill(40)(0.toByte)).isLeft,
          HashBytes.fromChunk(Chunk.fill(20)(0.toByte)).isRight,
          HashBytes.fromChunk(Chunk.fill(32)(0.toByte)).isRight,
          HashBytes.fromChunk(Chunk.fill(19)(0.toByte)).isLeft,
          HashBytes.fromChunk(Chunk.fill(33)(0.toByte)).isLeft,
          HashBytes.fromChunk(Chunk.fill(20)(0.toByte)).flatMap(Hash.make(HashAlgo.Sha1, _)).isRight,
          HashBytes.fromChunk(Chunk.fill(20)(0.toByte)).flatMap(Hash.make(HashAlgo.Sha256, _)).isLeft,
        )
      },
      test("returns a strict non-empty hash collection in stable algorithm order") {
        val hashes = for
          multi  <- MultiHasher.makeTyped(HashAlgo.Sha256, HashAlgo.Sha1)
          result <- multi.update("graviton").hashes.toEither.left.map(HashError.Multiple.apply)
        yield result

        assertTrue(
          hashes.exists(_.length == 2),
          hashes.exists(_.map(_.algo) == NonEmptyChunk(HashAlgo.Sha1, HashAlgo.Sha256)),
          hashes.exists(_.forall(hash => hash.bytes.length == hash.algo.hashBytes)),
        )
      },
      test("exposes only the single-hash or non-empty multi-hash result shapes") {
        val single = Hash.compute(HashAlgo.Sha256)("graviton")
        val many   = Hash.compute(HashAlgo.Sha256, HashAlgo.Sha1)("graviton")

        assertTrue(
          single.exists {
            case hash: Hash => hash.bytes.length == HashAlgo.Sha256.hashBytes
            case _          => false
          },
          many.exists(_.isInstanceOf[NonEmptyChunk[?]]),
        )
      },
      test("verifies a typed hash against another Hashable value") {
        val expected = Hash(HashAlgo.Sha256)("graviton")

        assertTrue(
          expected.exists(Verify.matches(_, "graviton")),
          expected.exists(hash => !Verify.matches(hash, "different")),
        )
      },
      test("verifies an uploader MD5 without admitting MD5 as a CAS hash algorithm") {
        val md5      = Algo("md5")
        val expected = hex"5d41402abc4b2a76b9719d911017c592"
        val verified = for
          verifier <- ChecksumVerifier.make(Map(md5 -> expected))
          _        <- verifier.update(Chunk.fromArray("he".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          _        <- verifier.update(Chunk.fromArray("llo".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          result   <- verifier.verify
        yield result

        assertTrue(
          verified.exists(_.get(md5).contains(expected)),
          HashAlgo.fromString("md5").isEmpty,
        )
      },
      test("rejects a mismatched uploader checksum") {
        val md5      = Algo("md5")
        val expected = hex"00000000000000000000000000000000"
        val result   = for
          verifier <- ChecksumVerifier.make(Map(md5 -> expected))
          _        <- verifier.update("hello")
          _        <- verifier.verify
        yield ()

        assertTrue(result.left.exists(_.isInstanceOf[ChecksumError.Multiple]))
      },
    )
