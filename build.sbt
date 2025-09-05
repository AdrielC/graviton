import cats.instances.set
enablePlugins(
  ZioSbtEcosystemPlugin,
  ZioSbtCiPlugin,
)

import scala.sys.process.*
// import dbcodegen.plugin.DbCodegenPlugin.autoImport._

ThisBuild / scalaVersion  := "3.7.2"
ThisBuild / organization  := "io.quasar"
ThisBuild / versionScheme := Some("semver-spec")
ThisBuild / name          := "graviton"

lazy val zioV            = "2.1.20"
lazy val zioPreludeV     = "1.0.0-RC41"
lazy val ironV           = "3.2.0"
lazy val zioSchemaV      = "1.7.4"
lazy val zioJsonV        = "0.6.2"
lazy val zioMetricsV     = "2.4.3"
lazy val zioCacheV       = "0.2.4"
lazy val zioRocksdbV     = "0.4.4"
lazy val zioConfigV      = "4.0.4"
lazy val testContainersV = "1.19.7"
lazy val zioLoggingV     = "2.2.4"
lazy val magnumV         = "2.0.0-M2"
lazy val postgresV       = "42.7.3"

lazy val generatePgSchemas = taskKey[Seq[java.io.File]](
  "Generate DB schemas and snapshot into modules/pg/src/main/scala/graviton/db"
)

// Task to bootstrap local Postgres and stream output via sbt logger
lazy val setUpPg = taskKey[Unit]("Bootstrap local Postgres (Podman) and stream logs")
ThisBuild / setUpPg := {
  val log     = streams.value.log
  val workDir = (ThisBuild / baseDirectory).value

  val host       = (ThisBuild / pgHost).value
  val portString = (ThisBuild / pgPort).value
  val port       = scala.util.Try(portString.toInt).getOrElse(5432)

  def pgReachable(): Boolean = {
    val socket = new java.net.Socket()
    try {
      socket.connect(new java.net.InetSocketAddress(host, port), 1500)
      true
    } catch {
      case _: Throwable => false
    } finally {
      try socket.close()
      catch { case _: Throwable => () }
    }
  }

  def dockerAvailable(): Boolean =
    // check if docker is present and responsive
    Process(Seq("docker", "info"), workDir).!(ProcessLogger(_ => (), _ => ())) == 0

  if (pgReachable()) {
    log.info(s"Postgres detected at $host:$port. Skipping bootstrap.")
  } else if (dockerAvailable()) {
    log.warn(
      "Docker is available but Postgres is not reachable. Skipping Podman bootstrap. " +
        "Start your database with Docker or adjust PG_* settings if needed."
    )
  } else {
    val cmd  = Seq("./scripts/bootstrap-podman-postgres.sh", "--fix-rootless")
    log.info(s"Constrained environment detected (no Docker). Running: ${cmd.mkString(" ")} (cwd=${workDir.getAbsolutePath})")
    val exit = Process(cmd, workDir).!(ProcessLogger(out => log.info(out), err => log.error(err)))
    if (exit == 0) {
      if (pgReachable()) log.info(s"Postgres is now reachable at $host:$port.")
      else log.warn(s"Podman bootstrap completed but Postgres still not reachable at $host:$port.")
    } else {
      log.error(s"Podman Postgres bootstrap failed with exit code $exit")
    }
  }
}

