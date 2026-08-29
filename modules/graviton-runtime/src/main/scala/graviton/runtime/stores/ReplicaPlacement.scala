package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import zio.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** Stable, storage-neutral selection of failure-domain-aware block replicas. */
trait ReplicaPlacement:
  def select(
    key: BinaryKey.Block,
    candidates: Chunk[ReplicatedBlockStore.Replica],
    desiredReplicas: Int,
  ): UIO[Chunk[ReplicatedBlockStore.Replica]]

object ReplicaPlacement:
  def select(
    key: BinaryKey.Block,
    candidates: Chunk[ReplicatedBlockStore.Replica],
    desiredReplicas: Int,
  ): URIO[ReplicaPlacement, Chunk[ReplicatedBlockStore.Replica]] =
    ZIO.serviceWithZIO[ReplicaPlacement](_.select(key, candidates, desiredReplicas))

  val rendezvous: ReplicaPlacement = new ReplicaPlacement:
    override def select(
      key: BinaryKey.Block,
      candidates: Chunk[ReplicatedBlockStore.Replica],
      desiredReplicas: Int,
    ): UIO[Chunk[ReplicatedBlockStore.Replica]] =
      ZIO.succeed {
        val ranked        = candidates.sortBy(replica => score(key, replica))(using Ordering.String.reverse)
        val uniqueDomains = ranked.foldLeft(Chunk.empty[ReplicatedBlockStore.Replica]) { (selected, candidate) =>
          if selected.length >= desiredReplicas || selected.exists(_.failureDomain == candidate.failureDomain) then selected
          else selected :+ candidate
        }
        val selectedNames = uniqueDomains.iterator.map(_.name).toSet
        val remainder     = ranked.filterNot(replica => selectedNames.contains(replica.name))
        (uniqueDomains ++ remainder).take(desiredReplicas)
      }

  val rendezvousLayer: ULayer[ReplicaPlacement] = ZLayer.succeed(rendezvous)

  private def score(key: BinaryKey.Block, replica: ReplicatedBlockStore.Replica): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(key.bits.render.getBytes(StandardCharsets.UTF_8))
    digest.update(0.toByte)
    digest.update(replica.failureDomain.getBytes(StandardCharsets.UTF_8))
    digest.update(0.toByte)
    digest.update(replica.name.getBytes(StandardCharsets.UTF_8))
    HexFormat.of().formatHex(digest.digest())
