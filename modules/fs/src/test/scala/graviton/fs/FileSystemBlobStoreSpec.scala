package graviton.fs

import zio.*
import zio.stream.*
import zio.test.*
import graviton.*
import io.github.iltotore.iron.{zio => _, *}
import io.github.iltotore.iron.constraint.all.*
import java.nio.file.Files

object FileSystemBlobStoreSpec extends ZIOSpecDefault:
  def spec = suite("FileSystemBlobStoreSpec")(
    test("write, read, delete round trip") {
      for
        tmp     <- ZIO.attempt(Files.createTempDirectory("fs-store"))
        store   <- FileSystemBlobStore.make(tmp)
        data     = Chunk.fromArray("hello".getBytes)
        hash    <- Hashing.compute(
                     Bytes(ZStream.fromChunk(data)),
                     HashAlgorithm.SHA256,
                   )
        digest   = hash.assume[MinLength[16] & MaxLength[64]]
        sizeR    = data.length.assume[Positive]
        key      = BlockKey(Hash(digest, HashAlgorithm.SHA256), sizeR)
        _       <- store.write(key, Bytes(ZStream.fromChunk(data)))
        read    <- store.read(key).someOrFailException.flatMap(_.runCollect)
        _       <- assertTrue(read == data)
        deleted <- store.delete(key)
        _       <- assertTrue(deleted)
      yield assertCompletes
    },
    test("partial read") {
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("fs-store"))
        store <- FileSystemBlobStore.make(tmp)
        data   = Chunk.fromArray("hello".getBytes)
        hash  <- Hashing.compute(
                   Bytes(ZStream.fromChunk(data)),
                   HashAlgorithm.SHA256,
                 )
        digest = hash.assume[MinLength[16] & MaxLength[64]]
        sizeR  = data.length.assume[Positive]
        key    = BlockKey(Hash(digest, HashAlgorithm.SHA256), sizeR)
        _     <- store.write(key, Bytes(ZStream.fromChunk(data)))
        read  <- store
                   .read(key, Some(ByteRange(1, 4)))
                   .someOrFailException
                   .flatMap(_.runCollect)
      yield assertTrue(new String(read.toArray) == "ell")
    },
  )
