import sbt._
import Keys._

object BuildHelper {
  val baseSettings: Seq[Setting[_]] = Seq(
    Test / parallelExecution := false,
    Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Temporarily disable fatal warnings for new scan algebra development
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(_ == "-Xfatal-warnings")
  )
}
