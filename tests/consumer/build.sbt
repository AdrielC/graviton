ThisBuild / scalaVersion := "3.8.4"

lazy val gravitonVersion = sys.props.getOrElse(
  "graviton.version",
  sys.error("Run through scripts/verify-external-consumer.sh so graviton.version is supplied"),
)

lazy val gravitonRepository = sys.props.getOrElse(
  "graviton.repository",
  sys.error("Run through scripts/verify-external-consumer.sh so graviton.repository is supplied"),
)

lazy val gravitonResolver =
  if (gravitonRepository.startsWith("https://") || gravitonRepository.startsWith("http://"))
    "graviton-consumer-proof" at gravitonRepository
  else "graviton-consumer-proof" at file(gravitonRepository).toURI.toString

lazy val verifyZioBlocksResolution = taskKey[Unit](
  "Require the external consumer's resolved ZIO Blocks graph to use the audited release",
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "graviton-external-consumer-proof",
    Compile / run / fork := true,
    externalResolvers := Seq(gravitonResolver, Resolver.mavenCentral),
    verifyZioBlocksResolution := {
      val expected = "0.0.51"
      val resolved = (Compile / update).value.allModules
        .filter(module => module.organization == "dev.zio" && module.name.startsWith("zio-blocks-"))
        .map(module => module.name -> module.revision)
        .distinct
        .sortBy { case (name, revision) => (name, revision) }

      if (resolved.isEmpty)
        sys.error("The external consumer did not resolve any ZIO Blocks modules")

      val unexpected = resolved.filterNot(_._2 == expected)
      if (unexpected.nonEmpty) {
        val rendered = unexpected.map { case (name, revision) => s"$name:$revision" }.mkString(", ")
        sys.error(s"Expected every resolved ZIO Blocks module at $expected, found: $rendered")
      }

      val rendered = resolved.map { case (name, revision) => s"$name:$revision" }.mkString(", ")
      streams.value.log.info(s"Verified resolved ZIO Blocks graph: $rendered")
    },
    libraryDependencies ++= Seq(
      "io.github.adrielc" %% "graviton-core" % gravitonVersion,
      "io.github.adrielc" %% "graviton-streams" % gravitonVersion,
      "io.github.adrielc" %% "graviton-shared" % gravitonVersion,
      "io.github.adrielc" %% "graviton-runtime" % gravitonVersion,
      "io.github.adrielc" %% "graviton-backend-laws" % gravitonVersion,
      "io.github.adrielc" %% "graviton-pdf" % gravitonVersion,
      "io.github.adrielc" %% "graviton-shardcake" % gravitonVersion,
      "io.github.adrielc" %% "graviton-proto" % gravitonVersion,
      "io.github.adrielc" %% "graviton-security" % gravitonVersion,
      "io.github.adrielc" %% "graviton-grpc" % gravitonVersion,
      "io.github.adrielc" %% "graviton-http" % gravitonVersion,
      "io.github.adrielc" %% "graviton-s3" % gravitonVersion,
      "io.github.adrielc" %% "graviton-pg" % gravitonVersion,
      "io.github.adrielc" %% "graviton-rocks" % gravitonVersion,
    ),
  )
