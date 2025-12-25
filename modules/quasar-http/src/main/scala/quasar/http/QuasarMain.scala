package quasar.http

import quasar.core.Jdbc
import zio.*
import zio.http.*
import zio.json.*

import java.net.{HttpURLConnection, InetSocketAddress, Socket, URL}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object QuasarMain extends ZIOAppDefault:

  private final case class DependencyStatus(ok: Boolean, detail: Option[String] = None) derives JsonCodec
  private final case class ReadyResponse(
    status: String,
    uptimeMs: Long,
    db: DependencyStatus,
    redis: DependencyStatus,
    minio: DependencyStatus,
    graviton: DependencyStatus,
  ) derives JsonCodec

  private final case class Env(
    httpPort: Int,
    jdbc: Jdbc.Config,
    redisHost: String,
    redisPort: Int,
    minioUrl: String,
    gravitonBaseUrl: String,
  )

  private def envOr(name: String, default: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  private def envIntOr(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.trim.toIntOption).getOrElse(default)

  private def loadEnv: Env =
    Env(
      httpPort = envIntOr("QUASAR_HTTP_PORT", 8080),
      jdbc = Jdbc.Config(
        url = envOr("QUASAR_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/quasar"),
        username = envOr("QUASAR_DB_USERNAME", "quasar_app"),
        password = envOr("QUASAR_DB_PASSWORD", "quasar_app"),
      ),
      redisHost = envOr("QUASAR_REDIS_HOST", "127.0.0.1"),
      redisPort = envIntOr("QUASAR_REDIS_PORT", 6379),
      minioUrl = envOr("QUASAR_MINIO_URL", "http://127.0.0.1:9000"),
      gravitonBaseUrl = envOr("QUASAR_GRAVITON_BASE_URL", "http://127.0.0.1:8081"),
    )

  private def pingRedis(host: String, port: Int): IO[Throwable, Unit] =
    ZIO
      .attemptBlocking {
        val socket = new Socket()
        socket.connect(InetSocketAddress(host, port), 1000)
        try
          val out = socket.getOutputStream
          val in  = socket.getInputStream
          out.write("PING\r\n".getBytes(StandardCharsets.UTF_8))
          out.flush()
          val buf = new Array[Byte](16)
          val n   = in.read(buf)
          val s   = if n <= 0 then "" else String(buf, 0, n, StandardCharsets.UTF_8)
          s.contains("PONG")
        finally socket.close()
      }
      .flatMap { ok =>
        if ok then ZIO.unit
        else ZIO.fail(new RuntimeException("redis ping failed"))
      }

  private def httpReady(url: String): IO[Throwable, Unit] =
    ZIO
      .attemptBlocking {
        val conn = URL(url).openConnection().asInstanceOf[HttpURLConnection]
        conn.setConnectTimeout(1000)
        conn.setReadTimeout(1000)
        conn.setRequestMethod("GET")
        conn.connect()
        conn.getResponseCode
      }
      .flatMap { code =>
        if code >= 200 && code < 500 then ZIO.unit
        else ZIO.fail(new RuntimeException(s"http=$code for $url"))
      }

  override def run: ZIO[Any, Any, Any] =
    for
      env     <- ZIO.succeed(loadEnv)
      started <- Clock.currentTime(TimeUnit.MILLISECONDS)
      api      = QuasarHttpApi()
      ready    = Routes(
                   Method.GET / "v1" / "ready" -> Handler.fromZIO {
                     for
                       now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                       db0 <- Jdbc.ping(env.jdbc).either
                       r0  <- pingRedis(env.redisHost, env.redisPort).either
                       m0  <- httpReady(s"${env.minioUrl.stripSuffix("/")}/minio/health/ready").either
                       g0  <- httpReady(s"${env.gravitonBaseUrl.stripSuffix("/")}/api/v1/health").either
                       db   = db0.fold(th => DependencyStatus(ok = false, detail = Some(th.getMessage)), _ => DependencyStatus(ok = true))
                       rds  = r0.fold(th => DependencyStatus(ok = false, detail = Some(th.getMessage)), _ => DependencyStatus(ok = true))
                       min  = m0.fold(th => DependencyStatus(ok = false, detail = Some(th.getMessage)), _ => DependencyStatus(ok = true))
                       gra  = g0.fold(th => DependencyStatus(ok = false, detail = Some(th.getMessage)), _ => DependencyStatus(ok = true))
                       ok   = db.ok && rds.ok && min.ok && gra.ok
                       body = ReadyResponse(
                                status = if ok then "ready" else "degraded",
                                uptimeMs = (now - started).max(0L),
                                db = db,
                                redis = rds,
                                minio = min,
                                graviton = gra,
                              ).toJson
                     yield Response(
                       status = if ok then Status.Ok else Status.ServiceUnavailable,
                       headers = Headers(Header.ContentType(MediaType.application.json)),
                       body = Body.fromString(body),
                     )
                   }
                 )
      routes   = api.routes ++ ready
      _       <- ZIO.logInfo(s"Starting Quasar API on :${env.httpPort}")
      _       <- Server.serve(routes).provide(Server.defaultWithPort(env.httpPort))
    yield ()
