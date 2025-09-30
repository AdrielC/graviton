package graviton.s3

import graviton.*
import zio.*
import zio.stream.*
import zio.test.*
import io.github.iltotore.iron.{zio => _, *}
import io.github.iltotore.iron.constraint.all.*
import io.minio.{MakeBucketArgs, MinioClient}
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName

object S3BlobStoreSpec extends ZIOSpecDefault:
  override def spec =
    suite("S3BlobStore")(
      test("writes, reads, and deletes data") {
        val acquire = ZIO.attempt {
          val container =
            new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
              .withUserName("minio")
              .withPassword("password")
          container.start()
          container
        }
        val release = (c: MinIOContainer) => ZIO.attempt(c.stop()).ignore
        ZIO.acquireRelease(acquire)(release).flatMap { c =>
          val client = MinioClient
            .builder()
            .endpoint(c.getS3URL)
            .credentials(c.getUserName, c.getPassword)
            .build()
          val bucket = "test"
          for
            _         <- ZIO.attempt(
                           client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
                         )
            store      = new S3BlobStore(client, bucket, BlobStoreId("test"))
            data       = Chunk.fromArray("hello".getBytes)
            hashBytes <- Hashing
                           .compute(Bytes(ZStream.fromChunk(data)), HashAlgorithm.SHA256)
            digest     = hashBytes.assume[MinLength[16] & MaxLength[64]]
            hash       = Hash(digest, HashAlgorithm.SHA256)
            key        = BlockKey(hash, data.length.assume[Positive])
            _         <- store.write(key, Bytes(ZStream.fromChunk(data)))
            outOpt    <- store.read(key)
            out       <- outOpt match
                           case Some(bs) => bs.runCollect
                           case None     => ZIO.fail(new RuntimeException("missing data"))
            deleted   <- store.delete(key)
            missing   <- store.read(key)
          yield assertTrue(out == data, deleted, missing.isEmpty)
        }
      }
    ) @@ TestAspect.ifEnvSet("TESTCONTAINERS")
