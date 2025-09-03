package graviton

import zio.*
import zio.stream.*
import zio.test.*
import graviton.impl.*

object FileStoreSpec extends ZIOSpecDefault:

  def spec = suite("FileStoreSpec")(
    test("ingest and read back a file") {
      for
        blob <- InMemoryBlobStore.make()
        blocks <- InMemoryBlockStore.make(blob)
        files <- InMemoryFileStore.make(blocks)
        fk <- ZStream.fromIterable("abc" * 1000).map(_.toByte).run(files.put(FileMetadata(Some("t.txt"), Some("text/plain")), 64 * 1024))
        out <- files.get(fk).someOrFailException.flatMap(_.runCollect)
      yield assertTrue(out.length == 3000)
    }
  )
