package graviton.protocol.http

import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.runtime.legacy.*
import graviton.runtime.stores.InMemoryBlobStore
import graviton.shared.dashboard.DashboardSamples
import graviton.shared.schema.SchemaExplorer
import zio.*
import zio.http.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}

object CedarLegacyHttpApiSpec extends ZIOSpecDefault:

  private def fixtureRoot: Task[Path] =
    ZIO.attempt {
      val url = getClass.getClassLoader.getResource("cedar-fixture")
      Paths.get(url.toURI)
    }

  override def spec: Spec[TestEnvironment, Any] =
    suite("CedarLegacyHttpApi")(
      test("resolves metadata and streams binary bytes") {
        for
          root    <- fixtureRoot
          repos    = CedarRepos(List(CedarRepo("shortterm", root.resolve("shortterm"))))
          catalog <- CedarCatalogLive.make(repos)
          fs      <- CedarFsLive.make(repos)
          desc    <- catalog.resolve(LegacyId("shortterm", "doc-1"))
          bytes   <- fs.open("shortterm", desc.binaryHash).runCollect
        yield assertTrue(
          desc.mime == "text/plain",
          desc.length.contains(6L),
          bytes == Chunk.fromArray("hello\n".getBytes(StandardCharsets.UTF_8)),
        )
      },
      test("GET /legacy/{repo}/{docId} returns 200 + body") {
        for
          root      <- fixtureRoot
          repos      = CedarRepos(List(CedarRepo("shortterm", root.resolve("shortterm"))))
          catalog   <- CedarCatalogLive.make(repos)
          fs        <- CedarFsLive.make(repos)
          legacyApi  = CedarLegacyHttpApi(repos, catalog, fs)
          dashboard <- ZIO.succeed(new DatalakeDashboardService {
                         def snapshot                                                     = ZIO.succeed(DashboardSamples.snapshot)
                         def metaschema                                                   = ZIO.succeed(DashboardSamples.metaschema)
                         def explorer: UIO[SchemaExplorer.Graph]                          = ZIO.succeed(DashboardSamples.schemaExplorer)
                         def updates                                                      = zio.stream.ZStream.empty
                         def publish(update: graviton.shared.ApiModels.DatalakeDashboard) = ZIO.unit
                       })
          blobStore <- InMemoryBlobStore.make()
          httpApi    = HttpApi(blobStore, dashboard, cedarLegacy = Some(legacyApi))
          req       <- ZIO
                         .fromEither(URL.decode("http://localhost/legacy/shortterm/doc-1"))
                         .map(url => Request.get(url))
          resp      <- httpApi.app(req)
          body      <- resp.body.asString
        yield assertTrue(resp.status == Status.Ok, body == "hello\n")
      },
      test("missing metadata returns 404") {
        for
          root      <- fixtureRoot
          repos      = CedarRepos(List(CedarRepo("shortterm", root.resolve("shortterm"))))
          catalog   <- CedarCatalogLive.make(repos)
          fs        <- CedarFsLive.make(repos)
          legacyApi  = CedarLegacyHttpApi(repos, catalog, fs)
          dashboard <- ZIO.succeed(new DatalakeDashboardService {
                         def snapshot                                                     = ZIO.succeed(DashboardSamples.snapshot)
                         def metaschema                                                   = ZIO.succeed(DashboardSamples.metaschema)
                         def explorer: UIO[SchemaExplorer.Graph]                          = ZIO.succeed(DashboardSamples.schemaExplorer)
                         def updates                                                      = zio.stream.ZStream.empty
                         def publish(update: graviton.shared.ApiModels.DatalakeDashboard) = ZIO.unit
                       })
          blobStore <- InMemoryBlobStore.make()
          httpApi    = HttpApi(blobStore, dashboard, cedarLegacy = Some(legacyApi))
          req       <- ZIO
                         .fromEither(URL.decode("http://localhost/legacy/shortterm/missing-doc"))
                         .map(url => Request.get(url))
          resp      <- httpApi.app(req)
        yield assertTrue(resp.status == Status.NotFound)
      },
    )
