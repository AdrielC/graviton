package graviton

import zio.*

trait BlockResolver:
  def record(key: BlockKey, sector: BlockSector): UIO[Unit]
  def resolve(key: BlockKey): UIO[Chunk[BlockSector]]
