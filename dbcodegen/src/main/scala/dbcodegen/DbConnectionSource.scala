package dbcodegen

import java.sql.{Connection, DriverManager}
import java.util.function.Consumer
import us.fatehi.utility.datasource.DatabaseConnectionSource

final class DbConnectionSource(jdbcUrl: String, user: Option[String], pass: Option[String])
    extends DatabaseConnectionSource {

  private var initializer: Consumer[Connection] = (_: Connection) => ()

  override def get(): Connection = {
    val conn = (user, pass) match {
      case (Some(u), Some(p)) => DriverManager.getConnection(jdbcUrl, u, p)
      case _                  => DriverManager.getConnection(jdbcUrl)
    }
    if (initializer != null) initializer.accept(conn)
    conn
  }

  override def releaseConnection(connection: Connection): Boolean = {
    try connection.close()
    catch { case _: Throwable => () }
    true
  }

  override def setFirstConnectionInitializer(init: Consumer[Connection]): Unit = {
    initializer = init
  }

  override def close(): Unit = ()
}