import sbt._
import Keys._
import BuildHelper._
import org.scalajs.linker.interface.ModuleSplitStyle
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._
import sbtprotoc.ProtocPlugin.autoImport._
import protocbridge.{Target, gens}

  lazy val V = new {
    val scala3     = "3.7.3"
    val zio        = "2.1.9"
    val zioSchema  = "1.5.0"
    val zioPrelude = "1.0.0-RC23"
    val zioGrpc    = "0.6.2"
    val zioHttp    = "3.0.0-RC7"
    val iron       = "2.6.0"
    val awsV2      = "2.25.54"
    val rocksdbJni = "8.11.3"
    val pg         = "42.7.4"
    val laminar    = "17.1.0"
    val waypoint   = "8.0.0"
    val scalajsDom = "2.8.0"
    val scalapb    = "0.11.14"
  }

ThisBuild / scalaVersion := V.scala3
ThisBuild / organization := "io.graviton"
ThisBuild / resolvers += Resolver.mavenCentral

// Semantic versioning
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage := Some(url("https://github.com/AdrielC/graviton"))
ThisBuild / licenses := List("MIT" -> url("https://github.com/AdrielC/graviton/blob/main/LICENSE"))
ThisBuild / developers := List(
  Developer(
    "AdrielC",
    "Adriel Cafiero",
    "adriel.cafiero@gmail.com",
    url("https://github.com/AdrielC")
  )
)

// Scaladoc settings
ThisBuild / Compile / doc / scalacOptions ++= Seq(
  "-project", "Graviton",
  "-project-version", version.value,
  "-project-logo", "docs/public/logo.svg",
  "-social-links:github::https://github.com/AdrielC/graviton",
  "-source-links:github://AdrielC/graviton",
  "-revision", "main",
  "-doc-root-content", "docs/scaladoc-root.md"
)

// Task to generate and copy scaladoc to docs
lazy val generateDocs = taskKey[Unit]("Generate Scaladoc and copy to docs folder")
generateDocs := {
  val log = Keys.streams.value.log
  val targetDir = file("docs/public/scaladoc")
  
  log.info("Generating Scaladoc for core modules...")
  
  // Generate docs for key modules (use LocalProject to avoid ambiguity)
  val coreDoc = (LocalProject("core") / Compile / doc).value
  val streamsDoc = (LocalProject("streams") / Compile / doc).value
  val runtimeDoc = (LocalProject("runtime") / Compile / doc).value
  
  log.info("Copying core module docs to docs folder...")
  IO.delete(targetDir)
  IO.copyDirectory(coreDoc, targetDir)
  
  log.info(s"Scaladoc copied to $targetDir")
}

// Task to build frontend and copy to docs
lazy val buildFrontend = taskKey[Unit]("Build Scala.js frontend and copy to docs")
buildFrontend := {
  val log = Keys.streams.value.log
  log.info("Building Scala.js frontend...")
  
  // Trigger fastLinkJS
  val report = (frontend / Compile / fastLinkJS).value
  val sourceDir = (frontend / Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
  val targetDir = file("docs/public/js")
  
  log.info(s"Copying Scala.js output from $sourceDir to $targetDir")
  IO.delete(targetDir)
  IO.createDirectory(targetDir)
  
  // Copy all JS files
  IO.copyDirectory(sourceDir, targetDir, overwrite = true)
  
  log.info(s"Frontend built and copied to $targetDir")
}

// Combined task to build all docs assets
lazy val buildDocsAssets = taskKey[Unit]("Build all documentation assets")
buildDocsAssets := Def.sequential(
  generateDocs,
  buildFrontend
).value

lazy val root = (project in file(".")).aggregate(
  core,
  zioBlocks,
  streams,
  runtime,
  proto,
  grpc,
  http,
  s3,
  pg,
  rocks,
  server,
  sharedProtocol.jvm,
  sharedProtocol.js,
  frontend
).settings(baseSettings, publish / skip := true, name := "graviton")

lazy val core = (project in file("modules/graviton-core"))
  .settings(baseSettings,
    name := "graviton-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-schema"  % V.zioSchema,
      "dev.zio" %% "zio-schema-derivation" % V.zioSchema,
      "dev.zio" %% "zio-prelude" % V.zioPrelude,
      "io.github.iltotore" %% "iron" % V.iron,
      "dev.zio" %% "zio-test"          % V.zio % Test,
      "dev.zio" %% "zio-test-sbt"      % V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % V.zio % Test
    )
  )

lazy val zioBlocks = (project in file("modules/zio-blocks"))
  .settings(baseSettings, publish / skip := true, name := "zio-blocks")