// Run setUpPg automatically on sbt startup
ThisBuild / onLoad := {
  val prev = (ThisBuild / onLoad).value
  (state: State) => {
    val s1 = prev(state)
    "setUpPg" :: s1
  }
}

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio"            %% "zio"                   % zioV,
    "dev.zio"            %% "zio-streams"           % zioV,
    "dev.zio"            %% "zio-cache"             % zioCacheV,
    "dev.zio"            %% "zio-prelude"           % zioPreludeV,
    "dev.zio"            %% "zio-schema"            % zioSchemaV,
    "dev.zio"            %% "zio-schema-derivation" % zioSchemaV,
    "dev.zio"            %% "zio-schema-json"       % zioSchemaV,
    "dev.zio"            %% "zio-config"            % zioConfigV,
    "dev.zio"            %% "zio-config-typesafe"   % zioConfigV,
    "dev.zio"            %% "zio-config-magnolia"   % zioConfigV,
    "io.github.iltotore" %% "iron"                  % ironV,
    "io.github.iltotore" %% "iron-zio"              % ironV,
    "io.github.rctcwyvrn" % "blake3"                % "1.3",
    "dev.zio"            %% "zio-logging"           % zioLoggingV,
    "dev.zio"            %% "zio-test"              % zioV % Test,
    "dev.zio"            %% "zio-test-sbt"          % zioV % Test,
    "dev.zio"            %% "zio-test-magnolia"     % zioV % Test,
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
)

lazy val root = (project in file("."))
  .aggregate(core, fs, s3, tika, metrics, pg, docs)
  .settings(name := "graviton")

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name := "graviton-core"
  )
  .settings(commonSettings)

lazy val fs = project
  .in(file("modules/fs"))
  .dependsOn(core)
  .settings(
    name := "graviton-fs",
    libraryDependencies ++= Seq(
      "dev.zio"           %% "zio-rocksdb"    % zioRocksdbV,
      "org.testcontainers" % "testcontainers" % testContainersV % Test,
    ),
  )
  .settings(commonSettings)

lazy val s3 = project
  .in(file("modules/s3"))
  .dependsOn(core)
  .settings(
    name := "graviton-s3",
    libraryDependencies ++= Seq(
      "io.minio"           % "minio" % "8.5.9",
      "org.testcontainers" % "minio" % testContainersV % Test,
    ),
  )
  .settings(commonSettings)

lazy val metrics = project
  .in(file("modules/metrics"))
  .dependsOn(core)
  .settings(
    name                             := "graviton-metrics",
    libraryDependencies += "dev.zio" %% "zio-metrics-connectors-prometheus" % zioMetricsV,
  )
  .settings(commonSettings)

lazy val pg = project
  .in(file("modules/pg"))
  .dependsOn(core)
  .settings(
    name := "graviton-pg",
    // on-demand schema generation snapshot directory (checked into VCS)
    Compile / unmanagedSourceDirectories += (
      baseDirectory.value / "src" / "main" / "scala" / "graviton" / "db"
    ),
    // keep your existing deps:
    libraryDependencies ++= Seq(
      "com.augustnagro"   %% "magnum"     % magnumV,
      "com.augustnagro"   %% "magnumpg"   % magnumV,
      "com.augustnagro"   %% "magnumzio"  % magnumV,
      "org.postgresql"     % "postgresql" % postgresV,
      "com.zaxxer"         % "HikariCP"   % "5.1.0",
      "org.testcontainers" % "postgresql" % testContainersV % Test,
    ),
  )
  .settings(commonSettings)
  .settings(Test / fork := true)
  .settings(
    Test / javaOptions ++= Seq(
      "-DTESTCONTAINERS_RYUK_DISABLED=false",
      "-DTESTCONTAINERS_CHECKS_DISABLE=false",
      "-DTC_LOG_LEVEL=DEBUG",
      "-Dcom.zaxxer.hikari.level=DEBUG", // optional: Hikari debug
    )
  )
  .settings(
    Test / envVars ++= Map(
      "TESTCONTAINERS_RYUK_DISABLED"  -> "false",
      "TESTCONTAINERS_CHECKS_DISABLE" -> "false",
      "TC_LOG_LEVEL"                  -> "DEBUG",
    )
  )
  .settings(
    pgHost     := sys.env.get("PG_HOST").getOrElse("127.0.0.1"),
    pgPort     := sys.env.get("PG_PORT").getOrElse("5432"),
    pgDatabase := sys.env.get("PG_DATABASE").getOrElse("postgres"),
    pgUsername := sys.env.get("PG_USERNAME").getOrElse("postgres"),
    pgPassword := sys.env.get("PG_PASSWORD").getOrElse("postgres"),
  )
  .settings(
    Test / envVars ++= Map(
      "PG_HOST"     -> pgHost.value,
      "PG_PORT"     -> pgPort.value,
      "PG_DATABASE" -> pgDatabase.value,
      "PG_USERNAME" -> pgUsername.value,
      "PG_PASSWORD" -> pgPassword.value,
    )
  )

