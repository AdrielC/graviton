import scala.sys.process._

ThisBuild / scalaVersion := "3.7.2"
ThisBuild / organization := "io.quasar"
ThisBuild / versionScheme := Some("semver-spec")


Test / fork := true

Test / javaOptions ++= Seq(
  "-DTESTCONTAINERS_RYUK_DISABLED=false",
  "-DTESTCONTAINERS_CHECKS_DISABLE=false",
  "-DTC_LOG_LEVEL=DEBUG",
  "-Dcom.zaxxer.hikari.level=DEBUG"         // optional: Hikari debug
)

Test / envVars ++= Map(
  "TESTCONTAINERS_RYUK_DISABLED" -> "false",
  "TESTCONTAINERS_CHECKS_DISABLE" -> "false",
  "TC_LOG_LEVEL"                  -> "DEBUG"
)

lazy val zioV        = "2.1.20"
lazy val zioPreludeV = "1.0.0-RC41"
lazy val ironV       = "3.2.0"
lazy val zioSchemaV  = "1.7.4"
lazy val zioJsonV    = "0.6.2"
lazy val zioMetricsV = "2.4.3"
lazy val zioCacheV       = "0.2.4"
lazy val zioRocksdbV     = "0.4.4"
lazy val testContainersV = "1.19.7"
lazy val zioLoggingV  = "2.2.4"
lazy val magnumV      = "2.0.0-M2"
lazy val postgresV    = "42.7.3"

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"         % zioV,
    "dev.zio" %% "zio-streams" % zioV,
    "dev.zio" %% "zio-cache"   % zioCacheV,
    "dev.zio" %% "zio-prelude" % zioPreludeV,
    "dev.zio" %% "zio-schema"        % zioSchemaV,
    "dev.zio" %% "zio-schema-derivation" % zioSchemaV,
    "dev.zio" %% "zio-json"          % zioJsonV,
    "io.github.iltotore" %% "iron" % ironV,
    "io.github.rctcwyvrn" % "blake3" % "1.3",
    "dev.zio" %% "zio-logging" % zioLoggingV,
    "dev.zio" %% "zio-test"          % zioV % Test,
    "dev.zio" %% "zio-test-sbt"      % zioV % Test,
    "dev.zio" %% "zio-test-magnolia" % zioV % Test
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

lazy val root = (project in file(".")).aggregate(core, fs, s3, tika, metrics, pg, docs)
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
      "dev.zio" %% "zio-rocksdb"   % zioRocksdbV,
      "org.testcontainers" % "testcontainers" % testContainersV % Test
    )
  )
  .settings(commonSettings)

lazy val s3 = project
  .in(file("modules/s3"))
  .dependsOn(core)
  .settings(
    name := "graviton-s3",
    libraryDependencies ++= Seq(
      "io.minio" % "minio" % "8.5.9",
      "org.testcontainers" % "minio" % testContainersV % Test
    )
  )
  .settings(commonSettings)

lazy val metrics = project
  .in(file("modules/metrics"))
  .dependsOn(core)
  .settings(
    name := "graviton-metrics",
    libraryDependencies += "dev.zio" %% "zio-metrics-connectors-prometheus" % zioMetricsV
  )
  .settings(commonSettings)

lazy val pg = project
  .in(file("modules/pg"))
  .dependsOn(core)
  .enablePlugins(dbcodegen.plugin.DbCodegenPlugin)
  .settings(
    name := "graviton-pg",
    libraryDependencies ++= Seq(
      "com.augustnagro" %% "magnum" % magnumV,
      "com.augustnagro" %% "magnumzio" % magnumV,
      "org.postgresql" % "postgresql" % postgresV,
      "com.zaxxer" % "HikariCP" % "5.1.0",
      "org.testcontainers" % "postgresql" % testContainersV % Test
    ),
    dbcodegenTemplateFiles := Seq(file("codegen/magnum.ssp")),
    dbcodegenJdbcUrl := "jdbc:postgresql://localhost:5432/postgres",
    dbcodegenUsername := Some("postgres"),
    dbcodegenPassword := Some("postgres"),
    dbcodegenSetupTask := {
      val root = (ThisBuild / baseDirectory).value
      val script = new java.io.File(root, "scripts/pg-db-setup.sh").getAbsolutePath
      _ => Process(script, root).!
    }
  )
  .settings(commonSettings)

lazy val tika = project
  .in(file("modules/tika"))
  .dependsOn(core)
  .settings(
    name := "graviton-tika",
    libraryDependencies += "org.apache.tika" % "tika-core" % "2.9.1"
  )
  .settings(commonSettings)

lazy val docs = project
  .in(file("docs"))
  .dependsOn(core, fs, s3, tika)
  .settings(
    publish / skip := true,
    mdocIn := baseDirectory.value / "src/main/mdoc",
    mdocOut := baseDirectory.value / "target/mdoc",
    mdocVariables := Map("VERSION" -> version.value)
  )
  .enablePlugins(MdocPlugin)
