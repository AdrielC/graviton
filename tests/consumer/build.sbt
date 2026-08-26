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
    Compile / run / fork := true,
    resolvers += "graviton-consumer-proof" at file(gravitonRepository).toURI.toString,
    libraryDependencies ++= Seq(
      "io.github.adrielc" %% "graviton-core" % gravitonVersion,
      "io.github.adrielc" %% "graviton-streams" % gravitonVersion,
      "io.github.adrielc" %% "graviton-shared" % gravitonVersion,
      "io.github.adrielc" %% "graviton-runtime" % gravitonVersion,
      "io.github.adrielc" %% "graviton-pdf" % gravitonVersion,
      "io.github.adrielc" %% "graviton-proto" % gravitonVersion,
      "io.github.adrielc" %% "graviton-security" % gravitonVersion,
      "io.github.adrielc" %% "graviton-grpc" % gravitonVersion,
      "io.github.adrielc" %% "graviton-http" % gravitonVersion,
      "io.github.adrielc" %% "graviton-s3" % gravitonVersion,
      "io.github.adrielc" %% "graviton-pg" % gravitonVersion,
      "io.github.adrielc" %% "graviton-rocks" % gravitonVersion,
    ),
  )
