import sbt._
import Keys._

object BuildWebsiteKeys {
  lazy val buildWebsite   = taskKey[Unit]("Generate the complete documentation site including API docs and guides")
  lazy val generateReadme = taskKey[Unit]("Generate README from docs index (placeholder)")
}
