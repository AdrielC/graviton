package graviton.protocol.http

import graviton.runtime.stores.BlobStore
import graviton.runtime.tenant.{TenantContext, TenantRoutingError, TenantStoreProvider}
import graviton.runtime.upload.{LocalityAwareUpload, ResumableUploadService, TenantId}
import graviton.security.CallerContext
import zio.*
import zio.http.*

/**
 * Selects a server-owned tenant store before a response body can escape the
 * request handler. Download streams therefore retain a concrete tenant store
 * without depending on a FiberRef after the handler has returned.
 */
final class TenantHttpApi(
  provider: TenantStoreProvider,
  tenantContext: TenantContext,
  fallbackStore: BlobStore,
  metrics: Option[MetricsHttpApi] = None,
  security: Option[HttpSecurityPolicy] = None,
  localizedUpload: Option[LocalityAwareUpload] = None,
  resumableUploads: Option[ResumableUploadService] = None,
):
  private val fallback = HttpApi(fallbackStore, metrics, security, localizedUpload, resumableUploads)

  private val tenantHandler: Handler[Any, Nothing, (Path, Request), Response] =
    Handler.scoped[Any] {
      Handler.fromFunctionZIO[(Path, Request)] { case (_, request) =>
        val response = for
          caller  <- CallerContext.required.mapError(_ => authenticationError)
          tenant   = TenantId.applyUnsafe(caller.orgId.toString)
          binding <- provider.resolve(tenant).mapError(routingError)
          // The tenant provider owns the only bounded store cache. Keeping a
          // second HttpApi cache here is both unnecessary and dangerous: each
          // HttpApi strongly references its BlobStore, so even a WeakHashMap
          // retains its own key through the value and grows with tenant churn.
          delegate = HttpApi(binding.store, metrics, security, localizedUpload, resumableUploads)
          result  <- tenantContext.locally(tenant)(delegate.app(request))
        yield result
        response.catchAll(ZIO.succeed(_))
      }
    }

  val routes: Routes[Any, Nothing] = Routes(
    Method.ANY / "api" / "v1" / trailing -> tenantHandler
  )

  val preflightRoutes: Routes[Any, Nothing] = fallback.preflightRoutes

  private def authenticationError: Response =
    Response.json("""{"error":"unauthenticated","message":"Authentication required"}""").copy(status = Status.Unauthorized)

  private def routingError(error: TenantRoutingError): Response = error match
    case _: TenantRoutingError.UnknownTenant | _: TenantRoutingError.SuspendedTenant   =>
      Response.json("""{"error":"tenant_unavailable","message":"Tenant storage is unavailable"}""").copy(status = Status.Forbidden)
    case _: TenantRoutingError.InvalidPolicy | _: TenantRoutingError.PolicyUnavailable =>
      Response
        .json("""{"error":"tenant_policy_unavailable","message":"Tenant storage policy is unavailable"}""")
        .copy(status = Status.ServiceUnavailable)
    case TenantRoutingError.MissingContext                                             =>
      Response
        .json("""{"error":"tenant_context_missing","message":"Tenant context is unavailable"}""")
        .copy(status = Status.InternalServerError)
