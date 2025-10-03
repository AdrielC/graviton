package graviton.impl

import graviton.*
import zio.*

final class InMemoryBlockResolver private (
  state: Ref[Map[BlockKey, Set[BlockSector]]]
) extends BlockResolver:
  def record(key: BlockKey, sector: BlockSector): UIO[Unit] =
    state.update { m =>
      val existing = m.getOrElse(key, Set.empty)
      m.updated(key, existing + sector)
    }

  def resolve(key: BlockKey): UIO[Chunk[BlockSector]] =
    state.get.map { m =>
      Chunk.fromIterable(m.getOrElse(key, Set.empty))
    }

object InMemoryBlockResolver:
  def make: UIO[InMemoryBlockResolver] =
    Ref
      .make(Map.empty[BlockKey, Set[BlockSector]])
      .map(new InMemoryBlockResolver(_))
