ThisBuild / scalaVersion := "3.8.2"

lazy val gravitonVersion = sys.props.getOrElse(
  "graviton.version",
  sys.error("Run through scripts/verify-external-consumer.sh so graviton.version is supplied"),
)

lazy val gravitonRepository = sys.props.getOrElse(
  "graviton.repository",
  sys.error("Run through scripts/verify-external-consumer.sh so graviton.repository is supplied"),
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "graviton-external-consumer-proof",
    resolvers += "graviton-consumer-proof" at file(gravitonRepository).toURI.toString,
    libraryDependencies += "io.github.adrielc" %% "graviton-runtime" % gravitonVersion,
  )
