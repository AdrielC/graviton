package graviton.core.scan

import graviton.core.bytes.*
import graviton.core.scan.FS.*
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets

object IngestScanSpec extends ZIOSpecDefault:

  private val ascii = StandardCharsets.US_ASCII

  private def bytes(s: String): Chunk[Byte] =
    Chunk.fromArray(s.getBytes(ascii))

  private def run(scan: FreeScan[Prim, Chunk[Byte], IngestScan.Event], input: Chunk[Byte], splits: List[Int]): Chunk[IngestScan.Event] =
    val chunks =
      if splits.isEmpty then Chunk(input)
      else
        val out = ChunkBuilder.make[Chunk[Byte]]()
        var idx = 0
        var si  = 0
        while idx < input.length do
          val n = math.max(1, splits(si % splits.length))
          out += input.slice(idx, math.min(input.length, idx + n))
          idx += n
          si += 1
        out.result()
    scan.runChunk(chunks.toList)

  private def field[A](e: IngestScan.Event, name: String): A =
    e.toMap
      .collectFirst { case (f, v) if f.name == name => v.asInstanceOf[A] }
      .getOrElse(throw new NoSuchElementException(s"Missing field '$name' in IngestScan.Event"))

  override def spec: Spec[TestEnvironment, Any] =
    suite("IngestScan")(
      test("FastCDC boundaries are chunking-invariant and blocks stay within bounds") {
        val algo = HashAlgo.runtimeDefault

        // Inject an anchor so we can observe anchor-driven boundaries.
        val anchor = bytes("endstream")
        val data   =
          bytes("AAAA") ++
            Chunk.fromArray(Array.fill[Byte](3000)(0x41.toByte)) ++
            anchor ++
            Chunk.fromArray(Array.fill[Byte](3000)(0x42.toByte))

        val scan = IngestScan.fastCdc(algo = algo, minSize = 256, avgSize = 1024, maxSize = 4096, anchor = Some(anchor))

        val a = run(scan, data, splits = Nil)
        val b = run(scan, data, splits = List(1, 7, 64, 3, 1024))

        def blocks(out: Chunk[IngestScan.Event]): Chunk[Chunk[Byte]] =
          out
            .filter(e => field[String](e, "kind") == "block")
            .flatMap(e => field[Option[Chunk[Byte]]](e, "blockBytes").toList)

        val aBlocks = blocks(a)
        val bBlocks = blocks(b)

        val sameBytes = aBlocks == bBlocks
        val withinMax = aBlocks.forall(b => b.length <= 4096 && b.length >= 1)
        val hasAnchor =
          a.exists(e =>
            field[String](e, "kind") == "block" &&
              field[Option[String]](e, "reason").contains("anchor")
          )

        assertTrue(sameBytes, withinMax, hasAnchor)
      }
    )
