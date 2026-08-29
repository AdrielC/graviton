package graviton.runtime.config

import graviton.core.RefinedTypeExt
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric
import zio.*

type ReplicaTargetName = ReplicaTargetName.T
object ReplicaTargetName extends RefinedTypeExt[String, Match["[a-z][a-z0-9-]{0,62}"]]

type ReplicaFailureDomain = ReplicaFailureDomain.T
object ReplicaFailureDomain extends RefinedTypeExt[String, MinLength[1] & MaxLength[128]]

type ReplicaTargetLocation = ReplicaTargetLocation.T
object ReplicaTargetLocation extends RefinedTypeExt[String, MinLength[1] & MaxLength[2048]]

type ReplicaRepairBatchSize = ReplicaRepairBatchSize.T
object ReplicaRepairBatchSize extends RefinedTypeExt[Int, numeric.GreaterEqual[1] & numeric.LessEqual[1000000]]

/** One operator-declared independent placement target. */
final case class ReplicaTargetConfig(
  name: ReplicaTargetName,
  failureDomain: ReplicaFailureDomain,
  location: ReplicaTargetLocation,
)

object ReplicaTargetConfig:
  def parseList(raw: String): Either[String, Chunk[ReplicaTargetConfig]] =
    if raw.trim.isEmpty then Right(Chunk.empty)
    else
      val values = raw.split(",", -1).toList
      for
        _       <- Either.cond(values.length <= 16, (), "replication targets exceed the 16-target safety limit")
        targets <- values.zipWithIndex.foldLeft[Either[String, List[ReplicaTargetConfig]]](Right(Nil)) { case (acc, (entry, index)) =>
                     for
                       current <- acc
                       pieces   = entry.split("\\|", -1).toList.map(_.trim)
                       target  <- pieces match
                                    case name :: domain :: location :: Nil =>
                                      for
                                        refinedName     <-
                                          ReplicaTargetName.either(name).left.map(message => s"target ${index + 1} name: $message")
                                        refinedDomain   <- ReplicaFailureDomain
                                                             .either(domain)
                                                             .left
                                                             .map(message => s"target ${index + 1} failure domain: $message")
                                        refinedLocation <- ReplicaTargetLocation
                                                             .either(location)
                                                             .left
                                                             .map(message => s"target ${index + 1} location: $message")
                                      yield ReplicaTargetConfig(refinedName, refinedDomain, refinedLocation)
                                    case _                                 =>
                                      Left(s"target ${index + 1} must use name|failure-domain|location")
                     yield target :: current
                   }
        ordered  = Chunk.fromIterable(targets.reverse)
        _       <- Either.cond(ordered.map(_.name.value).distinct.length == ordered.length, (), "replication target names must be unique")
      yield ordered

final case class ReplicationConfig(
  targets: Chunk[ReplicaTargetConfig] = Chunk.empty,
  desiredReplicas: Option[Int] = None,
  writeQuorum: Option[Int] = None,
  repairInterval: Duration = 5.minutes,
  repairBatchSize: ReplicaRepairBatchSize = ReplicaRepairBatchSize.applyUnsafe(10000),
):
  def enabled: Boolean              = targets.nonEmpty
  def effectiveDesiredReplicas: Int = desiredReplicas.getOrElse(targets.length)
  def effectiveWriteQuorum: Int     = writeQuorum.getOrElse(effectiveDesiredReplicas)

  def validate: Either[String, ReplicationConfig] =
    val desired = effectiveDesiredReplicas
    val quorum  = effectiveWriteQuorum
    for
      _ <-
        Either.cond(!enabled || (desired >= 1 && desired <= targets.length), (), "desired replicas must be within configured target count")
      _ <- Either.cond(!enabled || (quorum >= 1 && quorum <= desired), (), "write quorum must be within desired replicas")
      _ <- Either.cond(repairInterval > Duration.Zero, (), "repair interval must be positive")
    yield this

object ReplicationConfig:
  val Default: ReplicationConfig = ReplicationConfig()

  val config: Config[ReplicationConfig] =
    (Config.string("targets").withDefault("") ++
      Config.int("desired-replicas").optional ++
      Config.int("write-quorum").optional ++
      Config.duration("repair-interval").withDefault(Default.repairInterval) ++
      Config.int("repair-batch-size").withDefault(Default.repairBatchSize.value))
      .mapOrFail { case (rawTargets, desired, quorum, interval, batch) =>
        (for
          targets      <- ReplicaTargetConfig.parseList(rawTargets)
          refinedBatch <- ReplicaRepairBatchSize.either(batch)
          config        = ReplicationConfig(targets, desired, quorum, interval, refinedBatch)
          validated    <- config.validate
        yield validated).left.map(message => Config.Error.InvalidData(Chunk.empty, message))
      }
      .nested("replication")
