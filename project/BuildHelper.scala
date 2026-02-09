import sbt._
import Keys._

object BuildHelper {


  val testSettings: Seq[Setting[_]] = Seq(
    Test / parallelExecution := false,
    Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Fork tests in a separate JVM to prevent OOM issues
    Test / fork := true,
    

    /// enable Test frameworks

    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"          % Dependencies.V.zio % Test,
      "dev.zio" %% "zio-test-sbt"      % Dependencies.V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % Dependencies.V.zio % Test
    ),

    
    Test / testOptions += Tests.Argument(TestFrameworks.ZIOTest, "-oD"),
    // Set reasonable heap size for tests to prevent OOM with streaming/concurrent tests
    Test / javaOptions ++= Seq(
      "-Xmx2G",   // Maximum heap size
      "-Xms512M", // Initial heap size
      "-XX:+UseG1GC", // Use G1 garbage collector for better memory management
      "-XX:MaxGCPauseMillis=100", // Target max GC pause time
    )
  )

  val baseSettings: Seq[Setting[_]] = Seq(
    // Disable fatal warnings for development; Scala 3.8+ renamed -Xfatal-warnings to -Werror.
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(o => o == "-Xfatal-warnings" || o == "-Werror"),
  ) ++ testSettings

  val isTestContainers: SettingKey[Boolean] = settingKey[Boolean]("Whether to run tests with TestContainers")
    
  Global / isTestContainers := sys.env.get("TESTCONTAINERS").exists(_.toBoolean)
}
