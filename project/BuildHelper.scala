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
    // In CI (GRAVITON_WERROR=1), treat warnings as errors to prevent regression.
    // Locally, strip -Werror/-Xfatal-warnings so iteration stays fast.
    Compile / scalacOptions := {
      val opts = (Compile / scalacOptions).value
        .filterNot(o => o == "-Xfatal-warnings" || o == "-Werror")
      if (sys.env.getOrElse("GRAVITON_WERROR", "0") == "1") opts :+ "-Werror"
      else opts
    },
  ) ++ testSettings

  val isTestContainers: SettingKey[Boolean] = settingKey[Boolean]("Whether to run tests with TestContainers")
    
  Global / isTestContainers := sys.env.get("TESTCONTAINERS").exists(_.toBoolean)
}
