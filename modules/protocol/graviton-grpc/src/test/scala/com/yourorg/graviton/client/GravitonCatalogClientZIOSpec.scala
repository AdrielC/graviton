package com.yourorg.graviton.client

import com.yourorg.graviton.client.GravitonCatalogClientZIO.*
import com.yourorg.graviton.client.GravitonUploadGatewayClientZIO.UploadGatewayClient
import io.grpc.Status
import io.graviton.blobstore.v1.*
import zio.*
import zio.stream.*
import zio.test.*

object GravitonCatalogClientZIOSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment, Any] =
    suite("GravitonCatalogClientZIO")(
      test("search builds filters for hash, content type, and size range") {
        for {
          ref    <- Ref.make(Option.empty[SearchRequest])
          client  = new GravitonCatalogClientZIO(RecordingCatalogClient(ref))
          _      <- client
                      .search(SearchFilters(hashes = Chunk("hash"), contentTypes = Chunk("application/pdf"), sizeRange = Some(0L -> 1024L)))
                      .runDrain
          stored <- ref.get
        } yield assertTrue(
          stored.exists(_.filters.exists(f => f.field == Filter.Field.FIELD_HASH && f.value == "hash")),
          stored.exists(_.filters.exists(f => f.field == Filter.Field.FIELD_CONTENT_TYPE && f.value == "application/pdf")),
          stored.exists(_.filters.count(_.field == Filter.Field.FIELD_SIZE_BYTES) == 2),
        )
      },
      test("findDuplicates groups identical blobs") {
        val duplicates = DuplicateGroup(
          key = "sha",
          items = List(SearchResult(documentId = "doc", blobHash = "sha", contentType = "application/pdf", sizeBytes = 10L)),
        )

        val stub = new CatalogClient {
          override def search(request: SearchRequest): ZStream[Any, Status, SearchResult]                   = ZStream.empty
          override def list(request: ListRequest): IO[Status, ListResponse]                                 = ZIO.fail(Status.INTERNAL)
          override def get(request: GetRequest): IO[Status, GetResponse]                                    = ZIO.fail(Status.INTERNAL)
          override def findDuplicates(request: FindDuplicatesRequest): ZStream[Any, Status, DuplicateGroup] = ZStream.succeed(duplicates)
          override def `export`(request: ExportRequest): ZStream[Any, Status, ExportChunk]                  = ZStream.empty
          override def subscribe(request: SubscribeRequest): ZStream[Any, Status, CatalogEvent]             = ZStream.empty
        }

        val client = new GravitonCatalogClientZIO(stub)
        client
          .findDuplicates(FindDuplicatesRequest(hashPrefix = "sha"))
          .runCollect
          .map(results => assertTrue(results.head.items.head.blobHash == "sha"))
      },
      test("export manifest and frames can be re-uploaded through frames path") {
        val manifestChunk =
          ExportChunk(ExportChunk.Kind.Manifest(Manifest("application/vnd.graviton.manifest+json", Chunk.fromArray("{}".getBytes))))
        val frameChunk    = ExportChunk(ExportChunk.Kind.Frame(ExportFrame("application/graviton-frame", Chunk.fromArray("block".getBytes))))

        val catalogStub = new CatalogClient {
          override def search(request: SearchRequest): ZStream[Any, Status, SearchResult]                   = ZStream.empty
          override def list(request: ListRequest): IO[Status, ListResponse]                                 = ZIO.fail(Status.INTERNAL)
          override def get(request: GetRequest): IO[Status, GetResponse]                                    = ZIO.fail(Status.INTERNAL)
          override def findDuplicates(request: FindDuplicatesRequest): ZStream[Any, Status, DuplicateGroup] = ZStream.empty
          override def `export`(request: ExportRequest): ZStream[Any, Status, ExportChunk]                  = ZStream(manifestChunk, frameChunk)
          override def subscribe(request: SubscribeRequest): ZStream[Any, Status, CatalogEvent]             = ZStream.empty
        }

        for {
          recorded       <- Ref.make(Chunk.empty[ClientFrame])
          gatewayStub     = RecordingGateway(
                              recorded,
                              Chunk(
                                ServerFrame(ServerFrame.Kind.StartAck(StartAck("sess", 60L))),
                                ServerFrame(ServerFrame.Kind.Ack(Ack("sess", acknowledgedSequence = 0L, receivedBytes = 5L))),
                                ServerFrame(
                                  ServerFrame.Kind.Completed(Completed("sess", "doc", "hash", "application/octet-stream", finalUrl = None))
                                ),
                              ),
                            )
          uploadSvc       = new InMemoryUploadService
          gatewayClient   = new GravitonUploadGatewayClientZIO(gatewayStub, uploadSvc)
          catalogClient   = new GravitonCatalogClientZIO(catalogStub)
          exportedFrames <- catalogClient.exportData(ExportPlan(documentIds = Chunk("doc"))).runCollect
          _              <- gatewayClient.uploadFrames(
                              StartUpload(objectContentType = "application/octet-stream", metadata = List.empty),
                              ZStream.fromIterable(
                                exportedFrames.collect { case ExportChunk(ExportChunk.Kind.Frame(f)) =>
                                  Left(
                                    DataFrame(
                                      sessionId = "sess",
                                      sequence = 0L,
                                      offsetBytes = 0L,
                                      contentType = f.contentType,
                                      bytes = f.bytes,
                                      last = true,
                                    )
                                  )
                                }
                              ),
                              complete = Complete(sessionId = "sess"),
                            )
          captured       <- recorded.get
        } yield assertTrue(exportedFrames.length == 2, captured.nonEmpty)
      },
      test("subscribe relays catalog events") {
        val event = CatalogEvent(topic = "catalog", message = "updated", attributes = Map("documentId" -> "doc"))

        val stub = new CatalogClient {
          override def search(request: SearchRequest): ZStream[Any, Status, SearchResult]                   = ZStream.empty
          override def list(request: ListRequest): IO[Status, ListResponse]                                 = ZIO.fail(Status.INTERNAL)
          override def get(request: GetRequest): IO[Status, GetResponse]                                    = ZIO.fail(Status.INTERNAL)
          override def findDuplicates(request: FindDuplicatesRequest): ZStream[Any, Status, DuplicateGroup] = ZStream.empty
          override def `export`(request: ExportRequest): ZStream[Any, Status, ExportChunk]                  = ZStream.empty
          override def subscribe(request: SubscribeRequest): ZStream[Any, Status, CatalogEvent]             = ZStream.succeed(event)
        }

        val client = new GravitonCatalogClientZIO(stub)
        client.subscribe(Chunk("catalog")).take(1).runCollect.map(out => assertTrue(out.head.message == "updated"))
      },
    )

  private final case class RecordingCatalogClient(ref: Ref[Option[SearchRequest]]) extends CatalogClient {
    override def search(request: SearchRequest): ZStream[Any, Status, SearchResult] =
      ZStream.unwrap(ref.set(Some(request)).as(ZStream.empty))

    override def list(request: ListRequest): IO[Status, ListResponse]                                 = ZIO.fail(Status.UNIMPLEMENTED)
    override def get(request: GetRequest): IO[Status, GetResponse]                                    = ZIO.fail(Status.UNIMPLEMENTED)
    override def findDuplicates(request: FindDuplicatesRequest): ZStream[Any, Status, DuplicateGroup] = ZStream.empty
    override def `export`(request: ExportRequest): ZStream[Any, Status, ExportChunk]                  = ZStream.empty
    override def subscribe(request: SubscribeRequest): ZStream[Any, Status, CatalogEvent]             = ZStream.empty
  }

  private final case class RecordingGateway(ref: Ref[Chunk[ClientFrame]], responses: Chunk[ServerFrame]) extends UploadGatewayClient {
    override def stream(requests: ZStream[Any, Throwable, ClientFrame]): ZStream[Any, Status, ServerFrame] =
      ZStream.unwrap {
        requests.runCollect.flatMap { collected =>
          ref.set(collected) *> ZIO.succeed(ZStream.fromChunk(responses))
        }
      }
  }

  private final class InMemoryUploadService extends GravitonUploadGatewayClientZIO.UploadServiceClient {
    override def registerUpload(request: RegisterUploadRequest): IO[Status, RegisterUploadResponse] =
      ZIO.succeed(RegisterUploadResponse(sessionId = request.clientSessionId.getOrElse("session"), ttlSeconds = 30L))

    override def uploadParts(request: ZStream[Any, Throwable, UploadPartRequest]): ZStream[Any, Status, UploadPartsResponse] =
      ZStream.unwrap(request.runDrain.as(ZStream.empty))

    override def completeUpload(request: CompleteUploadRequest): IO[Status, CompleteUploadResponse] =
      ZIO.succeed(
        CompleteUploadResponse(
          documentId = "doc",
          blobHash = request.expectedObjectHash.getOrElse("hash"),
          objectContentType = "application/octet-stream",
          finalUrl = None,
        )
      )
  }
}
