package graviton.protocol.http

import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.runtime.legacy.*
import graviton.runtime.model.{BlobStat, BlobWritePlan, BlobWriteResult}
import graviton.runtime.stores.BlobStore
import graviton.shared.dashboard.DashboardSamples
import graviton.shared.schema.SchemaExplorer
import zio.*
import zio.http.*
import zio.stream.ZSink
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}

object LegacyRepoHttpApiSpec extends ZIOSpecDefault:

  private def fixtureRoot: Task[Path] =
    ZIO.attempt {
      val url = getClass.getClassLoader.getResource("legacy-fixture")
      Paths.get(url.toURI)
    }

  override def spec: Spec[TestEnvironment, Any] =
    suite("LegacyRepoHttpApi")(
      test("resolves metadata and streams binary bytes") {
        for
          root    <- fixtureRoot
          repos    = LegacyRepos(List(LegacyRepo("shortterm", root.resolve("shortterm"))))
          catalog <- LegacyCatalogLive.make(repos)
          fs      <- LegacyFsLive.make(repos)
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
          root       <- fixtureRoot
          repos       = LegacyRepos(List(LegacyRepo("shortterm", root.resolve("shortterm"))))
          catalog    <- LegacyCatalogLive.make(repos)
          fs         <- LegacyFsLive.make(repos)
          api         = LegacyRepoHttpApi(repos, catalog, fs)
          internal    = InternalHttpApi(token = "test-token", legacyRepo = api)
          dashboard  <- ZIO.succeed(new DatalakeDashboardService {
                          def snapshot                                                     = ZIO.succeed(DashboardSamples.snapshot)
                          def metaschema                                                   = ZIO.succeed(DashboardSamples.metaschema)
                          def explorer: UIO[SchemaExplorer.Graph]                          = ZIO.succeed(DashboardSamples.schemaExplorer)
                          def updates                                                      = zio.stream.ZStream.empty
                          def publish(update: graviton.shared.ApiModels.DatalakeDashboard) = ZIO.unit
                        })
          blobStore   = new BlobStore {
                          override def put(plan: BlobWritePlan)                                                       = ZSink.fail(new UnsupportedOperationException("not used in this test"))
                          override def get(key: graviton.core.keys.BinaryKey)                                         =
                            zio.stream.ZStream.fail(new UnsupportedOperationException("not used in this test"))
                          override def stat(key: graviton.core.keys.BinaryKey): ZIO[Any, Throwable, Option[BlobStat]] =
                            ZIO.succeed(None)
                          override def delete(key: graviton.core.keys.BinaryKey): ZIO[Any, Throwable, Unit]           =
                            ZIO.unit
                        }
          httpApi     = HttpApi(blobStore, dashboard)
          internalApp = internal.routes.toHandler
          req        <- ZIO
                          .fromEither(URL.decode("http://localhost/internal/legacy/shortterm/doc-1"))
                          .map(url => Request.get(url).addHeader("x-internal-token", "test-token"))
          resp       <- ZIO.scoped(internalApp(req))
          body       <- resp.body.asString
        yield assertTrue(resp.status == Status.Ok, body == "hello\n")
      },
      test("missing metadata returns 404") {
        for
          root       <- fixtureRoot
          repos       = LegacyRepos(List(LegacyRepo("shortterm", root.resolve("shortterm"))))
          catalog    <- LegacyCatalogLive.make(repos)
          fs         <- LegacyFsLive.make(repos)
          api         = LegacyRepoHttpApi(repos, catalog, fs)
          internal    = InternalHttpApi(token = "test-token", legacyRepo = api)
          dashboard  <- ZIO.succeed(new DatalakeDashboardService {
                          def snapshot                                                     = ZIO.succeed(DashboardSamples.snapshot)
                          def metaschema                                                   = ZIO.succeed(DashboardSamples.metaschema)
                          def explorer: UIO[SchemaExplorer.Graph]                          = ZIO.succeed(DashboardSamples.schemaExplorer)
                          def updates                                                      = zio.stream.ZStream.empty
                          def publish(update: graviton.shared.ApiModels.DatalakeDashboard) = ZIO.unit
                        })
          blobStore   = new BlobStore {
                          override def put(plan: BlobWritePlan)                                                       = ZSink.fail(new UnsupportedOperationException("not used in this test"))
                          override def get(key: graviton.core.keys.BinaryKey)                                         =
                            zio.stream.ZStream.fail(new UnsupportedOperationException("not used in this test"))
                          override def stat(key: graviton.core.keys.BinaryKey): ZIO[Any, Throwable, Option[BlobStat]] =
                            ZIO.succeed(None)
                          override def delete(key: graviton.core.keys.BinaryKey): ZIO[Any, Throwable, Unit]           =
                            ZIO.unit
                        }
          internalApp = internal.routes.toHandler
          req        <- ZIO
                          .fromEither(URL.decode("http://localhost/internal/legacy/shortterm/missing-doc"))
                          .map(url => Request.get(url).addHeader("x-internal-token", "test-token"))
          resp       <- ZIO.scoped(internalApp(req))
        yield assertTrue(resp.status == Status.NotFound)
      },
    )
