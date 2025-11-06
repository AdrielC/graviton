package graviton.pg

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.time.Duration
import org.testcontainers.containers.wait.strategy.WaitStrategy

package object containers:

  final case class TestContainer(dockerImageName: DockerImageName) extends PostgreSQLContainer[TestContainer](dockerImageName):
    override def withUsername(username: String): TestContainer         = super.withUsername(username)
    override def withPassword(password: String): TestContainer         = super.withPassword(password)
    override def withDatabaseName(databaseName: String): TestContainer = super.withDatabaseName(databaseName)

    def withInitScript(initScript: Path): TestContainer                      = super.withInitScript(initScript.toString)
    override def withStartupAttempts(startupAttempts: Int): TestContainer    = super.withStartupAttempts(startupAttempts)
    override def withStartupTimeout(startupTimeout: Duration): TestContainer = super.withStartupTimeout(startupTimeout)
    override def withReuse(reuse: Boolean): TestContainer                    = super.withReuse(reuse)
    override def waitingFor(waitStrategy: WaitStrategy): TestContainer       = super.waitingFor(waitStrategy)

  end TestContainer

  object TestContainer:
    given PgTestLayers[TestContainer] = TestContainer(_)
  end TestContainer

end containers

export containers.TestContainer
