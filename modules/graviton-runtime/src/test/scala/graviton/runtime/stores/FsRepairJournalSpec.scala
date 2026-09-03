package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.model.CanonicalBlock
import zio.*
import zio.test.*

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*

object FsRepairJournalSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, Any] = suite("FsRepairJournal")(
    test("persists checkpoints and unresolved failures across fresh instances") {
      withTempDir { root =>
        for
          key      <- blockKey("durable repair failure")
          first     = new FsRepairJournal(root)
          failure   = StoreError.Unavailable(StoreOperation.Repair, StoreBackend.InMemory, new IOException("replica offline"))
          _        <- first.checkpoint(37L)
          _        <- first.recordFailure(key, failure, Instant.parse("2030-01-01T00:00:00Z"))
          _        <- first.recordFailure(key, failure, Instant.parse("2030-01-01T00:01:00Z"))
          restarted = new FsRepairJournal(root)
          cursor   <- restarted.loadCursor
          failures <- restarted.deadLetters.runCollect
          _        <- restarted.resolve(key)
          cleared  <- new FsRepairJournal(root).deadLetters.runCollect
        yield assertTrue(
          cursor == 37L,
          failures.length == 1,
          failures.head.attempts == 2L,
          failures.head.lastError.contains("unavailable"),
          failures.head.lastFailedAt == Instant.parse("2030-01-01T00:01:00Z"),
          cleared.isEmpty,
        )
      }
    },
    test("reports a corrupt durable cursor as typed corrupt data") {
      withTempDir { root =>
        val cursor = root.resolve("cas/repair/cursor")
        for
          _    <- ZIO.attemptBlocking {
                    Files.createDirectories(cursor.getParent)
                    Files.writeString(cursor, "not-a-cursor")
                    ()
                  }.orDie
          exit <- new FsRepairJournal(root).loadCursor.exit
        yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[StoreError.CorruptData]))
      }
    },
  )

  private def blockKey(value: String): Task[BinaryKey.Block] =
    val bytes = Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      bits   <- ZIO.fromEither(KeyBits.fromLong(hasher.algo, digest, bytes.length.toLong)).mapError(new IllegalArgumentException(_))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(new IllegalArgumentException(_))
      _      <- ZIO.fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty)).mapError(new IllegalArgumentException(_))
    yield key

  private def withTempDir[A](use: Path => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking(Files.createTempDirectory("graviton-repair-journal-")))(deleteTree)(use)

  private def deleteTree(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path) then
        val paths = Files.walk(path)
        try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
        finally paths.close()
    }.orDie
