package graviton.backend.laws

import graviton.runtime.Graviton
import graviton.runtime.stores.{StoreBackend, StoreError, StoreOperation}
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object BlobStoreLawsSpec extends ZIOSpecDefault:
  override def spec =
    suite("published backend laws")(
      BlobStoreLaws.suite("in-memory CAS")(
        Graviton.inMemory(chunkSize = 64 * 1024).map(_.blobStore)
      ),
      BlobStoreLaws.suite("filesystem CAS")(
        ZIO
          .acquireRelease(
            ZIO
              .attemptBlocking(Files.createTempDirectory("graviton-backend-laws-"))
              .mapError(StoreError.fromThrowable(StoreOperation.PutBlob, StoreBackend.Filesystem))
          )(deleteTree)
          .flatMap(root => Graviton.fs(root, chunkSize = 64 * 1024).map(_.blobStore))
      ),
    )

  private def deleteTree(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path) then
        val paths = Files.walk(path)
        try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
        finally paths.close()
    }.orDie
