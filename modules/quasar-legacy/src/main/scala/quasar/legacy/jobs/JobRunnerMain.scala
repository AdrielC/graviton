package quasar.legacy.jobs

import quasar.core.Jdbc
import zio.*

import java.sql.DriverManager

object JobRunnerMain extends ZIOAppDefault:

  private final case class Env(
    jdbc: Jdbc.Config,
    pollInterval: Duration,
  )

  private def envOr(name: String, default: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  private def parseDuration(raw: String, default: Duration): Duration =
    val s                                 = raw.trim
    def num(suffix: String): Option[Long] =
      s.stripSuffix(suffix).trim.toLongOption

    if s.endsWith("ms") then num("ms").map(_.millis).getOrElse(default)
    else if s.endsWith("s") then num("s").map(_.seconds).getOrElse(default)
    else if s.endsWith("m") then num("m").map(_.minutes).getOrElse(default)
    else if s.endsWith("h") then num("h").map(_.hours).getOrElse(default)
    else s.toLongOption.map(_.seconds).getOrElse(default)

  private def loadEnv: Env =
    val interval = envOr("JOB_RUNNER_POLL_INTERVAL", "5s")
    val parsed   = parseDuration(interval, 5.seconds)
    Env(
      jdbc = Jdbc.Config(
        url = envOr("QUASAR_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/quasar"),
        username = envOr("QUASAR_DB_USERNAME", "quasar_app"),
        password = envOr("QUASAR_DB_PASSWORD", "quasar_app"),
      ),
      pollInterval = parsed,
    )

  private def queuedCount(cfg: Jdbc.Config): IO[Throwable, Long] =
    ZIO.attemptBlocking {
      val conn = DriverManager.getConnection(cfg.url, cfg.username, cfg.password)
      try
        val stmt = conn.prepareStatement("select count(*) from quasar.outbox_job where status = 'queued'")
        try
          val rs = stmt.executeQuery()
          if rs.next() then rs.getLong(1) else 0L
        finally stmt.close()
      finally conn.close()
    }

  override def run: ZIO[Any, Any, Any] =
    for
      env <- ZIO.succeed(loadEnv)
      _   <- ZIO.logInfo(s"Starting Job Runner (poll=${env.pollInterval.render})")
      _   <- (for
               _     <- Jdbc.ping(env.jdbc).tapError(th => ZIO.logWarning(s"db ping failed: ${th.getMessage}"))
               count <- queuedCount(env.jdbc).tapError(th => ZIO.logWarning(s"outbox query failed: ${th.getMessage}")).orElseSucceed(-1L)
               _     <- ZIO.logInfo(s"outbox queued jobs: $count")
             yield ())
               .repeat(Schedule.spaced(env.pollInterval))
    yield ()
