package quasar.core

import zio.*

import java.sql.DriverManager

object Jdbc:

  final case class Config(
    url: String,
    username: String,
    password: String,
  )

  def ping(cfg: Config): IO[Throwable, Unit] =
    ZIO.attemptBlocking {
      val conn = DriverManager.getConnection(cfg.url, cfg.username, cfg.password)
      try conn.close()
      finally ()
    }.unit
