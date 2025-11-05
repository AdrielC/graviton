package graviton.impl

import graviton.*
import zio.*
import zio.ZLayer.Derive.Default

final case class InMemoryBlockResolver (
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
  
  given Default.WithContext[Any, Nothing, Map[BlockKey, Set[BlockSector]]] = 
    Default.succeed(Map.empty[BlockKey, Set[BlockSector]])

  given Default.WithContext[Any, Nothing, InMemoryBlockResolver] =
    Default.deriveDefaultRef[Map[BlockKey, Set[BlockSector]]]
    .map(InMemoryBlockResolver(_))

  transparent inline def layer(
    state: Map[BlockKey, Set[BlockSector]]
  ): ULayer[InMemoryBlockResolver] =
    ZLayer.succeed:
      Unsafe.unsafely:
        InMemoryBlockResolver(Ref.unsafe.make(state))

  def default: ULayer[InMemoryBlockResolver] =
    Default[InMemoryBlockResolver].layer

  def make: UIO[InMemoryBlockResolver] =
    Ref
      .make(Map.empty[BlockKey, Set[BlockSector]])
      .map(new InMemoryBlockResolver(_))
