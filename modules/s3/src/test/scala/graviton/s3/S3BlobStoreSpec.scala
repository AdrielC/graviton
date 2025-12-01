package graviton.s3

import graviton.*
import zio.*
import zio.test.*
import io.minio.{MakeBucketArgs, MinioClient}
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName
import graviton.core.model.Block

import graviton.core.{mapZIO}

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
            data       = Block.applyUnsafe(Chunk.fromArray("hello".getBytes))
            hash <- Hashing
                           .compute(Bytes(data))

            key        = hash.map((h, bs) => BlockKey(Hash.SingleHash(h, bs), data.blockSize))
            _         <- ZIO.foreachDiscard(key.values)(key => store.write(key, Bytes(data)))
            outOpt    <- store.read(key.head._2)
            out       <- outOpt match
                           case Some(bs) => bs.runCollect
                           case None     => ZIO.fail(new RuntimeException("missing data"))
            deleted   <- key.mapZIO((_, key) => store.delete(key))
            missing   <- ZIO.forall(key.values)(key => store.read(key).map(_.isEmpty))
          yield assertTrue(out == data, deleted.values.forall(identity), missing)
        }
      }
    ) @@ TestAspect.ifEnv("TESTCONTAINERS") { value =>
      value.trim match
        case v if v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") => true
        case _                                                                                       => false
    }
