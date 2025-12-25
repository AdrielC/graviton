package graviton.core.scan

import graviton.core.bytes.*
import graviton.core.keys.*
import graviton.core.manifest.*
import graviton.core.ranges.*
import graviton.core.scan.FreeScan.*
import graviton.core.scan.Prim.*
import graviton.core.scan.FS.*
import graviton.core.scan.Tensor
import kyo.Tag.given
import zio.*
import zio.stream.*
import zio.test.*
import java.nio.charset.StandardCharsets
import zio.test.Assertion.*

import SafeFunction.given

object FreeScanV2Spec extends ZIOSpecDefault:

  private val ascii           = StandardCharsets.US_ASCII
  private val runtimeHashAlgo = HashAlgo.runtimeDefault

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
        val combined =
          map[Chunk[Byte], Tensor.Pair["count", "bytes", Chunk[Byte], Chunk[Byte]]] { chunk =>
            pair["count", "bytes", Chunk[Byte], Chunk[Byte]](chunk, chunk)
          } >>>
            (counter[Chunk[Byte]].labelled["count"] >< byteCounter.labelled["bytes"])
        val inputs   = List(chunk("ab"), chunk("c"), chunk("def"))
        val outputs  = combined.runChunk(inputs).map(Tensor.toTuple["count", "bytes", Long, Long]).toList

        assertTrue(outputs == List((1L, 2L), (2L, 3L), (3L, 6L)))
      },
      test("fixed chunker flushes remainder") {
        val chunker = fixedChunker(3)
        val inputs  = List(chunk("ab"), chunk("cde"))
        val outputs = chunker.runChunk(inputs).map(bytes => new String(bytes.toArray, ascii))

        assertTrue(outputs == List("abc", "de"))
      },
      test("Kyo interpreter matches pure runner (including flush)") {
        val scan = fixedChunker(3)
        val in   = List(chunk("ab"), chunk("cde"))

        val expected = scan.runChunk(in).map(bytes => new String(bytes.toArray, ascii)).toList
        val got      =
          InterpretKyo
            .runChunk(scan, kyo.Chunk.from(in))
            .map(bytes => new String(bytes.toArray, ascii))
            .toSeq
            .toList

        assertTrue(got == expected)
      },
      test("fanout (&&&) broadcasts input and returns Record output") {
        val program =
          counter[Chunk[Byte]].labelled["count"] &&&
            byteCounter.labelled["bytes"]

        val inputs  = List(chunk("ab"), chunk("c"), chunk("def"))
        val outputs = program.runChunk(inputs).map(Tensor.toTuple["count", "bytes", Long, Long]).toList

        assertTrue(outputs == List((1L, 2L), (2L, 3L), (3L, 6L)))
      },
      test("manifest builder aggregates entries") {

        val digest = "0" * runtimeHashAlgo.hexLength

        ZIO.fromEither:
          for
            digest   <- Digest.make(runtimeHashAlgo)(digest)
            keyBits1 <- KeyBits.create(runtimeHashAlgo, digest, 10L)
            keyBits2 <- KeyBits.create(runtimeHashAlgo, digest, 5L)
            blobKey1 <- BinaryKey.blob(keyBits1)
            blobKey2 <- BinaryKey.blob(keyBits2)
            entry1    = ManifestEntry(blobKey1, Span.unsafe(0L, 9L), Map("name" -> "a"))
            entry2    = ManifestEntry(blobKey2, Span.unsafe(10L, 14L), Map("name" -> "b"))
            outputs   = buildManifest.runChunk(List(entry1, entry2))
            manifest <- outputs.lastOption.toRight("No manifest found")
          yield assertTrue(
            manifest.entries == List(entry1, entry2),
            manifest.size == 15L,
          )
      },
      test("hashBytes emits padded digest on flush") {
        val data     = chunk("hi")
        val outputs  = hashBytes(runtimeHashAlgo).runChunk(List(data))
        val expected =
          for
            hasher <- Hasher.hasher(runtimeHashAlgo, None)
            _       = hasher.update("hi".getBytes(StandardCharsets.UTF_8))
            digest <- hasher.digest
          yield digest

        for
          digest <- ZIO.fromEither(expected)
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
