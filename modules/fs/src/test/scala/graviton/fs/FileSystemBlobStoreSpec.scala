package graviton.fs

import zio.*
import zio.stream.*
import zio.test.*
import graviton.*
import java.nio.file.Files

import graviton.core.model.Block

object FileSystemBlobStoreSpec extends ZIOSpecDefault:
  def spec = suite("FileSystemBlobStoreSpec")(
    test("write, read, delete round trip") {
      for
        tmp     <- ZIO.attempt(Files.createTempDirectory("fs-store"))
        store   <- FileSystemBlobStore.make(tmp)
        data     = Block.applyUnsafe(Chunk.fromArray("hello".getBytes))
        hash    <- Hashing.compute(
                     Bytes(ZStream.fromChunk(data))
                   )
        sizeR    = data.blockSize
        key      = BlockKey(Hash.SingleHash(hash.bytes.head._1, hash.bytes.head._2), sizeR)
        _       <- store.write(key, Bytes(data))
        read    <- store.read(key).someOrFailException.flatMap(_.runCollect)
        _       <- assertTrue(read == data)
        deleted <- store.delete(key).unit
      yield assertCompletes
    },
    test("partial read") {
      for
        tmp    <- ZIO.attempt(Files.createTempDirectory("fs-store"))
        store  <- FileSystemBlobStore.make(tmp)
        data    = Block.applyUnsafe(Chunk.fromArray("hello".getBytes))
        hash   <- Hashing.compute(
                    Bytes(ZStream.fromChunk(data))
                  )
        sizeR   = data.blockSize
        key     = BlockKey(Hash.SingleHash(hash.bytes.head._1, hash.bytes.head._2), sizeR)
        _      <- store.write(key, Bytes(data))
        read   <- store
                    .read(key, Some(ByteRange(1, 4)))
                    .someOrFailException
                    .flatMap(_.runCollect)
      yield assertTrue(new String(read.toArray) == "ell")
    },
  )
