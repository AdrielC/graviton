package graviton.pg

import zio.*
import com.zaxxer.hikari.HikariDataSource
import com.augustnagro.magnum.magzio.TransactorZIO
import javax.sql.DataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.containers.wait.strategy.{WaitStrategy, Wait}
import java.time.Duration
import java.nio.file.Path
import org.testcontainers.containers.PostgreSQLContainerProvider
import zio.prelude.fx.*
import zio.prelude.*
// import java.lang.RuntimeException
// import scala.jdk.CollectionConverters.*

trait PgTestLayers[+A]:
  def make(dockerImageName: => DockerImageName): A
end PgTestLayers

type AnyContainer = PostgreSQLContainer[? <: PostgreSQLContainer[?]]

object PgTestLayers:
  val containerProvider: ZLayer[Any, Throwable, PostgreSQLContainerProvider] =
    ZLayer.succeed(new PostgreSQLContainerProvider())

  val getImageName: ZPure[Nothing, Unit, DockerImageName, PgTestConfig, Throwable, DockerImageName] =
    for
      config <- State.service[Unit, PgTestConfig]
      imgN   <- ZPure.attempt(DockerImageName.parse(config.image))
      _      <- State.set(imgN)
      _      <- State.update((_: DockerImageName).withTag(config.tag))
      _      <- State.update((d: DockerImageName) => config.registry.fold(d)(d.withRegistry))
      _      <- State.update((d: DockerImageName) => config.repository.fold(d)(d.withRepository))
      img    <- State.get[DockerImageName]
    yield img

  private def mkContainer[C <: PostgreSQLContainer[C]: {PgTestLayers, Tag}]: ZLayer[Any, Config.Error, C] =
    ZLayer.scoped:
      for
        config    <- ZIO.config[PgTestConfig]
        imgN      <- getImageName.provideService(config).toZIO.orDie
        container <- ZIO.acquireRelease(
                       ZIO.attemptBlocking {
                         val c = PgTestLayers[C]
                           .make(imgN)
                           .withUsername(config.username)
                           .withPassword(config.password)
                           .withDatabaseName(config.database)

                         val conf = config.initScript
                           .fold(c)(path => c.withInitScript(path.getFileName.toString))
                           .withStartupAttempts(config.startupAttempts)
                           .withStartupTimeout(config.startupTimeout)
                           .withReuse(true)
                           .waitingFor(Wait.forListeningPort().withStartupTimeout(config.startupTimeout))

                         conf.start()
                         conf
                       }.orDie
                     )(c => ZIO.attempt(c.stop()).orDie.logError)
      yield container

  private def mkDataSource[A <: PostgreSQLContainer[A]: Tag]: ZLayer[A, Throwable, HikariDataSource] =
    ZLayer.scoped:
      for
        container <- ZIO.service[A]
        ds        <- ZIO.acquireRelease(
                       ZIO.attempt {
                         val ds  = new HikariDataSource()
                         // IMPORTANT: trust Testcontainers' URL (includes host + mapped port + params)
                         val url = {
                           val u = container.getJdbcUrl
                           if u.contains("sslmode=") then u
                           else u + (if u.contains("?") then "&" else "?") + "sslmode=disable"
                         }
                         ds.setJdbcUrl(url)
                         ds.setUsername(container.getUsername)
                         ds.setPassword(container.getPassword)

                         ds.setMaximumPoolSize(4)
                         ds.setMinimumIdle(1)
                         ds.setAutoCommit(true)
                         ds.setKeepaliveTime(30_000) // keep connections warm on CI
                         ds.setIdleTimeout(120_000)
                         ds.setMaxLifetime(600_000)
                         ds.setValidationTimeout(5_000)
                         ds.setConnectionTimeout(10_000)
                         ds.setPoolName("pg-tests")
                         // optional, can help on noisy runners:
                         ds.setConnectionTestQuery("SELECT 1")
                         ds
                       }
                     )(ds => ZIO.attempt(ds.close()).orDie)
      yield ds

  inline private def transactorLayer: ZLayer[DataSource, Throwable, TransactorZIO] =
    TransactorZIO.layer

  def layer[A <: PostgreSQLContainer[A]: {PgTestLayers, Tag}]: ZLayer[PgTestConfig, Throwable, TransactorZIO] =
    ZLayer.make[TransactorZIO](
      mkContainer[A],
      mkDataSource[A],
      transactorLayer,
    )

  def apply[A <: PostgreSQLContainer[A]: PgTestLayers]: PgTestLayers[A] =
    summon[PgTestLayers[A]]
  end apply

end PgTestLayers

