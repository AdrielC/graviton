package graviton.backend.pg

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.model.{InventoryCursor, InventoryNamespace, InventoryPage, InventoryPageSize}
import graviton.runtime.streaming.BlobStreamer
import graviton.runtime.stores.*
import zio.*
import zio.stream.ZStream

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.Instant
import javax.sql.DataSource

final class PgBlobManifestRepo private (
  private val ds: DataSource,
  integrity: Option[ManifestIntegrity],
) extends BlobManifestRepo:

  def this(ds: DataSource) = this(ds, None)

  override def healthCheck: IO[StoreError, Unit] =
    ZIO
      .attemptBlocking {
        val conn = ds.getConnection()
        try
          val statement = conn.prepareStatement("SELECT 1")
          try
            val result = statement.executeQuery()
            try
              if !result.next() || result.getInt(1) != 1 then throw new IllegalStateException("Postgres readiness query failed")
            finally result.close()
          finally statement.close()
        finally conn.close()
      }
      .mapError(PgStoreError.fromThrowable(StoreOperation.HealthCheck))

  override def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): IO[StoreError, Unit] =
    ZIO
      .fromEither(FileSize.either(manifest.size))
      .mapError(StoreError.InvalidInput(StoreOperation.PutManifest, _))
      .flatMap(size => putStream(blob, size, manifest.entries.length, ZStream.fromIterable(manifest.entries), ingestedAt))

  override def putStream(
    blob: BinaryKey.Blob,
    totalSize: FileSize,
    blockCount: Int,
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    val identity = ManifestIdentity(blob, totalSize, blockCount, ManifestChunkerId.applyUnsafe("legacy-unspecified"))
    putStreamInternal(
      identity,
      BlobMetadataV1.default(identity.chunker),
      entries,
      ingestedAt,
    )

  override def putAuthenticatedStream(
    identity: ManifestIdentity,
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    putStreamInternal(identity, BlobMetadataV1.default(identity.chunker), entries, ingestedAt)

  override def putVersionedStream(
    identity: ManifestIdentity,
    metadata: BlobMetadataV1,
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    putStreamInternal(identity, metadata, entries, ingestedAt)

  private def putStreamInternal(
    identity: ManifestIdentity,
    metadata: BlobMetadataV1,
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    val blob       = identity.blob
    val totalSize  = identity.totalSize
    val blockCount = identity.blockCount
    ZIO
      .fromEither(BlobManifestRepo.validateStreamArguments(blob, totalSize, blockCount))
      .mapError(StoreError.InvalidInput(StoreOperation.PutManifest, _)) *>
      ZIO
        .fail(StoreError.InvalidInput(StoreOperation.PutManifest, "blob metadata chunker does not match manifest identity"))
        .unless(metadata.chunker == identity.chunker) *>
      withTransaction { conn =>
        for
          accumulator  <- ZIO.foreach(integrity)(_.accumulator(identity, metadata))
          _            <- upsertBlobSummary(conn, blob, blockCount, ingestedAt, metadata)
          _            <- deleteBlobBlocks(conn, blob)
          authenticated = accumulator.fold(entries)(value => entries.tap(value.update))
          state        <- writeEntryStream(conn, blob, authenticated.mapError(error => error: Throwable))
          _            <- ZIO
                            .fail(
                              new IllegalArgumentException(
                                s"Manifest entry count mismatch: expected $blockCount, observed ${state.count}"
                              )
                            )
                            .unless(state.count == blockCount)
          _            <- ZIO
                            .fail(
                              new IllegalArgumentException(
                                s"Manifest size mismatch: expected ${totalSize.value}, observed ${state.offset}"
                              )
                            )
                            .unless(state.offset == totalSize.value)
          proof        <- ZIO.foreach(accumulator)(_.prove)
          _            <- writeManifestProof(conn, blob, identity.chunker, proof)
        yield ()
      }.mapError(PgStoreError.fromThrowable(StoreOperation.PutManifest))

  override def get(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifest]] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(msg => new IllegalArgumentException(msg))
      .flatMap { blobAlg =>
        ZIO
          .attemptBlocking {
            val conn = ds.getConnection()
            try
              // Check blob exists and reject materialized inspection of a large manifest.
              val blobPs        = conn.prepareStatement(
                """SELECT created_at, block_count FROM graviton.blob
                  |WHERE alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
              )
              val ingestedAtOpt =
                try
                  blobPs.setString(1, blobAlg)
                  blobPs.setBytes(2, blob.bits.digest.bytes)
                  blobPs.setLong(3, blob.bits.size)
                  val blobRs = blobPs.executeQuery()
                  try
                    if blobRs.next() then
                      val blockCount = blobRs.getInt(2)
                      if blockCount > BlobManifestRepo.MaxMaterializedEntries then
                        throw new IllegalArgumentException(
                          s"Manifest has $blockCount entries; materialized inspection is limited to ${BlobManifestRepo.MaxMaterializedEntries}. Use streamBlockRefs for reconstruction."
                        )
                      Some(Option(blobRs.getTimestamp(1)).map(_.toInstant).getOrElse(Instant.EPOCH))
                    else None
                  finally blobRs.close()
                finally blobPs.close()

              ingestedAtOpt match
                case None             => None
                case Some(ingestedAt) =>
                  // Read real spans from blob_block (block_offset + block_length)
                  val blockPs = conn.prepareStatement(
                    """SELECT
                      |  block_alg,
                      |  block_hash_bytes,
                      |  block_byte_length,
                      |  block_offset,
                      |  block_length
                      |FROM graviton.blob_block
                      |WHERE alg = ?::core.hash_alg
                      |  AND hash_bytes = ?
                      |  AND byte_length = ?
                      |ORDER BY ordinal ASC""".stripMargin
                  )
                  try
                    blockPs.setString(1, blobAlg)
                    blockPs.setBytes(2, blob.bits.digest.bytes)
                    blockPs.setLong(3, blob.bits.size)
                    val blockRs = blockPs.executeQuery()
                    try
                      import graviton.core.manifest.ManifestEntry
                      import graviton.core.ranges.Span
                      import graviton.core.types.BlobOffset

                      val entries = scala.collection.mutable.ListBuffer.empty[ManifestEntry]
                      while blockRs.next() do
                        val blockAlgStr = blockRs.getString(1)
                        val blockHash   = blockRs.getBytes(2)
                        val blockLen    = blockRs.getLong(3)
                        val offset      = blockRs.getLong(4)
                        val length      = blockRs.getLong(5)

                        val blockAlg = PgStoreError.corruptOption("block hash algorithm", parseDbAlg(blockAlgStr))
                        val digest   = PgStoreError.corruptValue("block digest", Digest.fromBytes(blockHash))
                        val bits     = PgStoreError.corruptValue("block key bits", KeyBits.create(blockAlg, digest, blockLen))
                        val key      = PgStoreError.corruptValue("block key", BinaryKey.block(bits))

                        val start = BlobOffset.unsafe(offset)
                        val end   = BlobOffset.unsafe(offset + length - 1L)
                        val span  = Span.unsafe(start, end)
                        entries += ManifestEntry(key, span, Map.empty)

                      Some((ingestedAt, entries.toList))
                    finally blockRs.close()
                  finally blockPs.close()
            finally conn.close()
          }
          .flatMap {
            case None                                  => ZIO.succeed(None)
            case Some((_, entries)) if entries.isEmpty => ZIO.succeed(None)
            case Some((ingestedAt, entries))           =>
              ZIO
                .fromEither(Manifest.fromEntries(entries))
                .mapBoth(
                  msg => new IllegalArgumentException(msg),
                  m => Some(StoredManifest(m, ingestedAt)),
                )
          }
      }
      .mapError(PgStoreError.fromThrowable(StoreOperation.GetManifest))

  override def getSummary(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifestSummary]] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(message => new IllegalArgumentException(message))
      .flatMap { blobAlg =>
        ZIO.attemptBlocking {
          val conn = ds.getConnection()
          try
            val ps = conn.prepareStatement(
              """SELECT byte_length, block_count, created_at
                |FROM graviton.blob
                |WHERE alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
            )
            try
              ps.setString(1, blobAlg)
              ps.setBytes(2, blob.bits.digest.bytes)
              ps.setLong(3, blob.bits.size)
              val rs = ps.executeQuery()
              try
                if !rs.next() then None
                else
                  val size = PgStoreError.corruptValue("manifest byte length", FileSize.either(rs.getLong(1)))
                  Some(
                    StoredManifestSummary(
                      totalSize = size,
                      blockCount = rs.getInt(2),
                      ingestedAt = Option(rs.getTimestamp(3)).map(_.toInstant).getOrElse(Instant.EPOCH),
                    )
                  )
              finally rs.close()
            finally ps.close()
          finally conn.close()
        }
      }
      .mapError(PgStoreError.fromThrowable(StoreOperation.GetManifest))

  override def getMetadata(blob: BinaryKey.Blob): IO[StoreError, Option[BlobMetadataV1]] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(StoreError.CorruptData(StoreOperation.GetManifest, _))
      .flatMap { algorithm =>
        ZIO.attemptBlocking {
          val connection = ds.getConnection()
          try
            val statement = connection.prepareStatement(
              """SELECT metadata::text FROM graviton.blob
                |WHERE alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
            )
            try
              statement.setString(1, algorithm)
              statement.setBytes(2, blob.bits.digest.bytes)
              statement.setLong(3, blob.bits.size)
              val rows = statement.executeQuery()
              try
                if !rows.next() then None
                else
                  Some(
                    PgStoreError.corruptValue(
                      "blob metadata",
                      BlobMetadataV1.decode(Chunk.fromArray(rows.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    )
                  )
              finally rows.close()
            finally statement.close()
          finally connection.close()
        }
      }
      .mapError(PgStoreError.fromThrowable(StoreOperation.GetManifest))

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
      rows   <- queryInventoryPage(anchor, limit.value + 1)
      items   = rows.take(limit.value)
      next   <- ZIO.foreach(items.lastOption.filter(_ => rows.length > limit.value)) { case (blob, _) =>
                  ZIO.fromEither(InventoryCursor.encode(InventoryNamespace.PostgreSql, blob.bits.render))
                }
    yield InventoryPage(items, next))
      .mapError {
        case message: String                 => StoreError.InvalidInput(StoreOperation.Inventory, message)
        case error: StoreError               => error
        case error: IllegalArgumentException => StoreError.InvalidInput(StoreOperation.Inventory, error.getMessage)
        case error: Throwable                => PgStoreError.fromThrowable(StoreOperation.Inventory)(error)
      }

  override def delete(blob: BinaryKey.Blob): IO[StoreError, Boolean] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(message => new IllegalArgumentException(message))
      .flatMap { blobAlg =>
        withTransaction { conn =>
          deleteRows(conn, "graviton.blob_manifest_page", blobAlg, blob) *>
            deleteRows(conn, "graviton.blob_block", blobAlg, blob) *>
            deleteRows(conn, "graviton.blob", blobAlg, blob).map(_ > 0)
        }
      }
      .mapError(PgStoreError.fromThrowable(StoreOperation.DeleteManifest))

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, BlobStreamer.BlockRef] =
    integrity match
      case None          => rawBlockRefs(blob)
      case Some(service) => ZStream.unwrap(verifyManifest(blob, service).as(rawBlockRefs(blob)))

  private def rawBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, BlobStreamer.BlockRef] =
    val sql =
      """
        |SELECT
        |  ordinal,
        |  block_alg,
        |  block_hash_bytes,
        |  block_byte_length
        |FROM graviton.blob_block
        |WHERE alg = ?::core.hash_alg
        |  AND hash_bytes = ?
        |  AND byte_length = ?
        |ORDER BY ordinal ASC
        |""".stripMargin

    ZStream
      .acquireReleaseWith(openCursor(sql, blob))(closeCursor)
      .flatMap { cursor =>
        ZStream.unfoldZIO(cursor) { c =>
          ZIO.attemptBlocking(c.rs.next()).flatMap { hasNext =>
            if !hasNext then ZIO.succeed(None)
            else readBlockRef(c.rs).map(ref => Some((ref, c)))
          }
        }
      }
      .mapError(PgStoreError.fromThrowable(StoreOperation.GetManifest))

  override def streamBlockRefsRange(
    blob: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, BlobStreamer.RangedBlockRef] =
    val endExclusive = java.lang.Math.addExact(start.value, length.value)
    val sql          =
      """
        |SELECT
        |  ordinal,
        |  block_alg,
        |  block_hash_bytes,
        |  block_byte_length,
        |  block_offset
        |FROM graviton.blob_block
        |WHERE alg = ?::core.hash_alg
        |  AND hash_bytes = ?
        |  AND byte_length = ?
        |  AND span && int8range(?, ?, '[)')
        |ORDER BY ordinal ASC
        |""".stripMargin

    val raw = ZStream
      .acquireReleaseWith(openRangeCursor(sql, blob, start, endExclusive))(closeCursor)
      .flatMap { cursor =>
        ZStream.unfoldZIO(cursor) { c =>
          ZIO.attemptBlocking(c.rs.next()).flatMap { hasNext =>
            if !hasNext then ZIO.succeed(None)
            else readRangedBlockRef(c.rs).map(ref => Some((ref, c)))
          }
        }
      }
      .mapError(PgStoreError.fromThrowable(StoreOperation.GetRange))
    integrity match
      case None          => raw
      case Some(service) => ZStream.unwrap(verifyManifest(blob, service).as(raw))

  private def verifyManifest(blob: BinaryKey.Blob, service: ManifestIntegrity): IO[StoreError, Unit] =
    for
      stored                     <- readStoredAuthentication(blob)
      authentication             <- ZIO
                                      .fromOption(stored)
                                      .orElseFail(StoreError.CorruptData(StoreOperation.GetManifest, "manifest authentication proof is missing"))
      (identity, metadata, proof) = authentication
      accumulator                <- service.verificationAccumulator(identity, metadata)
      _                          <- rawManifestEntries(blob).runForeach(accumulator.update)
      _                          <- accumulator.verify(proof)
    yield ()

  private def rawManifestEntries(blob: BinaryKey.Blob): ZStream[Any, StoreError, ManifestEntry] =
    val sql =
      """SELECT ordinal, block_alg, block_hash_bytes, block_byte_length, block_offset, block_length
        |FROM graviton.blob_block
        |WHERE alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?
        |ORDER BY ordinal ASC""".stripMargin

    ZStream
      .acquireReleaseWith(openCursor(sql, blob))(closeCursor)
      .flatMap { cursor =>
        ZStream.unfoldZIO(cursor) { current =>
          ZIO.attemptBlocking(current.rs.next()).flatMap { hasNext =>
            if !hasNext then ZIO.none
            else readManifestEntry(current.rs).map(entry => Some(entry -> current))
          }
        }
      }
      .mapError(PgStoreError.fromThrowable(StoreOperation.GetManifest))

  private def readStoredAuthentication(
    blob: BinaryKey.Blob
  ): IO[StoreError, Option[(ManifestIdentity, BlobMetadataV1, ManifestProof)]] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(StoreError.CorruptData(StoreOperation.GetManifest, _))
      .flatMap { algorithm =>
        ZIO.attemptBlocking {
          val connection = ds.getConnection()
          try
            val statement = connection.prepareStatement(
              """SELECT byte_length, block_count, manifest_proof_version, manifest_chunker,
                |       manifest_key_id, manifest_digest, manifest_signature, metadata::text
                |FROM graviton.blob
                |WHERE alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
            )
            try
              statement.setString(1, algorithm)
              statement.setBytes(2, blob.bits.digest.bytes)
              statement.setLong(3, blob.bits.size)
              val rows = statement.executeQuery()
              try
                if !rows.next() then None
                else if rows.getObject(3) == null then None
                else
                  val size     = PgStoreError.corruptValue("manifest byte length", FileSize.either(rows.getLong(1)))
                  val chunker  = PgStoreError.corruptValue("manifest chunker", ManifestChunkerId.either(rows.getString(4)))
                  val keyId    = PgStoreError.corruptValue("manifest key id", ManifestKeyId.either(rows.getString(5)))
                  val proof    = PgStoreError.corruptValue(
                    "manifest proof",
                    ManifestProof.make(rows.getInt(3), keyId, Chunk.fromArray(rows.getBytes(6)), Chunk.fromArray(rows.getBytes(7))),
                  )
                  val metadata = PgStoreError.corruptValue(
                    "blob metadata",
                    BlobMetadataV1.decode(Chunk.fromArray(rows.getString(8).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                  )
                  Some((ManifestIdentity(blob, size, rows.getInt(2), chunker), metadata, proof))
              finally rows.close()
            finally statement.close()
          finally connection.close()
        }
      }
      .mapError(PgStoreError.fromThrowable(StoreOperation.GetManifest))

  private def writeManifestProof(
    connection: Connection,
    blob: BinaryKey.Blob,
    chunker: ManifestChunkerId,
    proof: Option[ManifestProof],
  ): Task[Unit] =
    ZIO.fromEither(toDbAlg(blob.bits.algo)).mapError(new IllegalArgumentException(_)).flatMap { algorithm =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """UPDATE graviton.blob SET
            |  manifest_proof_version = ?, manifest_chunker = ?, manifest_key_id = ?,
            |  manifest_digest = ?, manifest_signature = ?
            |WHERE alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
        )
        try
          proof match
            case None        =>
              (1 to 5).foreach(statement.setNull(_, java.sql.Types.NULL))
            case Some(value) =>
              statement.setInt(1, value.version)
              statement.setString(2, chunker.value)
              statement.setString(3, value.keyId.value)
              statement.setBytes(4, value.canonicalDigest.toArray)
              statement.setBytes(5, value.signature.toArray)
          statement.setString(6, algorithm)
          statement.setBytes(7, blob.bits.digest.bytes)
          statement.setLong(8, blob.bits.size)
          if statement.executeUpdate() != 1 then throw new IllegalStateException("manifest proof row disappeared")
        finally statement.close()
      }
    }

  private def queryInventoryPage(
    anchor: Option[BinaryKey.Blob],
    count: Int,
  ): Task[Chunk[(BinaryKey.Blob, StoredManifestSummary)]] =
    ZIO.attemptBlocking {
      val base =
        """SELECT alg, hash_bytes, byte_length, block_count, created_at
          |FROM graviton.blob
          |""".stripMargin
      val sql  = anchor match
        case None    => base + "ORDER BY alg::text, encode(hash_bytes, 'hex'), byte_length LIMIT ?"
        case Some(_) =>
          base +
            "WHERE (alg::text, encode(hash_bytes, 'hex'), byte_length) > (?, ?, ?) " +
            "ORDER BY alg::text, encode(hash_bytes, 'hex'), byte_length LIMIT ?"

      val conn = ds.getConnection()
      try
        val ps = conn.prepareStatement(sql)
        try
          anchor match
            case None       => ps.setInt(1, count)
            case Some(blob) =>
              ps.setString(1, toDbAlg(blob.bits.algo).fold(message => throw new IllegalArgumentException(message), identity))
              ps.setString(2, blob.bits.digest.hex.value)
              ps.setLong(3, blob.bits.size)
              ps.setInt(4, count)
          val rs      = ps.executeQuery()
          val builder = ChunkBuilder.make[(BinaryKey.Blob, StoredManifestSummary)]()
          try
            while rs.next() do builder += readSummaryUnsafe(rs)
            builder.result()
          finally rs.close()
        finally ps.close()
      finally conn.close()
    }

  private def withTransaction[A](f: Connection => Task[A]): Task[A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(ZIO.attemptBlocking(ds.getConnection()))(c => ZIO.attemptBlocking(c.close()).orDie)
        .flatMap { conn =>
          ZIO.attemptBlocking(conn.setAutoCommit(false)) *>
            f(conn).tapBoth(
              _ => ZIO.attemptBlocking(conn.rollback()).ignore,
              _ => ZIO.attemptBlocking(conn.commit()).unit,
            )
        }
    }

  private def deleteRows(
    conn: Connection,
    table: String,
    blobAlg: String,
    blob: BinaryKey.Blob,
  ): Task[Int] =
    ZIO.attemptBlocking {
      val statement = conn.prepareStatement(
        s"""DELETE FROM $table
           |WHERE alg = ?::core.hash_alg
           |  AND hash_bytes = ?
           |  AND byte_length = ?""".stripMargin
      )
      try
        statement.setString(1, blobAlg)
        statement.setBytes(2, blob.bits.digest.bytes)
        statement.setLong(3, blob.bits.size)
        statement.executeUpdate()
      finally statement.close()
    }

  private def openCursor(sql: String, blob: BinaryKey.Blob): Task[Cursor] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(msg => new IllegalArgumentException(msg))
      .flatMap { blobAlg =>
        ZIO.attemptBlocking {
          val conn = ds.getConnection()
          try
            conn.setReadOnly(true)
            conn.setAutoCommit(false)
            val ps = conn.prepareStatement(sql)
            try
              ps.setFetchSize(256)
              ps.setString(1, blobAlg)
              ps.setBytes(2, blob.bits.digest.bytes)
              ps.setLong(3, blob.bits.size)
              val rs = ps.executeQuery()
              Cursor(conn, ps, rs)
            catch
              case error: Throwable =>
                ps.close()
                throw error
          catch
            case error: Throwable =>
              conn.close()
              throw error
        }
      }

  private def openRangeCursor(
    sql: String,
    blob: BinaryKey.Blob,
    start: BlobOffset,
    endExclusive: Long,
  ): Task[Cursor] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(msg => new IllegalArgumentException(msg))
      .flatMap { blobAlg =>
        ZIO.attemptBlocking {
          val conn = ds.getConnection()
          try
            conn.setReadOnly(true)
            conn.setAutoCommit(false)
            val ps = conn.prepareStatement(sql)
            try
              ps.setFetchSize(256)
              ps.setString(1, blobAlg)
              ps.setBytes(2, blob.bits.digest.bytes)
              ps.setLong(3, blob.bits.size)
              ps.setLong(4, start.value)
              ps.setLong(5, endExclusive)
              val rs = ps.executeQuery()
              Cursor(conn, ps, rs)
            catch
              case error: Throwable =>
                ps.close()
                throw error
          catch
            case error: Throwable =>
              conn.close()
              throw error
        }
      }

  private def closeCursor(cursor: Cursor): UIO[Unit] =
    ZIO.attemptBlocking {
      try cursor.rs.close()
      finally
        try cursor.ps.close()
        finally
          try cursor.conn.rollback()
          finally cursor.conn.close()
    }.orDie

  private def readBlockRef(rs: ResultSet): Task[BlobStreamer.BlockRef] =
    for
      ordinal     <- ZIO.attempt(rs.getInt(1).toLong)
      blockAlgStr <- ZIO.attempt(rs.getString(2))
      blockHash   <- ZIO.attempt(rs.getBytes(3))
      blockLen    <- ZIO.attempt(rs.getLong(4))
      blockAlg    <- ZIO
                       .fromOption(parseDbAlg(blockAlgStr))
                       .mapError(_ => PgStoreError.CorruptStoredData(s"unsupported stored block hash algorithm '$blockAlgStr'"))
      digest      <- ZIO.fromEither(Digest.fromBytes(blockHash)).mapError(PgStoreError.CorruptStoredData(_))
      bits        <- ZIO.fromEither(KeyBits.create(blockAlg, digest, blockLen)).mapError(PgStoreError.CorruptStoredData(_))
      key         <- ZIO.fromEither(BinaryKey.block(bits)).mapError(PgStoreError.CorruptStoredData(_))
    yield BlobStreamer.BlockRef(ordinal, key)

  private def readRangedBlockRef(rs: ResultSet): Task[BlobStreamer.RangedBlockRef] =
    for
      ref       <- readBlockRef(rs)
      rawOffset <- ZIO.attempt(rs.getLong(5))
      offset    <- ZIO.fromEither(BlobOffset.either(rawOffset)).mapError(PgStoreError.CorruptStoredData(_))
    yield BlobStreamer.RangedBlockRef(ref.idx, ref.key, offset)

  private def readManifestEntry(rs: ResultSet): Task[ManifestEntry] =
    for
      ref       <- readBlockRef(rs)
      rawOffset <- ZIO.attempt(rs.getLong(5))
      rawLength <- ZIO.attempt(rs.getLong(6))
      start     <- ZIO.fromEither(BlobOffset.either(rawOffset)).mapError(PgStoreError.CorruptStoredData(_))
      end       <- ZIO
                     .fromEither(BlobOffset.either(java.lang.Math.addExact(rawOffset, rawLength) - 1L))
                     .mapError(PgStoreError.CorruptStoredData(_))
      span      <- ZIO.fromEither(graviton.core.ranges.Span.make(start, end)).mapError(PgStoreError.CorruptStoredData(_))
    yield ManifestEntry(ref.key, span, Map.empty)

  private def readSummaryUnsafe(rs: ResultSet): (BinaryKey.Blob, StoredManifestSummary) =
    val algorithmText = rs.getString(1)
    val digestBytes   = rs.getBytes(2)
    val byteLength    = rs.getLong(3)
    val blockCount    = rs.getInt(4)
    val createdAt     = Option(rs.getTimestamp(5)).map(_.toInstant).getOrElse(Instant.EPOCH)
    val algorithm     = PgStoreError.corruptOption("blob hash algorithm", parseDbAlg(algorithmText))
    val digest        = PgStoreError.corruptValue("blob digest", Digest.fromBytes(digestBytes))
    val size          = PgStoreError.corruptValue("blob byte length", FileSize.either(byteLength))
    val bits          = PgStoreError.corruptValue("blob key bits", KeyBits.create(algorithm, digest, size.value))
    val blob          = PgStoreError.corruptValue("blob key", BinaryKey.blob(bits))
    if blockCount < 1 || blockCount > BlobManifestRepo.MaxEntries then
      throw PgStoreError.CorruptStoredData(s"manifest block count $blockCount is outside 1..${BlobManifestRepo.MaxEntries}")
    blob -> StoredManifestSummary(size, blockCount, createdAt)

  private def upsertBlobSummary(
    conn: Connection,
    blob: BinaryKey.Blob,
    blockCount: Int,
    ingestedAt: Instant,
    metadata: BlobMetadataV1,
  ): Task[Unit] =
    val sql =
      """
        |INSERT INTO graviton.blob (alg, hash_bytes, byte_length, block_count, created_at, chunker, attrs, metadata)
        |VALUES (?::core.hash_alg, ?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb, ?::jsonb)
        |ON CONFLICT (alg, hash_bytes, byte_length) DO UPDATE SET
        |  block_count = EXCLUDED.block_count,
        |  created_at = EXCLUDED.created_at,
        |  metadata = EXCLUDED.metadata
        |""".stripMargin

    for
      _       <- ZIO
                   .fail(new IllegalArgumentException(s"Manifest block count must be within 1..${BlobManifestRepo.MaxEntries}"))
                   .unless(blockCount >= 1 && blockCount <= BlobManifestRepo.MaxEntries)
      blobAlg <- ZIO.fromEither(toDbAlg(blob.bits.algo)).mapError(message => new IllegalArgumentException(message))
      encoded <- ZIO
                   .fromEither(BlobMetadataV1.encode(metadata))
                   .mapError(message => new IllegalArgumentException(message))
      _       <- ZIO.attemptBlocking {
                   val ps = conn.prepareStatement(sql)
                   try
                     ps.setString(1, blobAlg)
                     ps.setBytes(2, blob.bits.digest.bytes)
                     ps.setLong(3, blob.bits.size)
                     ps.setInt(4, blockCount)
                     ps.setTimestamp(5, java.sql.Timestamp.from(ingestedAt))
                     ps.setString(6, new String(encoded.toArray, java.nio.charset.StandardCharsets.UTF_8))
                     ps.executeUpdate()
                     ()
                   finally ps.close()
                 }
    yield ()

  private def deleteBlobBlocks(conn: Connection, blob: BinaryKey.Blob): Task[Unit] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(message => new IllegalArgumentException(message))
      .flatMap(blobAlg => deleteRows(conn, "graviton.blob_block", blobAlg, blob).unit)

  private def writeEntryStream(
    conn: Connection,
    blob: BinaryKey.Blob,
    entries: ZStream[Any, Throwable, ManifestEntry],
  ): Task[PgBlobManifestRepo.StreamState] =
    val blockSql =
      """
        |INSERT INTO graviton.block (alg, hash_bytes, byte_length, attrs)
        |VALUES (?::core.hash_alg, ?, ?, '{}'::jsonb)
        |ON CONFLICT (alg, hash_bytes, byte_length) DO NOTHING
        |""".stripMargin
    val entrySql =
      """
        |INSERT INTO graviton.blob_block (
        |  alg, hash_bytes, byte_length,
        |  ordinal,
        |  block_alg, block_hash_bytes, block_byte_length,
        |  block_offset, block_length
        |)
        |VALUES (?::core.hash_alg, ?, ?, ?, ?::core.hash_alg, ?, ?, ?, ?)
        |""".stripMargin

    ZIO.scoped {
      for
        blobAlg    <- ZIO.fromEither(toDbAlg(blob.bits.algo)).mapError(message => new IllegalArgumentException(message))
        statements <- ZIO.acquireRelease(
                        ZIO.attemptBlocking(
                          PgBlobManifestRepo.Statements(
                            conn.prepareStatement(blockSql),
                            conn.prepareStatement(entrySql),
                          )
                        )
                      )(current => ZIO.attemptBlocking(current.close()).orDie)
        state      <- entries
                        .rechunk(PgBlobManifestRepo.WriteBatchEntries)
                        .chunks
                        .runFoldZIO(PgBlobManifestRepo.StreamState.empty) { (current, batch) =>
                          ZIO.attemptBlocking {
                            var state = current
                            batch.foreach { entry =>
                              if state.count >= BlobManifestRepo.MaxEntries then
                                throw new IllegalArgumentException(s"Manifest exceeds ${BlobManifestRepo.MaxEntries} entries")
                              if entry.annotations.nonEmpty then
                                throw new IllegalArgumentException("CAS manifest entries must not carry non-semantic annotations")

                              val block  = entry.key match
                                case value: BinaryKey.Block => value
                                case other                  =>
                                  throw new IllegalArgumentException(s"Manifest entry key must be a block key, got $other")
                              val start  = entry.span.startInclusive.value
                              val length = entry.span.endInclusive.value - start + 1L
                              if start != state.offset then
                                throw new IllegalArgumentException(
                                  s"Manifest entry ${state.count} starts at $start, expected ${state.offset}"
                                )
                              if length <= 0L || length != block.bits.size then
                                throw new IllegalArgumentException(
                                  s"Manifest entry ${state.count} length $length does not match block size ${block.bits.size}"
                                )

                              val blockAlg = toDbAlg(block.bits.algo)
                                .fold(message => throw new IllegalArgumentException(message), identity)
                              statements.blocks.setString(1, blockAlg)
                              statements.blocks.setBytes(2, block.bits.digest.bytes)
                              statements.blocks.setLong(3, block.bits.size)
                              statements.blocks.addBatch()

                              statements.entries.setString(1, blobAlg)
                              statements.entries.setBytes(2, blob.bits.digest.bytes)
                              statements.entries.setLong(3, blob.bits.size)
                              statements.entries.setInt(4, state.count)
                              statements.entries.setString(5, blockAlg)
                              statements.entries.setBytes(6, block.bits.digest.bytes)
                              statements.entries.setLong(7, block.bits.size)
                              statements.entries.setLong(8, start)
                              statements.entries.setLong(9, length)
                              statements.entries.addBatch()

                              state = PgBlobManifestRepo.StreamState(
                                state.count + 1,
                                java.lang.Math.addExact(state.offset, length),
                              )
                            }
                            statements.blocks.executeBatch()
                            statements.entries.executeBatch()
                            state
                          }
                        }
      yield state
    }

  private def toDbAlg(algo: HashAlgo): Either[String, String] =
    algo match
      case HashAlgo.Sha256 => Right("sha256")
      case HashAlgo.Blake3 => Right("blake3")
      case other           => Left(s"Unsupported hash algorithm for v1 schema: $other")

  private def parseDbAlg(value: String): Option[HashAlgo] =
    value.trim.toLowerCase match
      case "sha256" => Some(HashAlgo.Sha256)
      case "blake3" => Some(HashAlgo.Blake3)
      case _        => None

object PgBlobManifestRepo:
  private val WriteBatchEntries = 512

  private final case class StreamState(count: Int, offset: Long)
  private object StreamState:
    val empty: StreamState = StreamState(0, 0L)

  private final case class Statements(
    blocks: PreparedStatement,
    entries: PreparedStatement,
  ):
    def close(): Unit =
      try blocks.close()
      finally entries.close()

  val layer: ZLayer[DataSource, Nothing, BlobManifestRepo] =
    ZLayer.fromFunction(new PgBlobManifestRepo(_))

  def authenticated(dataSource: DataSource, integrity: ManifestIntegrity): PgBlobManifestRepo =
    new PgBlobManifestRepo(dataSource, Some(integrity))

private final case class Cursor(conn: Connection, ps: PreparedStatement, rs: ResultSet)
