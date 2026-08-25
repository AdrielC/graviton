import graviton.runtime.Graviton
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

object ConsumerProof extends ZIOAppDefault:
  override def run: Task[Unit] =
    val payload = Chunk.fromArray("external consumer round trip".getBytes(StandardCharsets.UTF_8))

    for
      graviton <- Graviton.inMemory(chunkSize = 8)
      written  <- ZStream.fromChunk(payload).run(graviton.blobStore.put())
      restored <- graviton.blobStore.get(written.key).runCollect
      _        <- ZIO.fail(new IllegalStateException("published artifact did not round-trip bytes")).unless(restored == payload)
      _        <- Console.printLine(s"external-consumer-proof key=${written.key.bits.render} bytes=${restored.length}")
    yield ()
