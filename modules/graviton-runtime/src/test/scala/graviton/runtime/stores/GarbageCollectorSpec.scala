package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.model.CanonicalBlock
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.time.Instant

object GarbageCollectorSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("GarbageCollector")(
    test("dry-run, quarantine, and restore preserve referenced blocks") {
      withTempDir { root =>
        for
          blocks      <- ZIO.succeed(new FsBlockStore(root))
          manifests   <- ZIO.succeed(new FsBlobManifestRepo(root))
          cas         <- ZIO.succeed(new CasBlobStore(blocks, manifests))
          written     <- ZStream.fromIterable("referenced-data".getBytes(StandardCharsets.UTF_8)).run(cas.put())
          referenced  <- cas.inspect(written.key).someOrFail(new IllegalStateException("missing manifest"))
          orphan      <- canonical("orphan-data")
          _           <- ZStream.succeed(orphan).run(blocks.putBlocks())
          now          = Instant.parse("2030-01-01T00:00:00Z")
          old          = FileTime.from(now.minusSeconds(3600))
          _           <- ZIO.attemptBlocking(Files.setLastModifiedTime(blocks.pathFor(orphan.key), old))
          _           <- TestClock.setTime(now)
          collector    = new GarbageCollector(manifests, blocks)
          dry         <- collector.collect(1.minute, dryRun = true)
          stillThere  <- blocks.exists(orphan.key)
          swept       <- collector.collect(1.minute, dryRun = false)
          removed     <- blocks.exists(orphan.key)
          _           <- collector.restore(swept.quarantined)
          restored    <- blocks.exists(orphan.key)
          liveBlocks   = referenced.blocks.map(_.key).toSet
          livePresent <- ZIO.foreach(liveBlocks)(blocks.exists)
        yield assertTrue(
          dry.candidateBlocks == 1,
          stillThere,
          swept.quarantined.length == 1,
          !removed,
          restored,
          livePresent.forall(identity),
        )
      }
    }
  )

  private def canonical(value: String): Task[CanonicalBlock] =
    val bytes = Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes.toArray))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      bits   <- ZIO.fromEither(KeyBits.create(hasher.algo, digest, bytes.length.toLong)).mapError(new IllegalArgumentException(_))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(new IllegalArgumentException(_))
      block  <- ZIO.fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty)).mapError(new IllegalArgumentException(_))
    yield block

  private def withTempDir[A](effect: Path => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking(Files.createTempDirectory("graviton-gc-")))(path =>
      ZIO.attemptBlocking {
        val paths = Files.walk(path)
        try
          paths.sorted(java.util.Comparator.reverseOrder()).forEach { item =>
            val _ = Files.deleteIfExists(item); ()
          }
        finally paths.close()
      }.orDie
    )(effect)