lazy val dbcodegen = project
  .in(file("dbcodegen"))
  .settings(
    name := "graviton-dbcodegen"
  )
  .settings(commonSettings)

lazy val tika = project
  .in(file("modules/tika"))
  .dependsOn(core)
  .settings(
    name                                    := "graviton-tika",
    libraryDependencies += "org.apache.tika" % "tika-core" % "2.9.1",
  )
  .settings(commonSettings)

lazy val docs = project
  .in(file("docs"))
  .dependsOn(core, fs, s3, tika)
  .settings(
    publish / skip := true,
    mdocIn         := baseDirectory.value / "src/main/mdoc",
    mdocOut        := baseDirectory.value / "target/mdoc",
    mdocVariables  := Map("VERSION" -> version.value),
    // Custom lightweight website builder to generate a Pages-ready folder
    Compile / compile := (Compile / compile).value,
    buildWebsite := {
      val log        = streams.value.log
      val docsBase   = baseDirectory.value
      val outDir     = docsBase / "target" / "site"
      val mdocOutDir = mdocOut.value

      // Run mdoc to ensure latest markdown -> html/md transformations
      mdoc.toTask("").value

      IO.delete(outDir)
      IO.createDirectory(outDir)
      // Copy mdoc output tree to site
      IO.copyDirectory(mdocOutDir, outDir)

      // Ensure Pages essentials
      IO.touch(outDir / ".nojekyll")
      val indexHtml = outDir / "index.html"
      if (!indexHtml.exists) {
        IO.write(indexHtml,
          """<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"/><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><title>Graviton Docs</title></head><body><h1>Graviton Documentation</h1><p>Open docs entry point:</p><ul><li><a href=\"./index.md\">index.md</a></li></ul></body></html>"""
        )
      }
      IO.copyFile(indexHtml, outDir / "404.html")
      log.info(s"Website built at ${outDir.getAbsolutePath}")
    },
    generateReadme := {
      // No-op placeholder to satisfy CI step; customize as needed
      streams.value.log.info("Skipping generateReadme (no-op)")
    }
  )
  .enablePlugins(MdocPlugin)

// Convenience alias to generate and snapshot PG schemas on demand via in-repo tool
addCommandAlias(
  "genPg",
  "dbcodegen/runMain dbcodegen.DbMain -Ddbcodegen.template=modules/pg/codegen/magnum.ssp -Ddbcodegen.out=modules/pg/src/main/scala/graviton/db",
)

lazy val pgHost = settingKey[String]("PG_HOST")
ThisBuild / pgHost := sys.env.get("PG_HOST").getOrElse("127.0.0.1")

lazy val pgPort = settingKey[String]("PG_PORT")
ThisBuild / pgPort := sys.env.get("PG_PORT").getOrElse("5432")

lazy val pgPassword = settingKey[String]("PG_PASSWORD")
ThisBuild / pgPassword := sys.env.get("PG_PASSWORD").getOrElse("postgres")

lazy val pgDatabase = settingKey[String]("PG_DATABASE")
ThisBuild / pgDatabase := sys.env.get("PG_DATABASE").getOrElse("postgres")

lazy val pgUsername = settingKey[String]("PG_USERNAME")
ThisBuild / pgUsername := sys.env.get("PG_USERNAME").getOrElse("postgres")
