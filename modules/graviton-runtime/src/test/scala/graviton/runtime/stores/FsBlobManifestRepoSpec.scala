package graviton.runtime.stores

import graviton.core.bytes.Hasher
import graviton.core.attributes.BinaryAttributes
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.{FramedManifest, ManifestEntry}
import graviton.core.ranges.Span
import graviton.core.types.{BlobOffset, FileSize, Mime, UploadChunkSize}
import graviton.runtime.model.{BlobWritePlan, BlockBatchResult, BlockWritePlan, CanonicalBlock, InventoryPageSize}
import graviton.streams.Chunker
import zio.*
import zio.stream.{ZSink, ZStream}
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object FsBlobManifestRepoSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("FsBlobManifestRepo")(
      test("persists a CAS round-trip across fresh store instances") {
        withTempDir { root =>
          val data    = Chunk.fromArray(Array.tabulate(32_000)(index => (index % 251).toByte))
          val chunker = Chunker.fixed(UploadChunkSize(4096))

          for
            firstStore  <- makeStore(root)
            result      <- Chunker.locally(chunker)(ZStream.fromChunk(data).run(firstStore.put()))
            secondStore <- makeStore(root)
            readBack    <- secondStore.get(result.key).runCollect
            stat        <- secondStore.stat(result.key)
          yield assertTrue(
            readBack == data,
            stat.exists(_.size.value == data.length.toLong),
          )
        }
      },
      test("writes a versioned manifest and preserves its ingestion time") {
        withTempDir { root =>
          val data = Chunk.fromArray("durable-manifest".getBytes(StandardCharsets.UTF_8))

          for
            ingestedAt <- Clock.instant
            store      <- makeStore(root)
            result     <- ZStream.fromChunk(data).run(store.put())
            blob        = result.key.asInstanceOf[BinaryKey.Blob]
            repo        = new FsBlobManifestRepo(root)
            stored     <- repo.get(blob).someOrFail(new NoSuchElementException("manifest missing"))
            exists     <- ZIO.attemptBlocking(Files.isRegularFile(repo.pathFor(blob)))
          yield assertTrue(
            exists,
            stored.manifest.size == data.length.toLong,
            stored.ingestedAt == ingestedAt,
          )
        }
      },
      test("lists persisted manifests and exposes their real block layouts") {
        withTempDir { root =>
          val first  = Chunk.fromArray("first inventory blob".getBytes(StandardCharsets.UTF_8))
          val second = Chunk.fromArray(Array.tabulate(9000)(index => (index % 127).toByte))

          for
            store        <- makeStore(root)
            firstResult  <- ZStream.fromChunk(first).run(store.put())
            secondResult <- ZStream.fromChunk(second).run(store.put())
            listed       <- store.streamInventory.runCollect
            inspected    <- store.inspect(secondResult.key).someOrFail(new NoSuchElementException("blob missing from inventory"))
          yield assertTrue(
            listed.map(_.key).toSet == Set(firstResult.key, secondResult.key),
            inspected.listing.key == secondResult.key,
            inspected.listing.stat.size.value == second.length.toLong,
            inspected.blocks.nonEmpty,
            inspected.blocks.map(_.size).sum == second.length.toLong,
          )
        }
      },
      test("delete removes the manifest but retains deduplicated blocks") {
        withTempDir { root =>
          val data = Chunk.fromArray("manifest-only-delete".getBytes(StandardCharsets.UTF_8))

          for
            blockStore <- ZIO.succeed(new FsBlockStore(root))
            repo        = new FsBlobManifestRepo(root)
            store       = new CasBlobStore(blockStore, repo)
            result     <- ZStream.fromChunk(data).run(store.put())
            blob        = result.key.asInstanceOf[BinaryKey.Blob]
            manifest   <- repo.get(blob).someOrFail(new NoSuchElementException("manifest missing"))
            block       = manifest.manifest.entries.head.key.asInstanceOf[BinaryKey.Block]
            _          <- store.delete(blob)
            after      <- repo.get(blob)
            blockStill <- blockStore.exists(block)
          yield assertTrue(after.isEmpty, blockStill)
        }
      },
      test("rejects a corrupted manifest instead of returning partial data") {
        withTempDir { root =>
          val data = Chunk.fromArray("corrupt-me".getBytes(StandardCharsets.UTF_8))

          for
            store   <- makeStore(root)
            result  <- ZStream.fromChunk(data).run(store.put())
            blob     = result.key.asInstanceOf[BinaryKey.Blob]
            repo     = new FsBlobManifestRepo(root)
            _       <- ZIO.attemptBlocking(Files.write(repo.pathFor(blob), Array[Byte](1, 2, 3)))
            failure <- repo.get(blob).exit
          yield assertTrue(failure.isFailure)
        }
      },
      test("rejects the retired framed-manifest storage format") {
        withTempDir { root =>
          val data = Chunk.fromArray("current-format-only".getBytes(StandardCharsets.UTF_8))

          for
            store   <- makeStore(root)
            result  <- ZStream.fromChunk(data).run(store.put())
            repo     = new FsBlobManifestRepo(root)
            stored  <- repo.get(result.key).someOrFail(new NoSuchElementException("manifest missing"))
            retired <- ZIO.fromEither(FramedManifest.encode(stored.manifest)).mapError(new IllegalArgumentException(_))
            _       <- ZIO.attemptBlocking(Files.write(repo.pathFor(result.key), retired.bytes))
            failure <- repo.get(result.key).exit
          yield assertTrue(
            failure.isFailure,
            failure.causeOption.flatMap(_.failureOption).exists(_.getMessage.contains("Not a Graviton GVM5 streaming manifest")),
          )
        }
      },
      test("concurrent idempotent writes remain decodable") {
        withTempDir { root =>
          val data = Chunk.fromArray("concurrent-manifest".getBytes(StandardCharsets.UTF_8))

          for
            store    <- makeStore(root)
            result   <- ZStream.fromChunk(data).run(store.put())
            blob      = result.key.asInstanceOf[BinaryKey.Blob]
            repo      = new FsBlobManifestRepo(root)
            stored   <- repo.get(blob).someOrFail(new NoSuchElementException("manifest missing"))
            now      <- Clock.instant
            _        <- ZIO.foreachParDiscard(1 to 12)(_ => repo.put(blob, stored.manifest, now))
            reloaded <- repo.get(blob)
          yield assertTrue(reloaded.exists(_.manifest == stored.manifest))
        }
      },
      test("streams manifests larger than the materialized inspection limit") {
        withTempDir { root =>
          val entryCount = FsBlobManifestRepo.MaxMaterializedEntries + 1

          for
            hasher    <- ZIO.fromEither(Hasher.systemDefault).mapError(new IllegalStateException(_))
            _          = hasher.update(Chunk.single(1.toByte))
            digest    <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
            blockBits <- ZIO
                           .fromEither(KeyBits.fromLong(hasher.algo, digest, 1L))
                           .mapError(new IllegalArgumentException(_))
            block     <- ZIO.fromEither(BinaryKey.block(blockBits)).mapError(new IllegalArgumentException(_))
            blobBits  <- ZIO
                           .fromEither(KeyBits.fromLong(hasher.algo, digest, entryCount.toLong))
                           .mapError(new IllegalArgumentException(_))
            blob      <- ZIO.fromEither(BinaryKey.blob(blobBits)).mapError(new IllegalArgumentException(_))
            now       <- Clock.instant
            repo       = new FsBlobManifestRepo(root)
            entries    = ZStream.fromIterable(0 until entryCount).map { index =>
                           val offset = BlobOffset.unsafe(index.toLong)
                           ManifestEntry(block, Span.unsafe(offset, offset), Map.empty)
                         }
            _         <- repo.putStream(blob, FileSize.unsafe(entryCount.toLong), entryCount, entries, now)
            summary   <- repo.getSummary(blob).someOrFail(new NoSuchElementException("manifest missing"))
            summaries <- repo.streamSummaries.runCollect
            refs      <- repo.streamBlockRefs(blob).runCount
            inspect   <- repo.get(blob).exit
          yield assertTrue(
            summary.totalSize.value == entryCount.toLong,
            summary.blockCount == entryCount,
            summaries.map(_._2.blockCount).contains(entryCount),
            refs == entryCount.toLong,
            inspect.isFailure,
          )
        }
      },
      test("persists bounded semantic metadata and pages manifest blocks") {
        withTempDir { root =>
          val data    = Chunk.fromArray(Array.tabulate(12_000)(index => (index % 251).toByte))
          val chunker = Chunker.fixed(UploadChunkSize(4096))
          val plan    = BlobWritePlan(attributes = BinaryAttributes.empty.advertiseMime(Mime.applyUnsafe("application/pdf")))

          for
            store    <- makeStore(root)
            result   <- Chunker.locally(chunker)(ZStream.fromChunk(data).run(store.put(plan)))
            metadata <- store.metadata(result.key).someOrFail(new NoSuchElementException("blob metadata missing"))
            first    <- store
                          .inspectPage(result.key, None, InventoryPageSize.applyUnsafe(1))
                          .someOrFail(new NoSuchElementException("manifest missing"))
            second   <- store
                          .inspectPage(result.key, first.next, InventoryPageSize.applyUnsafe(1))
                          .someOrFail(new NoSuchElementException("second manifest page missing"))
          yield assertTrue(
            metadata.canonicalMediaType == "application/pdf",
            metadata.chunker.value != "legacy-unspecified",
            first.blocks.length == 1,
            first.next.nonEmpty,
            second.blocks.length == 1,
            second.blocks.head.index == 1L,
            second.blocks.head.offset == first.blocks.head.size,
          )
        }
      },
      test("rejects an inconsistent stream header before pulling entries") {
        withTempDir { root =>
          val data = Chunk.single(1.toByte)

          for
            store    <- makeStore(root)
            result   <- ZStream.fromChunk(data).run(store.put())
            repo      = new FsBlobManifestRepo(root)
            pulls    <- Ref.make(0)
            entries   = ZStream.fromZIO(pulls.update(_ + 1)).drain
            now      <- Clock.instant
            exit     <- repo
                          .putStream(result.key, FileSize.unsafe(2L), 1, entries, now)
                          .exit
            observed <- pulls.get
          yield assertTrue(
            exit.isFailure,
            observed == 0,
          )
        }
      },
      test("authenticates the manifest before fetching the first block") {
        withTempDir { root =>
          val data = Chunk.fromArray(Array.tabulate(16_000)(index => (index % 239).toByte))

          for
            integrity <- makeIntegrity
            delegate  <- InMemoryBlockStore.make
            reads     <- Ref.make(0)
            blocks     = new CountingBlockStore(delegate, reads)
            repo       = FsBlobManifestRepo.authenticated(root, integrity)
            store      = new CasBlobStore(blocks, repo)
            result    <- ZStream.fromChunk(data).run(store.put())
            blob       = result.key.asInstanceOf[BinaryKey.Blob]
            clean     <- store.get(blob).runCollect
            _         <- tamperSignature(repo.pathFor(blob))
            before    <- reads.get
            failure   <- store.get(blob).runHead.exit
            after     <- reads.get
          yield assertTrue(
            clean == data,
            before > 0,
            failure.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[StoreError.CorruptData]),
            after == before,
          )
        }
      },
    )

  private def makeStore(root: Path): UIO[BlobStore] =
    ZIO.succeed(new CasBlobStore(new FsBlockStore(root), new FsBlobManifestRepo(root)))

  private def makeIntegrity: Task[ManifestIntegrity] =
    for
      keyId   <- ZIO.fromEither(ManifestKeyId.either("test-key")).mapError(new IllegalArgumentException(_))
      hmacKey <- ZIO
                   .fromEither(ManifestKeyService.HmacKey.fromBytes(Array.tabulate[Byte](32)(index => (index + 1).toByte)))
                   .mapError(new IllegalArgumentException(_))
      service <- ZIO
                   .fromEither(ManifestKeyService.hmac(keyId, Map(keyId -> hmacKey)))
                   .mapError(new IllegalArgumentException(_))
    yield ManifestIntegrity(service)

  private def tamperSignature(path: Path): Task[Unit] =
    for
      header <- StreamingManifestFile.readEnvelopeHeader(path)
      proof  <- ZIO
                  .fromOption(header.authentication.map(_.proof))
                  .orElseFail(new IllegalStateException("authenticated manifest proof is missing"))
      _      <- ZIO.attemptBlocking {
                  val bytes     = Files.readAllBytes(path)
                  val signature = proof.signature.toArray
                  val offset    = indexOf(bytes, signature)
                  if offset < 0 then throw new IllegalStateException("manifest signature was not found in encoded file")
                  bytes(offset) = (bytes(offset) ^ 0x01).toByte
                  Files.write(path, bytes)
                  ()
                }
    yield ()

  private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
    var offset = 0
    while offset <= haystack.length - needle.length do
      var index = 0
      while index < needle.length && haystack(offset + index) == needle(index) do index += 1
      if index == needle.length then return offset
      offset += 1
    -1

  private def withTempDir[A](f: Path => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("graviton-manifest-test-"))
    )(dir =>
      ZIO.attemptBlocking {
        Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach { path =>
            val _ = Files.deleteIfExists(path)
            ()
          }
      }.orDie
    )(f)

  private final class CountingBlockStore(delegate: BlockStore, reads: Ref[Int]) extends BlockStore:
    override def putBlocks(plan: BlockWritePlan = BlockWritePlan()): ZSink[Any, StoreError, CanonicalBlock, Nothing, BlockBatchResult] =
      delegate.putBlocks(plan)

    override def get(key: BinaryKey.Block): ZStream[Any, StoreError, Byte] =
      ZStream.fromZIO(reads.update(_ + 1)) *> delegate.get(key)

    override def exists(key: BinaryKey.Block): IO[StoreError, Boolean] = delegate.exists(key)
