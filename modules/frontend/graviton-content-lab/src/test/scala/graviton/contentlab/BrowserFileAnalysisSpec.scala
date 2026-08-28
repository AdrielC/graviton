package graviton.contentlab

import scala.scalajs.reflect.annotation.EnableReflectiveInstantiation
import zio.*
import zio.blocks.mediatype.MediaTypes
import zio.pdf.PdfSource
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

@EnableReflectiveInstantiation
object BrowserFileAnalysisSpec extends ZIOSpecDefault:
  private val Hello       = Chunk.fromArray("hello".getBytes("UTF-8"))
  private val HelloDigest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("BrowserFileAnalysis")(
      test("streams a reusable source and computes exact fixed-range identity") {
        for
          opens    <- Ref.make(0)
          source    = countedSource(Hello, opens)
          size     <- ZIO.fromEither(BrowserFileAnalysis.fileSize(Hello.length.toDouble))
          config   <- ZIO.fromEither(
                        BrowserFileAnalysis.Config.make(
                          BrowserFileAnalysis.Strategy.Fixed,
                          BrowserFileAnalysis.MinimumTargetBytes,
                        )
                      )
          analysis <- BrowserFileAnalysis.analyze(source, size, MediaTypes.text.plain, config)
          opened   <- opens.get
        yield assertTrue(
          opened == 2,
          analysis.digestHex == HelloDigest,
          analysis.byteCount.value == Hello.length.toLong,
          analysis.blocks.length == 1,
          analysis.blocks.head.start == 0L,
          analysis.blocks.head.endExclusive == Hello.length.toLong,
          analysis.blocks.head.digestHex == HelloDigest,
        )
      },
      test("rejects metadata beyond the explicit manifest limit") {
        val block  = BrowserFileAnalysis.BlockRange(
          index = 0,
          start = 0L,
          length = 1,
          digestHex = HelloDigest,
          contentId = "sha-256:test:1",
          cut = BrowserFileAnalysis.Cut.Fixed,
          duplicateWithinFile = false,
        )
        val ranges = ZStream.fromIterable(List(block, block.copy(index = 1), block.copy(index = 2)))

        assertZIO(BrowserFileAnalysis.collectBlockMetadata(ranges, maximumBlocks = 2).exit)(
          fails(equalTo(BrowserFileAnalysis.Error.BlockLimitExceeded(2)))
        )
      },
      test("interrupting analysis closes the active source") {
        for
          opens     <- Ref.make(0)
          active    <- Promise.make[Nothing, Unit]
          finalized <- Promise.make[Nothing, Unit]
          source     = new PdfSource:
                         def bytes: ZStream[Any, Throwable, Byte] =
                           ZStream.unwrap {
                             opens.updateAndGet(_ + 1).map {
                               case 1 => ZStream.fromChunk(Chunk.fromArray("%PDF-".getBytes("UTF-8")))
                               case _ =>
                                 ZStream.unwrapScoped(
                                   ZIO
                                     .acquireRelease(active.succeed(()))(_ => finalized.succeed(()).unit)
                                     .as(ZStream.never)
                                 )
                             }
                           }
          size      <- ZIO.fromEither(BrowserFileAnalysis.fileSize(5.0))
          config    <- ZIO.fromEither(
                         BrowserFileAnalysis.Config.make(
                           BrowserFileAnalysis.Strategy.Fixed,
                           BrowserFileAnalysis.MinimumTargetBytes,
                         )
                       )
          fiber     <- BrowserFileAnalysis.analyze(source, size, MediaTypes.application.pdf, config).fork
          _         <- active.await
          _         <- fiber.interrupt
          closed    <- finalized.isDone
        yield assertTrue(closed)
      },
      test("reports a working-byte ceiling that includes browser reads and hash windows") {
        for config <- ZIO.fromEither(
                        BrowserFileAnalysis.Config.make(
                          BrowserFileAnalysis.Strategy.Auto,
                          BrowserFileAnalysis.MaximumTargetBytes,
                        )
                      )
        yield assertTrue(
          config.maximumOwnedBytes >=
            (2L * config.maximumBytes.toLong) + BrowserBlobSource.ReadBytes + BrowserFileAnalysis.HashWindowBytes
        )
      },
    )

  private def countedSource(payload: Chunk[Byte], opens: Ref[Int]): PdfSource =
    new PdfSource:
      def bytes: ZStream[Any, Throwable, Byte] =
        ZStream.fromZIO(opens.update(_ + 1)).drain ++ ZStream.fromChunk(payload)

end BrowserFileAnalysisSpec
