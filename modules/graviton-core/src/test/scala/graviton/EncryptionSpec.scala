package graviton

import graviton.core.model.Size
import graviton.impl.*
import zio.*
import zio.stream.*
import zio.test.*

object EncryptionSpec extends ZIOSpecDefault:

  private val masterKey =
    Chunk.fromArray("0123456789abcdef0123456789abcdef".getBytes)
  private val env       =
    ZLayer.succeed(Encryption.MasterKey(masterKey)) >>> Encryption.live

  private def blockKey(data: Array[Byte]): UIO[BlockKey] =
    for
      hashBytes <- Hashing.compute(
                     Bytes(ZStream.fromIterable(data.toIndexedSeq)),
                     HashAlgorithm.SHA256,
                   )
      size      <- Size.fromZIO(data.length).orDie
    yield BlockKey(Hash(hashBytes, HashAlgorithm.SHA256), size)

  def spec =
    suite("EncryptionSpec")(
      test("encrypt/decrypt roundtrip") {
        val bytes = Chunk.fromArray("hello".getBytes)
        for
          key    <- blockKey("hello".getBytes)
          enc    <- ZIO.service[Encryption]
          cipher <- enc.encrypt(key.hash, bytes)
          plain  <- enc.decrypt(key.hash, cipher)
        yield assertTrue(plain == bytes && cipher != bytes)
      },
      test("deterministic ciphertext") {
        val bytes = Chunk.fromArray("same".getBytes)
        for
          key <- blockKey("same".getBytes)
          enc <- ZIO.service[Encryption]
          c1  <- enc.encrypt(key.hash, bytes)
          c2  <- enc.encrypt(key.hash, bytes)
        yield assertTrue(c1 == c2)
      },
      test("blobstore integration") {
        val data = "hello world".getBytes
        for
          key    <- blockKey(data)
          blob   <- InMemoryBlobStore.make()
          enc    <- ZIO.service[Encryption]
          encBlob = new EncryptedBlobStore(blob, enc)
          _      <- encBlob.write(key, Bytes(ZStream.fromIterable(data)))
          out    <- encBlob
                      .read(key, None)
                      .someOrFailException
                      .flatMap(_.runCollect)
          raw    <- blob.read(key).someOrFailException.flatMap(_.runCollect)
        yield assertTrue(
          new String(out.toArray) == "hello world" && raw != Chunk.fromArray(
            data
          )
        )
      },
    ).provideLayer(env)
