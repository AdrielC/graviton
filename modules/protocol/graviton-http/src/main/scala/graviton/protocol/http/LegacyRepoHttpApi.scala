package graviton.protocol.http

import graviton.runtime.legacy.*
import graviton.runtime.metrics.MetricsRegistry
import zio.*
import zio.http.*

final case class LegacyRepoHttpApi(
  repos: LegacyRepos,
  catalog: LegacyCatalog,
  fs: LegacyFs,
  metrics: Option[MetricsRegistry] = None,
):

  private def recordCounter(name: String, tags: Map[String, String]): UIO[Unit] =
    metrics match
      case None        => ZIO.unit
      case Some(reggy) => reggy.counter(name, tags)

  private def recordGauge(name: String, value: Double, tags: Map[String, String]): UIO[Unit] =
    metrics match
      case None        => ZIO.unit
      case Some(reggy) => reggy.gauge(name, value, tags)

  private def respondForError(err: Throwable): UIO[Response] =
    err match
      case _: LegacyRepoError.CatalogError.RepoNotConfigured  =>
        ZIO.succeed(Response.status(Status.NotFound))
      case _: LegacyRepoError.CatalogError.MetadataNotFound   =>
        ZIO.succeed(Response.status(Status.NotFound))
      case _: LegacyRepoError.FsError.BinaryNotFound          =>
        ZIO.succeed(Response.status(Status.NotFound))
      case _: LegacyRepoError.FsError.InvalidBinaryHash       =>
        ZIO.succeed(Response.status(Status.UnprocessableEntity))
      case _: LegacyRepoError.CatalogError.MetadataInvalid    =>
        ZIO.succeed(Response.status(Status.UnprocessableEntity))
      case _: LegacyRepoError.FsError.BinaryUnreadable        =>
        ZIO.succeed(Response.status(Status.Conflict))
      case _: LegacyRepoError.CatalogError.MetadataUnreadable =>
        ZIO.succeed(Response.status(Status.Conflict))
      case _                                                  =>
        ZIO.succeed(Response.status(Status.InternalServerError))

  private val getLegacyHandler: Handler[Any, Nothing, (String, String, Request), Response] =
    Handler.fromFunctionZIO[(String, String, Request)] { case (repo, docId, _) =>
      val id   = LegacyId(repo = repo, docId = docId)
      val tags = Map("repo" -> repo)

      val effect =
        for
          start   <- Clock.nanoTime
          _       <- recordCounter("graviton_legacy_read_total", tags)
          desc    <- catalog.resolve(id)
          elapsed <- Clock.nanoTime.map(ns => (ns - start).toDouble / 1e9)
          _       <- recordGauge("graviton_legacy_read_duration_seconds", elapsed, tags)
          headers0 = Headers(Header.Custom("Content-Type", desc.mime))
          headers  =
            desc.length match
              case None    => headers0
              case Some(v) => headers0 ++ Headers(Header.Custom("Content-Length", v.toString))
        yield Response(
          status = Status.Ok,
          headers = headers,
          body = Body.fromStreamChunked(fs.open(repo, desc.binaryHash)),
        )

      effect.catchAll { err =>
        recordCounter("graviton_legacy_failures_total", tags ++ Map("reason" -> err.getClass.getSimpleName)) *>
          respondForError(err)
      }
    }

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "legacy" / string("repo") / string("docId") -> getLegacyHandler
    )
