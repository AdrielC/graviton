package ai.hylo.graviton.client

import graviton.protocol.http.HttpApi
import graviton.runtime.Graviton
import graviton.shared.ApiModels.BlobVerificationResult
import zio.*
import zio.blocks.mediatype.MediaType as BlocksMediaType
import zio.http.*
import zio.stream.ZStream
import zio.test.*

import java.security.MessageDigest
import java.util.HexFormat

object GravitonClientSpec extends ZIOSpecDefault:

  private val OneTiB = 1099511627776L

  override def spec: Spec[TestEnvironment, Any] =
    suite("GravitonClient")(
      test("a logical 1 TiB upload is a non-materialized streaming request") {
        val program =
          for
            client   <- GravitonClient.make(
                          GravitonClient.Config(URL.decode("http://127.0.0.1:1").toOption.get)
                        )
            pulled   <- Ref.make(false)
            request   = client.uploadRequest(
                          GravitonClient.Upload(
                            bytes = ZStream.fromZIO(pulled.set(true)) *> ZStream.fail(new AssertionError("must stay lazy")),
                            contentType = BlocksMediaType.unsafeFromString("application/octet-stream"),
                            contentLength = Some(GravitonClient.BlobByteLength.applyUnsafe(OneTiB)),
                          )
                        )
            observed <- pulled.get
          yield assertTrue(
            request.body.knownContentLength.contains(OneTiB),
            request.body.materializedContent.isEmpty,
            !observed,
          )

        program.provideLayer(Client.default)
      },
      test("invalid programmatic media types fail before transport or source pull") {
        val invalid = BlocksMediaType("application", "pdf", parameters = Map("name" -> "\"line\nfeed\""))
        val program =
          for
            client   <- GravitonClient.make(
                          GravitonClient.Config(URL.decode("http://127.0.0.1:1").toOption.get)
                        )
            pulled   <- Ref.make(false)
            exit     <- client
                          .upload(
                            GravitonClient.Upload(
                              bytes = ZStream.fromZIO(pulled.set(true)).as(1.toByte),
                              contentType = invalid,
                              contentLength = None,
                            )
                          )
                          .exit
            observed <- pulled.get
          yield assertTrue(
            exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[GravitonClient.Error.InvalidMediaType]),
            !observed,
          )

        program.provideLayer(Client.default)
      },
      test("real SDK and streaming server round-trip 32 MiB over a socket") {
        val chunk       = Chunk.fromArray(Array.tabulate[Byte](64 * 1024)(index => (index % 251).toByte))
        val repetitions = 512
        val totalBytes  = chunk.length.toLong * repetitions.toLong
        val source      = ZStream.fromIterable(0 until repetitions).flatMap(_ => ZStream.fromChunk(chunk))

        val program =
          for
            graviton    <- Graviton.inMemory(chunkSize = 1024 * 1024)
            port        <- Server.install(HttpApi(graviton.blobStore).routes)
            baseUrl     <- ZIO.fromEither(URL.decode(s"http://127.0.0.1:$port"))
            sdk         <- GravitonClient.make(GravitonClient.Config(baseUrl))
            uploaded    <- sdk.upload(
                             GravitonClient.Upload(
                               bytes = source,
                               contentType = BlocksMediaType.unsafeFromString("application/octet-stream"),
                               contentLength = Some(GravitonClient.BlobByteLength.applyUnsafe(totalBytes)),
                             )
                           )
            digest       = MessageDigest.getInstance("SHA-256")
            downloaded  <- sdk
                             .download(uploaded.blob.id)
                             .mapChunksZIO(bytes => ZIO.attempt(digest.update(bytes.toArray)).as(bytes))
                             .runCount
            verified    <- sdk.verify(uploaded.blob.id)
            metadata    <- sdk.metadata(uploaded.blob.id)
            inventory   <- sdk.list()
            expectedHash = HexFormat.of().formatHex(digest.digest())
          yield assertTrue(
            uploaded.blob.size.value == totalBytes,
            downloaded == totalBytes,
            expectedHash == uploaded.blob.digest,
            verified == BlobVerificationResult(uploaded.blob.id, verified = true, uploaded.blob.size),
            metadata.summary == uploaded.blob,
            inventory.blobs.exists(_.id == uploaded.blob.id),
          )

        program.provide(
          Client.default,
          Server.defaultWith(_.onAnyOpenPort.enableRequestStreaming),
        )
      } @@ TestAspect.timeout(90.seconds),
    ) @@ TestAspect.sequential
