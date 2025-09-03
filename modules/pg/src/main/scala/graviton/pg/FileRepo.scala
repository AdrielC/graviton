package graviton.pg

import zio.*
import zio.Chunk
import java.util.UUID

trait FileRepo:
  def upsertFile(key: FileKey): Task[UUID]
  def putFileBlocks(
      fileId: UUID,
      blocks: Chunk[(BlockKey, NonNegLong, PosLong)]
  ): Task[Unit]
  def findFileId(key: FileKey): Task[Option[UUID]]
