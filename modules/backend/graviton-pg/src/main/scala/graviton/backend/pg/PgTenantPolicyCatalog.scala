package graviton.backend.pg

import graviton.core.types.FileSize
import graviton.runtime.config.TenantStorageConfig
import graviton.runtime.tenant.*
import graviton.runtime.upload.TenantId
import zio.*

import java.sql.ResultSet
import javax.sql.DataSource

/** Durable, fail-closed tenant routing and admission policy. */
final class PgTenantPolicyCatalog(
  dataSource: DataSource,
  storageConfig: TenantStorageConfig,
  cellId: TenantCellId = TenantCellId.Default,
) extends TenantPolicyCatalog:

  override def resolve(tenantId: TenantId): IO[TenantRoutingError, TenantPolicy] =
    ZIO
      .attemptBlocking {
        val connection = dataSource.getConnection()
        try
          val statement = connection.prepareStatement(
            """SELECT lifecycle, deduplication_domain, max_concurrent_operations, max_object_bytes, max_retained_bytes, revision
              |FROM graviton.tenant_storage_policy
              |WHERE tenant_id = ?::uuid AND cell_id = ?""".stripMargin
          )
          try
            statement.setString(1, tenantId.value)
            statement.setString(2, cellId.value)
            val result = statement.executeQuery()
            try if result.next() then Some(readPolicy(tenantId, result)) else None
            finally result.close()
          finally statement.close()
        finally connection.close()
      }
      .mapError(TenantRoutingError.PolicyUnavailable.apply)
      .flatMap {
        case None         => ZIO.fail(TenantRoutingError.UnknownTenant(tenantId))
        case Some(result) => ZIO.fromEither(result).flatMap(validateSharing)
      }

  private def readPolicy(tenantId: TenantId, result: ResultSet): Either[TenantRoutingError, TenantPolicy] =
    for
      lifecycle  <- result.getString(1) match
                      case "active"    => Right(TenantLifecycle.Active)
                      case "suspended" => Right(TenantLifecycle.Suspended)
                      case other       => Left(TenantRoutingError.InvalidPolicy(tenantId, s"unknown lifecycle '$other'"))
      scope      <- Option(result.getString(2)) match
                      case None         => Right(DeduplicationScope.Isolated)
                      case Some(domain) =>
                        DeduplicationDomainId
                          .either(domain)
                          .left
                          .map(reason => TenantRoutingError.InvalidPolicy(tenantId, s"invalid deduplication domain: $reason"))
                          .map(DeduplicationScope.Shared.apply)
      concurrent <- TenantConcurrencyLimit
                      .either(result.getInt(3))
                      .left
                      .map(reason => TenantRoutingError.InvalidPolicy(tenantId, s"invalid concurrent operation limit: $reason"))
      maximum    <- FileSize
                      .either(result.getLong(4))
                      .left
                      .map(reason => TenantRoutingError.InvalidPolicy(tenantId, s"invalid maximum object size: $reason"))
      retained   <- TenantRetainedBytesLimit
                      .either(result.getLong(5))
                      .left
                      .map(reason => TenantRoutingError.InvalidPolicy(tenantId, s"invalid retained byte limit: $reason"))
      revision   <- TenantPolicyRevision
                      .either(result.getLong(6))
                      .left
                      .map(reason => TenantRoutingError.InvalidPolicy(tenantId, s"invalid revision: $reason"))
    yield TenantPolicy(TenantRoute(tenantId, scope), lifecycle, concurrent, maximum, retained, revision)

  private def validateSharing(policy: TenantPolicy): IO[TenantRoutingError, TenantPolicy] =
    policy.route.deduplication match
      case DeduplicationScope.Shared(domain) if !storageConfig.allowSharedDeduplication =>
        ZIO.fail(
          TenantRoutingError.InvalidPolicy(
            policy.route.tenantId,
            s"shared deduplication domain ${domain.value} is disabled by server policy",
          )
        )
      case _                                                                            => ZIO.succeed(policy)

object PgTenantPolicyCatalog:
  val layer: ZLayer[DataSource & TenantStorageConfig, Nothing, TenantPolicyCatalog] =
    ZLayer.fromFunction((dataSource: DataSource, config: TenantStorageConfig) =>
      new PgTenantPolicyCatalog(dataSource, config): TenantPolicyCatalog
    )
