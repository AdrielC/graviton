package graviton.shared.cas

import graviton.shared.cas.ContentAddressing.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import zio.Chunk
import zio.test.*

object ContentAddressingContract:
  val spec =
    suite("ContentAddressing")(
      test("computes the SHA-256 known vector on the platform implementation") {
        val bytes = ascii("abc")

        for analysis <- ContentAddressing.analyze(bytes, 16)
        yield assertTrue(
          analysis.blobId.render ==
            "sha-256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad:3",
          analysis.blocks.length == 1,
        )
      },
      test("hashes empty input instead of inventing a placeholder") {
        for analysis <- ContentAddressing.analyze(InteractiveBytes.empty, 16)
        yield assertTrue(
          analysis.blobId.render ==
            "sha-256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855:0",
          analysis.blocks.isEmpty,
        )
      },
      test("marks repeated fixed blocks in encounter order") {
        val bytes = ascii("GRAVITON-BLOCK!!GRAVITON-BLOCK!!CONTENT-ADDRESS!GRAVITON-BLOCK!!")

        for analysis <- ContentAddressing.analyze(bytes, 16)
        yield assertTrue(
          analysis.blocks.length == 4,
          analysis.uniqueCount == 2,
          analysis.duplicateCount == 2,
          analysis.blocks.map(_.duplicate) == Chunk(false, true, false, true),
        )
      },
      test("rejects payloads and fixed block sizes outside the public boundary") {
        val oversized = InteractiveBytes.fromChunk(Chunk.fill(MaxInteractiveBytes + 1)(0.toByte))

        assertTrue(
          oversized.isLeft,
          FixedBlockSize.fromInt(15).isLeft,
          FixedBlockSize.fromInt(17).isLeft,
          FixedBlockSize.fromInt(144).isLeft,
          FixedBlockSize.fromInt(128).isRight,
        )
      },
      test("round-trips the server and browser content-key text contract") {
        val digest   = "a" * 64
        val rendered = ContentKeyText.render("sha-256", digest, 42L)

        assertTrue(
          rendered == s"sha-256:$digest:42",
          ContentKeyText.parse(rendered) == Right(ContentKeyText.Parts("sha-256", digest, 42L)),
          ContentKeyText.parse(s"SHA256:$digest:42") == Right(ContentKeyText.Parts("sha-256", digest, 42L)),
          ContentKeyText.parse(s"sha-256:$digest:-1").isLeft,
          ContentKeyText.parse(s"sha-256:$digest:+1").isLeft,
          ContentKeyText.parse(s"unknown:$digest:42").isLeft,
          ContentKeyText.parse(s"sha-256:${"a" * 63}:42").isLeft,
          ContentKeyText.parse(s"sha-256:${"A" * 64}:42").isLeft,
          ContentKeyText.parse("missing-fields").isLeft,
          ContentKeyText.parse(null).isLeft,
          ContentKeyText.parse("x" * (ContentKeyText.MaxWireLength + 1)).isLeft,
        )
      },
    )

  private def ascii(value: String): InteractiveBytes =
    Chunk
      .fromIterable(value.iterator.map(_.toByte).toList)
      .refineUnsafe[MaxLength[8192]]
