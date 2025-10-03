import sbt._
import Keys._

object BuildHelper {
  val baseSettings: Seq[Setting[_]] = Seq(
    Test / parallelExecution := false
  )
}
