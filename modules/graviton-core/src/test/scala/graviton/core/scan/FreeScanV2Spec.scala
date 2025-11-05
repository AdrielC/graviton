package graviton.core.scan

import graviton.core.bytes.*
import graviton.core.keys.*
import graviton.core.manifest.*
import graviton.core.ranges.*
import graviton.core.scan.FreeScan.*
import graviton.core.scan.Prim.*
import graviton.core.scan.FS.*
import zio.*
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.StandardCharsets

object FreeScanV2Spec extends ZIOSpecDefault:

  private val ascii = StandardCharsets.US_ASCII

  private def chunk(str: String): Chunk[Byte] = Chunk.fromArray(str.getBytes(ascii))

  override def spec: Spec[TestEnvironment, Any] =
    suite("Volga FreeScan")(
      test("map/filter pipeline emits via channel") {
        val program =
          map[Int, Int](_ + 1) >>>
            filter[Int](_ % 2 == 0) >>>
            map[Int, String](_.toString)

        val stream = ZStream.fromIterable(1 to 6).via(program.toPipeline)

        assertZIO(stream.runCollect)(equalTo(Chunk("2", "4", "6")))
      },
      test("optimizer fuses adjacent maps and filters") {
        val maps      = map[Int, Int](_ + 1) >>> map[Int, Int](_ * 2)
        val fusedMaps = maps.optimize

        val filters      = filter[Int](_ % 2 == 0) >>> filter[Int](_ > 2)
        val fusedFilters = filters.optimize

        val mapCheck = fusedMaps match
          case Embed(Map1(f)) => f(3) == 8
          case _              => false

        val filterCheck = fusedFilters match
          case Embed(Filter(p)) => p(4) && !p(2)
          case _                => false

        assertTrue(mapCheck, filterCheck)
      },
      test("counter and byteCounter compose via tensor product") {
        val combined = map[Chunk[Byte], (Chunk[Byte], Chunk[Byte])](chunk => (chunk, chunk)) >>>
          (counter[Chunk[Byte]] >< byteCounter)
        val inputs   = List(chunk("ab"), chunk("c"), chunk("def"))
        val outputs  = combined.runChunk(inputs).toList

        assertTrue(outputs == List((1L, 2L), (2L, 3L), (3L, 6L)))
      },
      test("fixed chunker flushes remainder") {
        val chunker = fixedChunker(3)
        val inputs  = List(chunk("ab"), chunk("cde"))
        val outputs = chunker.runChunk(inputs).map(bytes => new String(bytes.toArray, ascii))

        assertTrue(outputs == List("abc", "de"))
      },
      test("manifest builder aggregates entries") {
        val digestHex = "0" * 64

        for
          digest   <- ZIO.fromEither(Digest.make(HashAlgo.Sha256, digestHex))
          keyBits1 <- ZIO.fromEither(KeyBits.create(HashAlgo.Sha256, digest, 10L))
          keyBits2 <- ZIO.fromEither(KeyBits.create(HashAlgo.Sha256, digest, 5L))
          entry1    = ManifestEntry(BinaryKey.Blob(keyBits1), Span.unsafe(0L, 9L), Map("name" -> "a"))
          entry2    = ManifestEntry(BinaryKey.Blob(keyBits2), Span.unsafe(10L, 14L), Map("name" -> "b"))
          outputs   = buildManifest.runChunk(List(entry1, entry2))
          manifest <- ZIO.fromOption(outputs.lastOption)
        yield assertTrue(
          manifest.entries == List(entry1, entry2),
          manifest.size == 15L,
        )
      },
      test("hashBytes emits padded digest on flush") {
        val data     = chunk("hi")
        val outputs  = hashBytes(HashAlgo.Sha256).runChunk(List(data))
        val expected = "6869" + ("0" * 60)

        for
          digest <- ZIO.fromEither(Digest.make(HashAlgo.Sha256, expected))
          result <- ZIO.fromOption(outputs.lastOption)
        yield assertTrue(result == Right(digest))
      },
      test("channel interpreter remains pure and stack-safe") {
        val many    = (1 to 10000).toList
        val program =
          map[Int, Int](_ + 1) >>>
            flat[Int, Int](i => Chunk(i, i)) >>>
            filter[Int](_ % 3 == 0)

        val collected = program.runChunk(many)
        assertTrue(collected.length == many.length * 2 / 3)
      },
    )
