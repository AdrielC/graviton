import ai.hylo.graviton.client.GravitonClient
import graviton.runtime.Graviton
import graviton.streams.BoundedByteStream
import zio.*
import zio.blocks.mediatype.MediaType
import zio.http.{Client, URL}
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

object ConsumerProof extends ZIOAppDefault:
  override def run: Task[Unit] =
    val payload = Chunk.fromArray("external consumer round trip".getBytes(StandardCharsets.UTF_8))

    for
      graviton <- Graviton.inMemory(chunkSize = 8)
      written  <- ZStream.fromChunk(payload).run(graviton.blobStore.put())
      restored <- BoundedByteStream.collectInMemory(graviton.blobStore.get(written.key))
      _        <- ZIO.fail(new IllegalStateException("published artifact did not round-trip bytes")).unless(restored == payload)
      baseUrl  <- ZIO.fromEither(URL.decode("http://127.0.0.1:8081"))
      _        <- GravitonClient.make(GravitonClient.Config(baseUrl)).provideLayer(Client.default)
      mediaType = MediaType.unsafeFromString("application/octet-stream")
      maxSize   = GravitonClient.BlobByteLength.applyUnsafe(1099511627776L)
      _        <- Console.printLine(
                    s"external-consumer-proof key=${written.key.bits.render} bytes=${restored.length} media=${mediaType.fullType} max=$maxSize"
                  )
    yield ()
