import sbt._
import Keys._

object BuildHelper {
  val baseSettings: Seq[Setting[_]] = Seq(
    Test / parallelExecution := false,
    Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Temporarily disable fatal warnings for new scan algebra development
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(_ == "-Xfatal-warnings"),
    // Fork tests in a separate JVM to prevent OOM issues
    Test / fork := true,
    // Set reasonable heap size for tests to prevent OOM with streaming/concurrent tests
    Test / javaOptions ++= Seq(
      "-Xmx2G",   // Maximum heap size
      "-Xms512M", // Initial heap size
      "-XX:+UseG1GC", // Use G1 garbage collector for better memory management
      "-XX:MaxGCPauseMillis=100", // Target max GC pause time
    )
  )
}
