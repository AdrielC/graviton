package graviton.backend.pg

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.ranges.Span
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.model.{InventoryCursor, InventoryNamespace, InventoryPage, InventoryPageSize}
import graviton.runtime.stores.*
import graviton.runtime.streaming.BlobStreamer
import graviton.runtime.tenant.StorageDomainId
import graviton.runtime.upload.TenantId
import zio.*
import zio.stream.ZStream

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.Instant
import javax.sql.DataSource

/** PostgreSQL manifest repository scoped by both tenant and physical storage domain. */
final class PgTenantBlobManifestRepo private (
  dataSource: DataSource,
  tenantId: TenantId,
  storageDomainId: StorageDomainId,
  integrity: Option[ManifestIntegrity],
) extends BlobManifestRepo:

  def this(dataSource: DataSource, tenantId: TenantId, storageDomainId: StorageDomainId) =
    this(dataSource, tenantId, storageDomainId, None)

  override def healthCheck: IO[StoreError, Unit] =
    blocking(StoreOperation.HealthCheck) { connection =>
      val statement = connection.prepareStatement("SELECT 1")
      try
        val result = statement.executeQuery()
        try
          if !result.next() || result.getInt(1) != 1 then throw new IllegalStateException("PostgreSQL readiness query failed")
        finally result.close()
      finally statement.close()
    }.unit

  override def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): IO[StoreError, Unit] =
    ZIO
      .fail(
        StoreError.InvalidInput(StoreOperation.PutManifest, s"manifest size ${manifest.size} does not match blob size ${blob.bits.size}")
      )
      .unless(manifest.size == blob.bits.size) *>
      putStream(blob, FileSize.unsafe(manifest.size), manifest.entries.length, ZStream.fromIterable(manifest.entries), ingestedAt)

  override def putStream(
    blob: BinaryKey.Blob,
    totalSize: FileSize,
    blockCount: Int,
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    putStreamInternal(
      ManifestIdentity(blob, totalSize, blockCount, ManifestChunkerId.applyUnsafe("legacy-unspecified")),
      entries,
      ingestedAt,
    )

  override def putAuthenticatedStream(
    identity: ManifestIdentity,
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    putStreamInternal(identity, entries, ingestedAt)

  private def putStreamInternal(
    identity: ManifestIdentity,
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    val blob       = identity.blob
    val totalSize  = identity.totalSize
    val blockCount = identity.blockCount
    ZIO
      .fromEither(BlobManifestRepo.validateStreamArguments(blob, totalSize, blockCount))
      .mapError(StoreError.InvalidInput(StoreOperation.PutManifest, _)) *>
      transaction(StoreOperation.PutManifest) { connection =>
        for
          accumulator  <- ZIO.foreach(integrity)(_.accumulator(identity))
          _            <- reserveUsage(connection, blob, totalSize)
          _            <- upsertBlob(connection, blob, blockCount, ingestedAt)
          _            <- deleteEntries(connection, blob)
          authenticated = accumulator.fold(entries)(value => entries.tap(value.update))
          state        <- writeEntries(connection, blob, authenticated)
          _            <- ZIO
                            .fail(new IllegalArgumentException(s"manifest entry count mismatch: expected $blockCount, observed ${state.count}"))
                            .unless(state.count == blockCount)
          _            <- ZIO
                            .fail(new IllegalArgumentException(s"manifest size mismatch: expected ${totalSize.value}, observed ${state.offset}"))
                            .unless(state.offset == totalSize.value)
          proof        <- ZIO.foreach(accumulator)(_.prove)
          _            <- writeManifestProof(connection, blob, identity.chunker, proof)
        yield ()
      }

  override def get(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifest]] =
    getSummary(blob).flatMap {
      case None          => ZIO.none
      case Some(summary) =>
        if summary.blockCount > BlobManifestRepo.MaxMaterializedEntries then
          ZIO.fail(
            StoreError.InvalidInput(
              StoreOperation.GetManifest,
              s"manifest has ${summary.blockCount} entries; materialized inspection is limited to ${BlobManifestRepo.MaxMaterializedEntries}",
            )
          )
        else
          collectMaterializedRefs(blob, summary.blockCount).flatMap { refs =>
            val (_, entries) = refs.foldLeft((0L, List.empty[ManifestEntry])) { case ((offset, result), ref) =>
              val length = ref.key.bits.size
              val entry  = ManifestEntry(
                ref.key,
                Span.unsafe(BlobOffset.unsafe(offset), BlobOffset.unsafe(java.lang.Math.addExact(offset, length) - 1L)),
                Map.empty,
              )
              java.lang.Math.addExact(offset, length) -> (entry :: result)
            }
            ZIO
              .fromEither(Manifest.fromEntries(entries.reverse))
              .mapError(StoreError.InvalidInput(StoreOperation.GetManifest, _))
              .map(manifest => Some(StoredManifest(manifest, summary.ingestedAt)))
          }
    }

  /**
   * Compatibility materialization is bounded independently of the summary
   * row, then checked against that row so corrupt metadata cannot expand the
   * allocation or return a partial manifest.
   */
  private def collectMaterializedRefs(
    blob: BinaryKey.Blob,
    expectedCount: Int,
  ): IO[StoreError, List[BlobStreamer.BlockRef]] =
    streamBlockRefs(blob)
      .runFoldZIO((0, List.empty[BlobStreamer.BlockRef])) { case ((count, refs), ref) =>
        if count >= BlobManifestRepo.MaxMaterializedEntries then
          ZIO.fail(
            StoreError.InvalidInput(
              StoreOperation.GetManifest,
              s"manifest exceeds the ${BlobManifestRepo.MaxMaterializedEntries}-entry materialization limit",
            )
          )
        else ZIO.succeed((count + 1, ref :: refs))
      }
      .flatMap { case (count, reversed) =>
        ZIO
          .fail(
            StoreError.InvalidInput(
              StoreOperation.GetManifest,
              s"manifest entry count mismatch: expected $expectedCount, observed $count",
            )
          )
          .unless(count == expectedCount)
          .as(reversed.reverse)
      }

  override def getSummary(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifestSummary]] =
    for
      algorithm <- algorithm(blob, StoreOperation.GetManifest)
      result    <- blocking(StoreOperation.GetManifest) { connection =>
                     val statement = connection.prepareStatement(
                       """SELECT byte_length, block_count, created_at
                         |FROM graviton.tenant_blob
                         |WHERE tenant_id = ?::uuid AND storage_domain_id = ?
                         |  AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
                     )
                     try
                       bindScope(statement, 1)
                       statement.setString(3, algorithm)
                       statement.setBytes(4, blob.bits.digest.bytes)
                       statement.setLong(5, blob.bits.size)
                       val rows = statement.executeQuery()
                       try
                         if !rows.next() then None
                         else
                           val size = FileSize.either(rows.getLong(1)).fold(reason => throw new IllegalArgumentException(reason), identity)
                           Some(
                             StoredManifestSummary(
                               size,
                               rows.getInt(2),
                               Option(rows.getTimestamp(3)).fold(Instant.EPOCH)(_.toInstant),
                             )
                           )
                       finally rows.close()
                     finally statement.close()
                   }
    yield result

  override def inventoryPage(
    after: Option[InventoryCursor],
    limit: InventoryPageSize,
  ): IO[StoreError, InventoryPage[(BinaryKey.Blob, StoredManifestSummary)]] =
    (for
      anchor <- ZIO.fromEither(
                  after.fold[Either[String, Option[BinaryKey.Blob]]](Right(None))(cursor =>
                    InventoryCursor
                      .decode(cursor, InventoryNamespace.PostgreSql)
                      .flatMap(KeyBits.fromString)
                      .flatMap(BinaryKey.blob)
                      .map(Some(_))
                  )
                )
      rows   <- queryInventory(anchor, limit.value + 1)
      items   = rows.take(limit.value)
      next   <- ZIO.foreach(items.lastOption.filter(_ => rows.length > limit.value)) { case (blob, _) =>
                  ZIO.fromEither(InventoryCursor.encode(InventoryNamespace.PostgreSql, blob.bits.render))
                }
    yield InventoryPage(items, next)).mapError {
      case error: StoreError => error
      case reason: String    => StoreError.InvalidInput(StoreOperation.Inventory, reason)
    }

  override def delete(blob: BinaryKey.Blob): IO[StoreError, Boolean] =
    transaction(StoreOperation.DeleteManifest) { connection =>
      for
        usage    <- lockUsage(connection)
        existing <- findScopedBlobSize(connection, blob)
        removed  <- existing match
                      case None       => ZIO.succeed(false)
                      case Some(size) =>
                        deleteEntries(connection, blob) *>
                          deleteBlob(connection, blob).flatMap { count =>
                            ZIO
                              .fail(new IllegalStateException(s"expected one tenant manifest deletion, observed $count"))
                              .unless(count == 1) *>
                              releaseUsage(connection, usage, size).as(true)
                          }
      yield removed
    }

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, BlobStreamer.BlockRef] =
    integrity match
      case None          => rawBlockRefs(blob)
      case Some(service) => ZStream.unwrap(verifyManifest(blob, service).as(rawBlockRefs(blob)))

  private def rawBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, BlobStreamer.BlockRef] =
    val sql =
      """SELECT ordinal, block_alg, block_hash_bytes, block_byte_length
        |FROM graviton.tenant_blob_block
        |WHERE tenant_id = ?::uuid AND storage_domain_id = ?
        |  AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?
        |ORDER BY ordinal ASC""".stripMargin
    cursorStream(StoreOperation.GetManifest, sql, blob, None)
      .mapZIO(result => readBlockRef(result).mapError(StoreError.fromThrowable(StoreOperation.GetManifest, StoreBackend.PostgreSql)))

  override def streamBlockRefsRange(
    blob: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, BlobStreamer.RangedBlockRef] =
    val endExclusive = java.lang.Math.addExact(start.value, length.value)
    val sql          =
      """SELECT ordinal, block_alg, block_hash_bytes, block_byte_length, block_offset
        |FROM graviton.tenant_blob_block
        |WHERE tenant_id = ?::uuid AND storage_domain_id = ?
        |  AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?
        |  AND span && int8range(?, ?, '[)')
        |ORDER BY ordinal ASC""".stripMargin
    val raw          = cursorStream(StoreOperation.GetRange, sql, blob, Some(start.value -> endExclusive))
      .mapZIO(result => readRangedBlockRef(result).mapError(StoreError.fromThrowable(StoreOperation.GetRange, StoreBackend.PostgreSql)))
    integrity match
      case None          => raw
      case Some(service) => ZStream.unwrap(verifyManifest(blob, service).as(raw))

  private def verifyManifest(blob: BinaryKey.Blob, service: ManifestIntegrity): IO[StoreError, Unit] =
    for
      stored           <- readStoredAuthentication(blob)
      authentication   <- ZIO
                            .fromOption(stored)
                            .orElseFail(StoreError.CorruptData(StoreOperation.GetManifest, "manifest authentication proof is missing"))
      (identity, proof) = authentication
      accumulator      <- service.verificationAccumulator(identity)
      _                <- rawManifestEntries(blob).runForeach(accumulator.update)
      _                <- accumulator.verify(proof)
    yield ()

  private def rawManifestEntries(blob: BinaryKey.Blob): ZStream[Any, StoreError, ManifestEntry] =
    val sql =
      """SELECT ordinal, block_alg, block_hash_bytes, block_byte_length, block_offset, block_length
        |FROM graviton.tenant_blob_block
        |WHERE tenant_id = ?::uuid AND storage_domain_id = ?
        |  AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?
        |ORDER BY ordinal ASC""".stripMargin
    cursorStream(StoreOperation.GetManifest, sql, blob, None)
      .mapZIO(result => readManifestEntry(result).mapError(StoreError.fromThrowable(StoreOperation.GetManifest, StoreBackend.PostgreSql)))

  private def readStoredAuthentication(blob: BinaryKey.Blob): IO[StoreError, Option[(ManifestIdentity, ManifestProof)]] =
    algorithm(blob, StoreOperation.GetManifest).flatMap { algorithm =>
      blocking(StoreOperation.GetManifest) { connection =>
        val statement = connection.prepareStatement(
          """SELECT byte_length, block_count, manifest_proof_version, manifest_chunker,
            |       manifest_key_id, manifest_digest, manifest_signature
            |FROM graviton.tenant_blob
            |WHERE tenant_id = ?::uuid AND storage_domain_id = ?
            |  AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
        )
        try
          bindScope(statement, 1)
          statement.setString(3, algorithm)
          statement.setBytes(4, blob.bits.digest.bytes)
          statement.setLong(5, blob.bits.size)
          val rows = statement.executeQuery()
          try
            if !rows.next() || rows.getObject(3) == null then None
            else
              val size    = FileSize.either(rows.getLong(1)).fold(message => throw new IllegalArgumentException(message), identity)
              val chunker =
                ManifestChunkerId.either(rows.getString(4)).fold(message => throw new IllegalArgumentException(message), identity)
              val keyId   = ManifestKeyId.either(rows.getString(5)).fold(message => throw new IllegalArgumentException(message), identity)
              val proof   = ManifestProof
                .make(rows.getInt(3), keyId, Chunk.fromArray(rows.getBytes(6)), Chunk.fromArray(rows.getBytes(7)))
                .fold(message => throw new IllegalArgumentException(message), identity)
              Some(ManifestIdentity(blob, size, rows.getInt(2), chunker) -> proof)
          finally rows.close()
        finally statement.close()
      }
    }

  private def writeManifestProof(
    connection: Connection,
    blob: BinaryKey.Blob,
    chunker: ManifestChunkerId,
    proof: Option[ManifestProof],
  ): Task[Unit] =
    ZIO.fromEither(toDbAlgorithm(blob.bits.algo)).mapError(new IllegalArgumentException(_)).flatMap { algorithm =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """UPDATE graviton.tenant_blob SET
            |  manifest_proof_version = ?, manifest_chunker = ?, manifest_key_id = ?,
            |  manifest_digest = ?, manifest_signature = ?
            |WHERE tenant_id = ?::uuid AND storage_domain_id = ?
            |  AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
        )
        try
          proof match
            case None        => (1 to 5).foreach(statement.setNull(_, java.sql.Types.NULL))
            case Some(value) =>
              statement.setInt(1, value.version)
              statement.setString(2, chunker.value)
              statement.setString(3, value.keyId.value)
              statement.setBytes(4, value.canonicalDigest.toArray)
              statement.setBytes(5, value.signature.toArray)
          bindScope(statement, 6)
          statement.setString(8, algorithm)
          statement.setBytes(9, blob.bits.digest.bytes)
          statement.setLong(10, blob.bits.size)
          if statement.executeUpdate() != 1 then throw new IllegalStateException("tenant manifest proof row disappeared")
        finally statement.close()
      }
    }

  private def queryInventory(
    anchor: Option[BinaryKey.Blob],
    count: Int,
  ): IO[StoreError, Chunk[(BinaryKey.Blob, StoredManifestSummary)]] =
    blocking(StoreOperation.Inventory) { connection =>
      val anchorClause = anchor.fold("")(_ => "AND (alg::text, encode(hash_bytes, 'hex'), byte_length) > (?, ?, ?) ")
      val statement    = connection.prepareStatement(
        s"""SELECT alg, hash_bytes, byte_length, block_count, created_at
           |FROM graviton.tenant_blob
           |WHERE tenant_id = ?::uuid AND storage_domain_id = ?
           |$anchorClause
           |ORDER BY alg::text, encode(hash_bytes, 'hex'), byte_length
           |LIMIT ?""".stripMargin
      )
      try
        bindScope(statement, 1)
        anchor match
          case None       => statement.setInt(3, count)
          case Some(blob) =>
            statement.setString(3, toDbAlgorithm(blob.bits.algo).fold(reason => throw new IllegalArgumentException(reason), identity))
            statement.setString(4, blob.bits.digest.hex.value)
            statement.setLong(5, blob.bits.size)
            statement.setInt(6, count)
        val rows    = statement.executeQuery()
        val builder = ChunkBuilder.make[(BinaryKey.Blob, StoredManifestSummary)]()
        try while rows.next() do builder += readSummary(rows)
        finally rows.close()
        builder.result()
      finally statement.close()
    }

  private def upsertBlob(connection: Connection, blob: BinaryKey.Blob, blockCount: Int, ingestedAt: Instant): Task[Unit] =
    for
      _         <- ZIO
                     .fail(new IllegalArgumentException(s"manifest block count must be within 1..${BlobManifestRepo.MaxEntries}"))
                     .unless(blockCount >= 1 && blockCount <= BlobManifestRepo.MaxEntries)
      algorithm <- ZIO.fromEither(toDbAlgorithm(blob.bits.algo)).mapError(new IllegalArgumentException(_))
      _         <- ZIO.attemptBlocking {
                     val statement = connection.prepareStatement(
                       """INSERT INTO graviton.tenant_blob (
                         |  tenant_id, storage_domain_id, alg, hash_bytes, byte_length, block_count, created_at
                         |) VALUES (?::uuid, ?, ?::core.hash_alg, ?, ?, ?, ?)
                         |ON CONFLICT (tenant_id, storage_domain_id, alg, hash_bytes, byte_length) DO UPDATE SET
                         |  block_count = EXCLUDED.block_count,
                         |  created_at = EXCLUDED.created_at""".stripMargin
                     )
                     try
                       bindScope(statement, 1)
                       statement.setString(3, algorithm)
                       statement.setBytes(4, blob.bits.digest.bytes)
                       statement.setLong(5, blob.bits.size)
                       statement.setInt(6, blockCount)
                       statement.setTimestamp(7, java.sql.Timestamp.from(ingestedAt))
                       statement.executeUpdate()
                       ()
                     finally statement.close()
                   }
    yield ()

  private final case class LockedUsage(retainedBytes: Long, blobCount: Long, limitBytes: Long)

  private def reserveUsage(connection: Connection, blob: BinaryKey.Blob, totalSize: FileSize): Task[Unit] =
    for
      usage     <- lockUsage(connection)
      existing  <- findScopedBlobSize(connection, blob)
      additional = if existing.isDefined then 0L else totalSize.value
      _         <- ZIO
                     .fail(
                       StoreError.TenantStorageQuotaExceeded(
                         StoreOperation.PutManifest,
                         usage.limitBytes,
                         usage.retainedBytes,
                         additional,
                       )
                     )
                     .when(additional > usage.limitBytes - usage.retainedBytes)
      _         <- updateUsage(
                     connection,
                     retainedBytes = java.lang.Math.addExact(usage.retainedBytes, additional),
                     blobCount = java.lang.Math.addExact(usage.blobCount, if additional == 0L then 0L else 1L),
                   ).when(additional > 0L)
    yield ()

  private def releaseUsage(connection: Connection, usage: LockedUsage, removedBytes: Long): Task[Unit] =
    for
      _ <- ZIO
             .fail(
               new IllegalStateException(
                 s"tenant usage is corrupt: retained=${usage.retainedBytes} blobs=${usage.blobCount} removed=$removedBytes"
               )
             )
             .when(usage.retainedBytes < removedBytes || usage.blobCount < 1L)
      _ <- updateUsage(connection, usage.retainedBytes - removedBytes, usage.blobCount - 1L)
    yield ()

  private def lockUsage(connection: Connection): Task[LockedUsage] =
    ZIO.attemptBlocking {
      val initialize = connection.prepareStatement(
        """INSERT INTO graviton.tenant_storage_usage (tenant_id)
          |VALUES (?::uuid)
          |ON CONFLICT (tenant_id) DO NOTHING""".stripMargin
      )
      try
        initialize.setString(1, tenantId.value)
        initialize.executeUpdate()
      finally initialize.close()

      val statement = connection.prepareStatement(
        """SELECT u.retained_bytes, u.blob_count, p.max_retained_bytes
          |FROM graviton.tenant_storage_usage u
          |JOIN graviton.tenant_storage_policy p ON p.tenant_id = u.tenant_id
          |WHERE u.tenant_id = ?::uuid
          |FOR UPDATE OF u""".stripMargin
      )
      try
        statement.setString(1, tenantId.value)
        val rows = statement.executeQuery()
        try
          if !rows.next() then throw new IllegalStateException(s"tenant policy ${tenantId.value} disappeared while locking usage")
          LockedUsage(rows.getLong(1), rows.getLong(2), rows.getLong(3))
        finally rows.close()
      finally statement.close()
    }

  private def updateUsage(connection: Connection, retainedBytes: Long, blobCount: Long): Task[Unit] =
    ZIO.attemptBlocking {
      val statement = connection.prepareStatement(
        """UPDATE graviton.tenant_storage_usage
          |SET retained_bytes = ?, blob_count = ?, updated_at = clock_timestamp()
          |WHERE tenant_id = ?::uuid""".stripMargin
      )
      try
        statement.setLong(1, retainedBytes)
        statement.setLong(2, blobCount)
        statement.setString(3, tenantId.value)
        if statement.executeUpdate() != 1 then throw new IllegalStateException(s"tenant usage ${tenantId.value} disappeared")
      finally statement.close()
    }

  private def findScopedBlobSize(connection: Connection, blob: BinaryKey.Blob): Task[Option[Long]] =
    ZIO.fromEither(toDbAlgorithm(blob.bits.algo)).mapError(new IllegalArgumentException(_)).flatMap { algorithm =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT byte_length
            |FROM graviton.tenant_blob
            |WHERE tenant_id = ?::uuid AND storage_domain_id = ?
            |  AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?
            |FOR UPDATE""".stripMargin
        )
        try
          bindScope(statement, 1)
          statement.setString(3, algorithm)
          statement.setBytes(4, blob.bits.digest.bytes)
          statement.setLong(5, blob.bits.size)
          val rows = statement.executeQuery()
          try Option.when(rows.next())(rows.getLong(1))
          finally rows.close()
        finally statement.close()
      }
    }

  private def writeEntries(
    connection: Connection,
    blob: BinaryKey.Blob,
    entries: ZStream[Any, StoreError, ManifestEntry],
  ): Task[StreamState] =
    val blockSql =
      """INSERT INTO graviton.tenant_block (storage_domain_id, alg, hash_bytes, byte_length)
        |VALUES (?, ?::core.hash_alg, ?, ?)
        |ON CONFLICT (storage_domain_id, alg, hash_bytes, byte_length) DO NOTHING""".stripMargin
    val entrySql =
      """INSERT INTO graviton.tenant_blob_block (
        |  tenant_id, storage_domain_id, alg, hash_bytes, byte_length, ordinal,
        |  block_alg, block_hash_bytes, block_byte_length, block_offset, block_length
        |) VALUES (?::uuid, ?, ?::core.hash_alg, ?, ?, ?, ?::core.hash_alg, ?, ?, ?, ?)""".stripMargin

    ZIO.scoped {
      for
        blobAlgorithm <- ZIO.fromEither(toDbAlgorithm(blob.bits.algo)).mapError(new IllegalArgumentException(_))
        statements    <- ZIO.acquireRelease(
                           ZIO.attemptBlocking(Statements(connection.prepareStatement(blockSql), connection.prepareStatement(entrySql)))
                         )(value => ZIO.attemptBlocking(value.close()).orDie)
        state         <- entries
                           .rechunk(256)
                           .chunks
                           .runFoldZIO(StreamState.empty) { (current, batch) =>
                             ZIO.attemptBlocking {
                               var next = current
                               batch.foreach { entry =>
                                 if next.count >= BlobManifestRepo.MaxEntries then
                                   throw new IllegalArgumentException(s"manifest exceeds ${BlobManifestRepo.MaxEntries} entries")
                                 if entry.annotations.nonEmpty then
                                   throw new IllegalArgumentException("CAS manifest entries must not carry non-semantic annotations")
                                 val block          = entry.key match
                                   case value: BinaryKey.Block => value
                                   case other                  => throw new IllegalArgumentException(s"manifest entry key must be a block key, got $other")
                                 val start          = entry.span.startInclusive.value
                                 val length         = entry.span.endInclusive.value - start + 1L
                                 if start != next.offset || length <= 0L || length != block.bits.size then
                                   throw new IllegalArgumentException(s"invalid manifest entry ${next.count} at offset $start with length $length")
                                 val blockAlgorithm =
                                   toDbAlgorithm(block.bits.algo).fold(reason => throw new IllegalArgumentException(reason), identity)

                                 statements.blocks.setString(1, storageDomainId.value)
                                 statements.blocks.setString(2, blockAlgorithm)
                                 statements.blocks.setBytes(3, block.bits.digest.bytes)
                                 statements.blocks.setLong(4, block.bits.size)
                                 statements.blocks.addBatch()

                                 bindScope(statements.entries, 1)
                                 statements.entries.setString(3, blobAlgorithm)
                                 statements.entries.setBytes(4, blob.bits.digest.bytes)
                                 statements.entries.setLong(5, blob.bits.size)
                                 statements.entries.setInt(6, next.count)
                                 statements.entries.setString(7, blockAlgorithm)
                                 statements.entries.setBytes(8, block.bits.digest.bytes)
                                 statements.entries.setLong(9, block.bits.size)
                                 statements.entries.setLong(10, start)
                                 statements.entries.setLong(11, length)
                                 statements.entries.addBatch()
                                 next = StreamState(next.count + 1, java.lang.Math.addExact(next.offset, length))
                               }
                               statements.blocks.executeBatch()
                               statements.entries.executeBatch()
                               next
                             }
                           }
      yield state
    }

  private def deleteEntries(connection: Connection, blob: BinaryKey.Blob): Task[Unit] =
    deleteScoped(connection, "graviton.tenant_blob_block", blob).unit

  private def deleteBlob(connection: Connection, blob: BinaryKey.Blob): Task[Int] =
    deleteScoped(connection, "graviton.tenant_blob", blob)

  private def deleteScoped(connection: Connection, table: String, blob: BinaryKey.Blob): Task[Int] =
    ZIO.fromEither(toDbAlgorithm(blob.bits.algo)).mapError(new IllegalArgumentException(_)).flatMap { algorithm =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          s"""DELETE FROM $table
             |WHERE tenant_id = ?::uuid AND storage_domain_id = ?
             |  AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
        )
        try
          bindScope(statement, 1)
          statement.setString(3, algorithm)
          statement.setBytes(4, blob.bits.digest.bytes)
          statement.setLong(5, blob.bits.size)
          statement.executeUpdate()
        finally statement.close()
      }
    }

  private def cursorStream(
    operation: StoreOperation,
    sql: String,
    blob: BinaryKey.Blob,
    range: Option[(Long, Long)],
  ): ZStream[Any, StoreError, ResultSet] =
    ZStream
      .acquireReleaseWith(openCursor(sql, blob, range))(closeCursor)
      .flatMap(cursor =>
        ZStream.unfoldZIO(cursor)(value =>
          ZIO.attemptBlocking(value.result.next()).map(hasNext => Option.when(hasNext)(value.result -> value))
        )
      )
      .mapError(StoreError.fromThrowable(operation, StoreBackend.PostgreSql))

  private def openCursor(sql: String, blob: BinaryKey.Blob, range: Option[(Long, Long)]): Task[Cursor] =
    ZIO.fromEither(toDbAlgorithm(blob.bits.algo)).mapError(new IllegalArgumentException(_)).flatMap { algorithm =>
      ZIO.attemptBlocking {
        val connection = dataSource.getConnection()
        try
          connection.setReadOnly(true)
          connection.setAutoCommit(false)
          setTenantContext(connection)
          val statement = connection.prepareStatement(sql)
          try
            statement.setFetchSize(256)
            bindScope(statement, 1)
            statement.setString(3, algorithm)
            statement.setBytes(4, blob.bits.digest.bytes)
            statement.setLong(5, blob.bits.size)
            range.foreach { case (start, endExclusive) =>
              statement.setLong(6, start)
              statement.setLong(7, endExclusive)
            }
            Cursor(connection, statement, statement.executeQuery())
          catch
            case error: Throwable =>
              statement.close()
              throw error
        catch
          case error: Throwable =>
            connection.close()
            throw error
      }
    }

  private def closeCursor(cursor: Cursor): UIO[Unit] =
    ZIO.attemptBlocking {
      try cursor.result.close()
      finally
        try cursor.statement.close()
        finally
          try cursor.connection.rollback()
          finally cursor.connection.close()
    }.orDie

  private def readBlockRef(result: ResultSet): Task[BlobStreamer.BlockRef] =
    for
      index     <- ZIO.attempt(result.getInt(1).toLong)
      algorithm <- ZIO.fromEither(parseDbAlgorithm(result.getString(2))).mapError(new IllegalArgumentException(_))
      digest    <- ZIO.fromEither(Digest.fromBytes(result.getBytes(3))).mapError(new IllegalArgumentException(_))
      bits      <- ZIO.fromEither(KeyBits.create(algorithm, digest, result.getLong(4))).mapError(new IllegalArgumentException(_))
      key       <- ZIO.fromEither(BinaryKey.block(bits)).mapError(new IllegalArgumentException(_))
    yield BlobStreamer.BlockRef(index, key)

  private def readRangedBlockRef(result: ResultSet): Task[BlobStreamer.RangedBlockRef] =
    for
      ref    <- readBlockRef(result)
      offset <- ZIO.fromEither(BlobOffset.either(result.getLong(5))).mapError(new IllegalArgumentException(_))
    yield BlobStreamer.RangedBlockRef(ref.idx, ref.key, offset)

  private def readManifestEntry(result: ResultSet): Task[ManifestEntry] =
    for
      ref       <- readBlockRef(result)
      rawOffset <- ZIO.attempt(result.getLong(5))
      rawLength <- ZIO.attempt(result.getLong(6))
      start     <- ZIO.fromEither(BlobOffset.either(rawOffset)).mapError(new IllegalArgumentException(_))
      end       <- ZIO
                     .fromEither(BlobOffset.either(java.lang.Math.addExact(rawOffset, rawLength) - 1L))
                     .mapError(new IllegalArgumentException(_))
      span      <- ZIO.fromEither(Span.make(start, end)).mapError(new IllegalArgumentException(_))
    yield ManifestEntry(ref.key, span, Map.empty)

  private def readSummary(result: ResultSet): (BinaryKey.Blob, StoredManifestSummary) =
    val algorithm = parseDbAlgorithm(result.getString(1)).fold(reason => throw new IllegalArgumentException(reason), identity)
    val digest    = Digest.fromBytes(result.getBytes(2)).fold(reason => throw new IllegalArgumentException(reason), identity)
    val size      = FileSize.either(result.getLong(3)).fold(reason => throw new IllegalArgumentException(reason), identity)
    val bits      = KeyBits.create(algorithm, digest, size.value).fold(reason => throw new IllegalArgumentException(reason), identity)
    val blob      = BinaryKey.blob(bits).fold(reason => throw new IllegalArgumentException(reason), identity)
    val count     = result.getInt(4)
    if count < 1 || count > BlobManifestRepo.MaxEntries then throw new IllegalArgumentException(s"invalid manifest block count $count")
    blob -> StoredManifestSummary(size, count, Option(result.getTimestamp(5)).fold(Instant.EPOCH)(_.toInstant))

  private def bindScope(statement: PreparedStatement, start: Int): Unit =
    statement.setString(start, tenantId.value)
    statement.setString(start + 1, storageDomainId.value)

  private def algorithm(blob: BinaryKey.Blob, operation: StoreOperation): IO[StoreError, String] =
    ZIO.fromEither(toDbAlgorithm(blob.bits.algo)).mapError(StoreError.InvalidInput(operation, _))

  private def blocking[A](operation: StoreOperation)(use: Connection => A): IO[StoreError, A] =
    ZIO
      .attemptBlocking {
        val connection = dataSource.getConnection()
        try
          connection.setAutoCommit(false)
          setTenantContext(connection)
          try
            val result = use(connection)
            connection.commit()
            result
          catch
            case error: Throwable =>
              connection.rollback()
              throw error
        finally connection.close()
      }
      .mapError(StoreError.fromThrowable(operation, StoreBackend.PostgreSql))

  private def transaction[A](operation: StoreOperation)(use: Connection => Task[A]): IO[StoreError, A] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(ZIO.attemptBlocking(dataSource.getConnection()))(connection => ZIO.attemptBlocking(connection.close()).orDie)
          .flatMap { connection =>
            ZIO.attemptBlocking {
              connection.setAutoCommit(false)
              setTenantContext(connection)
            } *>
              use(connection).tapBoth(
                _ => ZIO.attemptBlocking(connection.rollback()).ignore,
                _ => ZIO.attemptBlocking(connection.commit()),
              )
          }
      }
      .mapError(StoreError.fromThrowable(operation, StoreBackend.PostgreSql))

  private def setTenantContext(connection: Connection): Unit =
    val statement = connection.prepareStatement("SELECT set_config('app.org_id', ?, true)")
    try
      statement.setString(1, tenantId.value)
      val result = statement.executeQuery()
      try
        if !result.next() then throw new IllegalStateException("PostgreSQL tenant context was not established")
      finally result.close()
    finally statement.close()

  private def toDbAlgorithm(algorithm: HashAlgo): Either[String, String] = algorithm match
    case HashAlgo.Sha256 => Right("sha256")
    case HashAlgo.Blake3 => Right("blake3")
    case other           => Left(s"unsupported PostgreSQL hash algorithm '$other'")

  private def parseDbAlgorithm(value: String): Either[String, HashAlgo] = value match
    case "sha256" => Right(HashAlgo.Sha256)
    case "blake3" => Right(HashAlgo.Blake3)
    case other    => Left(s"unsupported PostgreSQL hash algorithm '$other'")

  private final case class Cursor(connection: Connection, statement: PreparedStatement, result: ResultSet)
  private final case class Statements(blocks: PreparedStatement, entries: PreparedStatement):
    def close(): Unit =
      try blocks.close()
      finally entries.close()
  private final case class StreamState(count: Int, offset: Long)
  private object StreamState:
    val empty: StreamState = StreamState(0, 0L)

object PgTenantBlobManifestRepo:
  def authenticated(
    dataSource: DataSource,
    tenantId: TenantId,
    storageDomainId: StorageDomainId,
    integrity: ManifestIntegrity,
  ): PgTenantBlobManifestRepo =
    new PgTenantBlobManifestRepo(dataSource, tenantId, storageDomainId, Some(integrity))
