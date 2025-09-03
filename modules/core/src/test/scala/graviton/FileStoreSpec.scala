package graviton

import zio.*
import zio.stream.*
import zio.test.*
import graviton.impl.*

object FileStoreSpec extends ZIOSpecDefault:

  def spec = suite("FileStoreSpec")(
    test("ingest and read back a file") {
      val detect = new ContentTypeDetect:
        def detect(bytes: Bytes) = ZIO.succeed(Some("text/plain"))
      for
        blob <- InMemoryBlobStore.make()
        blocks <- InMemoryBlockStore.make(blob)
        files <- InMemoryFileStore.make(blocks, detect)
        fk <- ZStream
          .fromIterable("abc" * 1000)
          .map(_.toByte)
          .run(
            files
              .put(FileMetadata(Some("t.txt"), Some("text/plain")), 64 * 1024)
          )
        out <- files.get(fk).someOrFailException.flatMap(_.runCollect)
      yield assertTrue(out.length == 3000)
    },
    test("mismatched content type fails") {
      val detect = new ContentTypeDetect:
        def detect(bytes: Bytes) = ZIO.succeed(Some("text/plain"))
      val attempt = for
        blob <- InMemoryBlobStore.make()
        blocks <- InMemoryBlockStore.make(blob)
        files <- InMemoryFileStore.make(blocks, detect)
        _ <- ZStream
          .fromIterable("data".getBytes.toIndexedSeq)
          .run(
            files.put(FileMetadata(Some("d.png"), Some("image/png")), 64 * 1024)
          )
      yield ()
      attempt.exit.map { exit => assertTrue(exit.isFailure) }
    }
  )
