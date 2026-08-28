package graviton.runtime.catalog

import graviton.core.attributes.IngestStats
import graviton.core.bytes.HashAlgo
import graviton.core.keys.BinaryKey
import zio.*
import zio.blocks.mediatype.MediaTypes
import zio.test.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object FsCatalogSpec extends ZIOSpecDefault:
  private val blob = BinaryKey.blob(HashAlgo.Sha256("restart-safe-catalog").toOption.get).toOption.get

  override def spec: Spec[TestEnvironment, Any] =
    suite("FsCatalog")(
      test("persists folders and file references across fresh services") {
        ZIO.scoped {
          for
            root     <- temporaryDirectory
            first    <- FsCatalog.layer(root).build.map(_.get[Catalog])
            folder   <- first.createFolder(None, CatalogName.parse("Research").toOption.get)
            file     <- first.attachFile(
                          Some(folder.id),
                          CatalogName.parse("paper.pdf").toOption.get,
                          blob,
                          MediaTypes.application.pdf,
                          IngestStats(blob.bits.size, 2, 1, 1, 0.5),
                        )
            second   <- FsCatalog.layer(root).build.map(_.get[Catalog])
            listing  <- second.list(Some(folder.id))
            restored <- second.getFile(file.id)
          yield assertTrue(
            listing.breadcrumbs.map(_.name.value) == Chunk("Research"),
            listing.files.map(_.name.value) == Chunk("paper.pdf"),
            restored.blob == blob,
            restored.mediaType == MediaTypes.application.pdf,
          )
        }
      },
      test("fails closed when persisted metadata is invalid") {
        ZIO.scoped {
          for
            root <- temporaryDirectory
            path  = root.resolve("catalog").resolve("catalog-v1.json")
            _    <- ZIO.attemptBlocking(Files.createDirectories(path.getParent))
            _    <- ZIO.attemptBlocking(Files.writeString(path, "{not-json"))
            exit <- FsCatalog.layer(root).build.exit
          yield assertTrue(exit.isFailure)
        }
      },
    )

  private val temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("graviton-fs-catalog-"))) { root =>
      ZIO.attemptBlocking {
        if Files.exists(root) then
          val paths = Files.walk(root)
          try paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
          finally paths.close()
      }.ignore
    }
