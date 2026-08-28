package graviton.runtime.catalog

import graviton.core.attributes.IngestStats
import graviton.core.bytes.HashAlgo
import graviton.core.keys.BinaryKey
import zio.*
import zio.blocks.mediatype.MediaTypes
import zio.test.*

object InMemoryCatalogSpec extends ZIOSpecDefault:
  private val blob = BinaryKey.blob(HashAlgo.Sha256("catalog-content").toOption.get).toOption.get

  override def spec: Spec[TestEnvironment, Any] =
    suite("InMemoryCatalog")(
      test("keeps folder structure separate from immutable blob identity") {
        for
          catalog <- ZIO.service[Catalog]
          folder  <- catalog.createFolder(None, CatalogName.parse("Evidence").toOption.get)
          file    <- catalog.attachFile(
                       Some(folder.id),
                       CatalogName.parse("record.pdf").toOption.get,
                       blob,
                       MediaTypes.application.pdf,
                       IngestStats(15L, 3, 2, 1, 0.01),
                     )
          nested  <- catalog.list(Some(folder.id))
          _       <- catalog.removeFile(file.id)
          after   <- catalog.list(Some(folder.id))
          _       <- catalog.removeFolder(folder.id)
          root    <- catalog.list(None)
        yield assertTrue(
          nested.breadcrumbs.map(_.name.value) == Chunk("Evidence"),
          nested.files.map(_.blob) == Chunk(blob),
          after.files.isEmpty,
          root.folders.isEmpty,
        )
      },
      test("atomically rejects colliding names") {
        for
          catalog <- ZIO.service[Catalog]
          name     = CatalogName.parse("same-name").toOption.get
          exits   <- ZIO.collectAllPar(List(catalog.createFolder(None, name).exit, catalog.createFolder(None, name).exit))
        yield assertTrue(
          exits.count(_.isSuccess) == 1,
          exits.count(_.isFailure) == 1,
        )
      },
      test("refuses to remove a non-empty folder") {
        for
          catalog <- ZIO.service[Catalog]
          folder  <- catalog.createFolder(None, CatalogName.parse("parent").toOption.get)
          _       <- catalog.createFolder(Some(folder.id), CatalogName.parse("child").toOption.get)
          exit    <- catalog.removeFolder(folder.id).exit
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[CatalogError.FolderNotEmpty])
        )
      },
    ).provide(InMemoryCatalog.layer)
