ThisBuild / scalaVersion := "3.7.2"
ThisBuild / organization := "io.quasar"
ThisBuild / versionScheme := Some("semver-spec")

lazy val zioV        = "2.1.20"
lazy val zioPreludeV = "1.0.0-RC41"
lazy val ironV       = "3.2.0"
lazy val zioSchemaV  = "1.7.4"
lazy val zioMetricsV = "2.4.3"

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"         % zioV,
    "dev.zio" %% "zio-streams" % zioV,
    "dev.zio" %% "zio-prelude" % zioPreludeV,
    "dev.zio" %% "zio-schema"        % zioSchemaV,
    "dev.zio" %% "zio-schema-derivation" % zioSchemaV,
    "io.github.iltotore" %% "iron" % ironV,
    "io.github.rctcwyvrn" % "blake3" % "1.3",
    "dev.zio" %% "zio-test"          % zioV % Test,
    "dev.zio" %% "zio-test-sbt"      % zioV % Test,
    "dev.zio" %% "zio-test-magnolia" % zioV % Test
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

lazy val root = (project in file(".")).aggregate(core, fs, minio, tika, metrics, docs)
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
    name := "graviton-fs"
  )
  .settings(commonSettings)

lazy val minio = project
  .in(file("modules/minio"))
  .dependsOn(core)
  .settings(
    name := "graviton-minio",
    libraryDependencies += "io.minio" % "minio" % "8.5.9"
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
  .dependsOn(core, fs, minio, tika)
  .settings(
    publish / skip := true,
    mdocIn := baseDirectory.value / "src/main/mdoc",
    mdocOut := baseDirectory.value / "target/mdoc",
    mdocVariables := Map("VERSION" -> version.value)
  )
  .enablePlugins(MdocPlugin)
