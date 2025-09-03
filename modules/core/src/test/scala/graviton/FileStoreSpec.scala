package graviton

import zio.*
import zio.stream.*
import zio.test.*
import graviton.impl.*
import graviton.core.{BinaryAttributeKey, BinaryAttributes}

object FileStoreSpec extends ZIOSpecDefault:

  def spec = suite("FileStoreSpec")(
    test("ingest and read back a file") {
      val detect = new ContentTypeDetect:
        def detect(bytes: Bytes) = ZIO.succeed(Some("text/plain"))
      for
        blob <- InMemoryBlobStore.make()
        resolver <- InMemoryBlockResolver.make
        blocks <- InMemoryBlockStore.make(blob, resolver)
        files <- InMemoryFileStore.make(blocks, detect)
        attrs =
          BinaryAttributes
            .advertised(BinaryAttributeKey.filename, "t.txt", "client") ++
            BinaryAttributes.advertised(
              BinaryAttributeKey.contentType,
              "text/plain",
              "client"
            )
        fk <- ZStream
          .fromIterable("abc" * 1000)
          .map(_.toByte)
          .run(files.put(attrs, 64 * 1024))
        out <- files.get(fk).someOrFailException.flatMap(_.runCollect)
      yield assertTrue(out.length == 3000)
    },
    test("mismatched content type fails") {
      val detect = new ContentTypeDetect:
        def detect(bytes: Bytes) = ZIO.succeed(Some("text/plain"))
      val attempt = for
        blob <- InMemoryBlobStore.make()
        resolver <- InMemoryBlockResolver.make
        blocks <- InMemoryBlockStore.make(blob, resolver)
        files <- InMemoryFileStore.make(blocks, detect)
        attrs =
          BinaryAttributes
            .advertised(BinaryAttributeKey.filename, "d.png", "client") ++
            BinaryAttributes.advertised(
              BinaryAttributeKey.contentType,
              "image/png",
              "client"
            )
        _ <- ZStream
          .fromIterable("data".getBytes.toIndexedSeq)
          .run(files.put(attrs, 64 * 1024))
      yield ()
      attempt.exit.map { exit => assertTrue(exit.isFailure) }
    }
  )