lazy val streams = (project in file("modules/graviton-streams"))
  .dependsOn(core, zioBlocks)
  .settings(baseSettings,
    name := "graviton-streams",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % V.zio,
      "dev.zio" %% "zio-streams"  % V.zio,
      "org.scodec" %% "scodec-core" % "2.3.3",
      "dev.zio" %% "zio-test"          % V.zio % Test,
      "dev.zio" %% "zio-test-sbt"      % V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % V.zio % Test
    )
  )

lazy val runtime = (project in file("modules/graviton-runtime"))
  .dependsOn(core, streams)
  .settings(baseSettings,
    name := "graviton-runtime",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % V.zio,
      "dev.zio" %% "zio-streams" % V.zio,
      "dev.zio" %% "zio-metrics-connectors" % "2.2.1"
    )
  )

  lazy val proto = (project in file("modules/protocol/graviton-proto"))
    .settings(
      baseSettings,
      name := "graviton-proto",
      libraryDependencies ++= Seq(
        "com.thesamet.scalapb" %% "scalapb-runtime" % V.scalapb % "protobuf",
        "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % V.scalapb,
      ),
      Compile / PB.targets := {
        val out = (Compile / sourceManaged).value / "scalapb"
        val options = Seq(
          "grpc",
          "flat_package=false",
          "java_conversions=false",
          "single_line_to_proto_string",
          "ascii_format_to_string",
          "lenses"
        )
        Seq(Target(gens.scalapb, out, options))
      },
    )

  lazy val grpc = (project in file("modules/protocol/graviton-grpc"))
    .dependsOn(runtime, proto)
    .settings(
      baseSettings,
      name := "graviton-grpc",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio"          % V.zio,
        "io.grpc" % "grpc-netty" % "1.50.1",
        "dev.zio" %% "zio-test"         % V.zio % Test,
        "dev.zio" %% "zio-test-sbt"     % V.zio % Test,
        "dev.zio" %% "zio-test-magnolia" % V.zio % Test,
      ),
    )

  lazy val http = (project in file("modules/protocol/graviton-http"))
    .dependsOn(runtime, grpc)
  .settings(baseSettings,
    name := "graviton-http",
    libraryDependencies ++= Seq(
        "dev.zio" %% "zio"               % V.zio,
        "dev.zio" %% "zio-http"          % V.zioHttp,
        "dev.zio" %% "zio-json"          % "0.7.3",
        "dev.zio" %% "zio-test"          % V.zio % Test,
        "dev.zio" %% "zio-test-sbt"      % V.zio % Test,
        "dev.zio" %% "zio-test-magnolia" % V.zio % Test
    )
  )

lazy val s3 = (project in file("modules/backend/graviton-s3"))
  .dependsOn(runtime)
  .settings(baseSettings,
    name := "graviton-s3",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "s3" % V.awsV2
    )
  )

lazy val pg = (project in file("modules/backend/graviton-pg"))
  .dependsOn(runtime)
  .settings(baseSettings,
    name := "graviton-pg",
    libraryDependencies ++= Seq(
      "org.postgresql" % "postgresql" % V.pg
    )
  )

lazy val rocks = (project in file("modules/backend/graviton-rocks"))
  .dependsOn(runtime)
  .settings(baseSettings,
    name := "graviton-rocks",
    libraryDependencies ++= Seq(
      "org.rocksdb" % "rocksdbjni" % V.rocksdbJni
    )
  )

lazy val server = (project in file("modules/server/graviton-server"))
  .dependsOn(runtime, grpc, http, s3, pg, rocks)
  .settings(baseSettings, name := "graviton-server")

// Shared protocol models for JVM and JS
lazy val sharedProtocol = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/protocol/graviton-shared"))
  .settings(
    baseSettings,
    name := "graviton-shared",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"      % V.zio,
      "dev.zio" %%% "zio-json" % "0.7.3"
    )
  )
  .jsSettings(
    Test / fork := false  // Scala.js tests cannot be forked
  )

// Frontend module with Scala.js
lazy val frontend = (project in file("modules/frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedProtocol.js)
  .settings(
    baseSettings,
    name := "graviton-frontend",
    Test / fork := false,  // Scala.js tests cannot be forked
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(ModuleSplitStyle.SmallModulesFor(List("graviton.frontend")))
    },
    libraryDependencies ++= Seq(
      "dev.zio"         %%% "zio"          % V.zio,
      "dev.zio"         %%% "zio-json"     % "0.7.3",
      "com.raquo"       %%% "laminar"      % V.laminar,
      "com.raquo"       %%% "waypoint"     % V.waypoint,
      "org.scala-js"    %%% "scalajs-dom"  % V.scalajsDom
    )
  )
