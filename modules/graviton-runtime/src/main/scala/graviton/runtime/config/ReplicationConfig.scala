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

enum ReplicaStorageMode(val configValue: String):
  case Replicated extends ReplicaStorageMode("replicated")
  case Erasure21  extends ReplicaStorageMode("erasure-2-1")

object ReplicaStorageMode:
  def parse(raw: String): Either[String, ReplicaStorageMode] =
    values
      .find(_.configValue == raw.trim.toLowerCase)
      .toRight(
        s"replication mode must be one of ${values.map(_.configValue).mkString(", ")}, received '$raw'"
      )

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
  mode: ReplicaStorageMode = ReplicaStorageMode.Replicated,
  localFailureDomain: Option[ReplicaFailureDomain] = None,
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
      _ <- Either.cond(
             !enabled || mode != ReplicaStorageMode.Erasure21 || targets.length == 3,
             (),
             "erasure-2-1 requires exactly three targets",
           )
      _ <- Either.cond(
             !enabled || mode != ReplicaStorageMode.Erasure21 || targets.map(_.failureDomain.value).distinct.length == 3,
             (),
             "erasure-2-1 requires three distinct failure domains",
           )
      _ <- Either.cond(
             !enabled || mode != ReplicaStorageMode.Erasure21 || (desired == 3 && quorum == 2),
             (),
             "erasure-2-1 requires desired-replicas=3 and write-quorum=2",
           )
    yield this

object ReplicationConfig:
  val Default: ReplicationConfig = ReplicationConfig()

  val config: Config[ReplicationConfig] =
    (Config.string("targets").withDefault("") ++
      Config.int("desired-replicas").optional ++
      Config.int("write-quorum").optional ++
      Config.duration("repair-interval").withDefault(Default.repairInterval) ++
      Config.int("repair-batch-size").withDefault(Default.repairBatchSize.value) ++
      Config.string("mode").withDefault(Default.mode.configValue) ++
      Config.string("local-failure-domain").optional)
      .mapOrFail { case (rawTargets, desired, quorum, interval, batch, rawMode, rawLocalDomain) =>
        (for
          targets      <- ReplicaTargetConfig.parseList(rawTargets)
          refinedBatch <- ReplicaRepairBatchSize.either(batch)
          mode         <- ReplicaStorageMode.parse(rawMode)
          localDomain  <- rawLocalDomain match
                            case None        => Right(None)
                            case Some(value) => ReplicaFailureDomain.either(value).map(Some(_))
          config        = ReplicationConfig(targets, desired, quorum, interval, refinedBatch, mode, localDomain)
          validated    <- config.validate
        yield validated).left.map(message => Config.Error.InvalidData(Chunk.empty, message))
      }
      .nested("replication")
