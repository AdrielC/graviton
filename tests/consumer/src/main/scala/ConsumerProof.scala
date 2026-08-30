import ai.hylo.graviton.client.GravitonClient
import graviton.backend.pg.{PgMaintenanceCoordinator, PgMutableObjectStore}
import graviton.backend.laws.BlobStoreLaws
import graviton.backend.rocks.RocksKeyValueStore
import graviton.backend.s3.S3BlobStore
import graviton.core.attributes.BinaryAttributes
import graviton.integration.shardcake.{ShardcakeInternalToken, ShardcakeUploadConfig}
import graviton.pdf.PdfIngest
import graviton.protocol.grpc.GravitonGrpcClient
import graviton.runtime.Graviton
import graviton.runtime.kv.{KvKey, KvValue}
import graviton.runtime.upload.{TenantId, UploadSessionId, UploadSessionKey}
import graviton.shared.ApiModels.HealthResponse
import graviton.streams.BoundedByteStream
import io.graviton.blobstore.v1.blob_service.BlobKey
import zio.*
import zio.blocks.mediatype.MediaType
import zio.http.{Client, URL}
import zio.pdf.PdfMime
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

object ConsumerProof extends ZIOAppDefault:
  override def run: Task[Unit] =
    val payload = Chunk.fromArray("external consumer round trip".getBytes(StandardCharsets.UTF_8))
    val pdfPayload = Chunk.fromArray(
      ("%PDF-1.7\n" +
        "1 0 obj\n<</Type /Catalog>>\nendobj\n" +
        "trailer\n<</Root 1 0 R>>\nstartxref\n0\n%%EOF\n").getBytes(StandardCharsets.US_ASCII)
    )

    for
      graviton <- Graviton.inMemory(chunkSize = 8)
      attributes = BinaryAttributes.empty
      _          <- ZIO.fromEither(attributes.validate).unit.mapError(new IllegalStateException(_))
      written  <- ZStream.fromChunk(payload).run(graviton.blobStore.put())
      restored <- BoundedByteStream.collectInMemory(graviton.blobStore.get(written.key))
      _        <- ZIO.fail(new IllegalStateException("published artifact did not round-trip bytes")).unless(restored == payload)
      _        <- graviton.maintenance.withMaintenance(ZIO.unit)
      pdf      <- PdfIngest.put(graviton.blobStore, PdfMime.mimeType, ZStream.fromChunk(pdfPayload).rechunk(7))
      restoredPdf <- BoundedByteStream.collectInMemory(graviton.blobStore.get(pdf.key))
      _        <- ZIO
                    .fail(new IllegalStateException("published PDF artifact did not round-trip bytes"))
                    .unless(restoredPdf == pdfPayload && pdf.stats.blockCount > 0)
      baseUrl  <- ZIO.fromEither(URL.decode("http://127.0.0.1:8081"))
      client   <- GravitonClient.make(GravitonClient.Config(baseUrl)).provideLayer(Client.default)
      mediaType = MediaType.unsafeFromString("application/octet-stream")
      maxSize   = GravitonClient.BlobByteLength.applyUnsafe(1099511627776L)
      resumablePartSize = GravitonClient.ResumablePartSize.Default
      resumableCall     = client.uploadResumable(
                            GravitonClient.Upload(
                              ZStream.fromChunk(payload),
                              mediaType,
                              Some(GravitonClient.BlobByteLength.applyUnsafe(payload.length.toLong)),
                            ),
                            resumablePartSize,
                            _ => ZIO.unit,
                          )
      kvKey     = KvKey.applyUnsafe("consumer/proof")
      kvValue  <- ZIO.fromEither(KvValue.fromChunk(payload)).mapError(new IllegalArgumentException(_))
      token     = GravitonGrpcClient.BearerToken.applyUnsafe("consumer-proof-token")
      localityToken = ShardcakeInternalToken.applyUnsafe("external-consumer-shardcake-token-0001")
      tenant        = TenantId.applyUnsafe("9f2f172c-8e6b-4aef-8be8-4c750420d971")
      session       = UploadSessionId.applyUnsafe("ab573594-abaa-44fa-867a-8c733bf87f6c")
      sessionKey    = UploadSessionKey(tenant, session)
      locality      <- ZIO
                         .fromEither(
                           ShardcakeUploadConfig.Default
                             .copy(enabled = true, internalToken = Some(localityToken))
                             .validate
                         )
                         .mapError(new IllegalStateException(_))
      protoKey  = BlobKey(written.key.bits.render)
      health    = HealthResponse("ok", "external-consumer", 0L)
      _        <- ZIO.fail(new IllegalStateException("backend modules did not resolve")).unless(
                    classOf[PgMutableObjectStore].getName.nonEmpty &&
                      classOf[PgMaintenanceCoordinator].getName.nonEmpty &&
                      BlobStoreLaws.getClass.getName.nonEmpty &&
                      classOf[RocksKeyValueStore].getName.nonEmpty &&
                      S3BlobStore.PartSize.Default.value >= 5 * 1024 * 1024
                  )
      _        <- Console.printLine(
                    s"external-consumer-proof key=${protoKey.value} bytes=${restored.length} pdf=${pdf.key.bits.render}:${restoredPdf.length} " +
                      s"media=${mediaType.fullType} max=$maxSize kv=${kvKey.value}:${kvValue.length} token-bytes=${token.value.length} " +
                      s"maintenance=ok resumable-part=${resumablePartSize.value} resumable-effect=${resumableCall.getClass.getSimpleName} " +
                      s"health=${health.status} locality=${sessionKey.tenantId.value}/${sessionKey.uploadSessionId.value}:${locality.numberOfShards}"
                  )
    yield ()
