package graviton.fs

import zio.*
import zio.stream.*
import zio.test.*
import graviton.*
import java.nio.file.Files

object FileSystemBlobStoreSpec extends ZIOSpecDefault:
  def spec = suite("FileSystemBlobStoreSpec")(
    test("write, read, delete round trip") {
      for
        tmp <- ZIO.attempt(Files.createTempDirectory("fs-store"))
        store <- FileSystemBlobStore.make(tmp)
        data = Chunk.fromArray("hello".getBytes)
        hash <- Hashing.compute(ZStream.fromChunk(data), HashAlgorithm.SHA256)
        key = BlockKey(Hash(hash, HashAlgorithm.SHA256), data.length)
        _ <- store.write(key, ZStream.fromChunk(data))
        read <- store.read(key).someOrFailException.flatMap(_.runCollect)
        _ <- assertTrue(read == data)
        deleted <- store.delete(key)
        _ <- assertTrue(deleted)
      yield assertCompletes
    }
  )
