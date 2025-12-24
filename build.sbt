import sbt._
import Keys._
import BuildHelper._
import _root_.mdoc.MdocPlugin
import org.scalajs.linker.interface.ModuleSplitStyle
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._
import sbtprotoc.ProtocPlugin.autoImport._
import scalapb.compiler.Version
lazy val docSnippetMappings =
  settingKey[Seq[DocSnippet]]("Mappings between documentation files and compiled snippet sources.")

lazy val syncDocSnippets =
  taskKey[Unit]("Regenerate documentation snippet blocks from their source files.")

lazy val checkDocSnippets =
  taskKey[Unit]("Verify that documentation snippet blocks are up to date.")

lazy val V = new {
  val scala3     = "3.7.4"
  val zio        = "2.1.9"
  val zioSchema  = "1.5.0"
  val zioPrelude = "1.0.0-RC23"
  val zioGrpc    = "0.6.3"
  val zioHttp    = "3.0.0-RC7"
  val iron       = "2.6.0"
  val awsV2      = "2.25.54"
  val rocksdbJni = "8.11.3"
  val pg         = "42.7.4"
  val laminar    = "17.1.0"
  val waypoint   = "8.0.0"
  val scalajsDom = "2.8.0"
  val grpc       = "1.65.1"
}

ThisBuild / scalaVersion := V.scala3
ThisBuild / organization := "io.graviton"
ThisBuild / resolvers += Resolver.mavenCentral
ThisBuild / PB.protocVersion := "3.21.12"

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
  val indexFile = targetDir / "index.html"

  log.info("Generating Scaladoc for core modules...")

  val moduleDocs = List(
    "core" -> (LocalProject("core") / Compile / doc).value,
    "streams" -> (LocalProject("streams") / Compile / doc).value,
    "runtime" -> (LocalProject("runtime") / Compile / doc).value
  )

  IO.delete(targetDir)
  IO.createDirectory(targetDir)

  moduleDocs.foreach { case (name, srcDir) =>
    val dest = targetDir / name
    log.info(s"Copying $name scaladoc to $dest")
    IO.copyDirectory(srcDir, dest, overwrite = true, preserveLastModified = true)
  }

  // Provide a stable entry point at /scaladoc/index.html (and /scaladoc/) for GitHub Pages.
  // Each module is published under /scaladoc/<module>/.
  IO.write(
    indexFile,
    """<!doctype html>
      |<html lang="en">
      |  <head>
      |    <meta charset="utf-8" />
      |    <meta name="viewport" content="width=device-width, initial-scale=1" />
      |    <meta http-equiv="refresh" content="0; url=./core/index.html" />
      |    <title>Graviton Scaladoc</title>
      |    <style>
      |      body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 2rem; }
      |      code { background: rgba(0,0,0,0.06); padding: 0.1rem 0.3rem; border-radius: 0.3rem; }
      |    </style>
      |  </head>
      |  <body>
      |    <h1>Graviton Scaladoc</h1>
      |    <p>Redirecting to <code>core</code>… If it doesn’t load, choose a module:</p>
      |    <ul>
      |      <li><a href="./core/index.html">core</a></li>
      |      <li><a href="./streams/index.html">streams</a></li>
      |      <li><a href="./runtime/index.html">runtime</a></li>
      |    </ul>
      |  </body>
      |</html>
      |""".stripMargin
  )

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

lazy val docs = (project in file("docs-mdoc"))
  .enablePlugins(MdocPlugin)
  .dependsOn(core, runtime, streams)
  .settings(
    publish / skip := true,
    name := "graviton-docs",
    mdocIn := (ThisBuild / baseDirectory).value / "docs",
    mdocOut := target.value / "mdoc-out",
    mdocVariables += "version" -> version.value,
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "docs/snippets/src/main/scala"
  )

lazy val root = (project in file(".")).aggregate(
  core,
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
  frontend,
  docs
).settings(
  baseSettings,
  publish / skip := true,
  name := "graviton",
  docSnippetMappings := Seq(
    DocSnippet(
      id = "binary-streaming-ingest",
      docPath = "docs/guide/binary-streaming.md",
      snippetPath = "docs/snippets/src/main/scala/graviton/docs/guide/BinaryStreamingIngest.scala"
    )
  ),
  syncDocSnippets := {
    DocSnippetTasks.sync(
      docSnippetMappings.value,
      (ThisBuild / baseDirectory).value,
      Keys.streams.value.log
    )
  },
  checkDocSnippets := {
    DocSnippetTasks.check(
      docSnippetMappings.value,
      (ThisBuild / baseDirectory).value,
      Keys.streams.value.log
    )
  }
)

lazy val core = (project in file("modules/graviton-core"))
  .settings(baseSettings,
    name := "graviton-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-schema"  % V.zioSchema,
      "dev.zio" %% "zio-schema-derivation" % V.zioSchema,
      "com.kubuszok" %% "hearth" % "0.2.0",
      "io.getkyo" %% "kyo-data" % "0.19.0",
      "dev.zio" %% "zio-schema-json" % V.zioSchema,
      "dev.zio" %% "zio-json" % "0.7.3",
      "dev.zio" %% "zio-prelude" % V.zioPrelude,
      "org.scodec" %% "scodec-core" % "2.3.3",
      "io.github.iltotore" %% "iron" % V.iron,
      "dev.zio" %% "zio-test"          % V.zio % Test,
      "dev.zio" %% "zio-test-sbt"      % V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % V.zio % Test
    ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _)) => Seq(compilerPlugin("com.kubuszok" % "hearth-cross-quotes_3" % "0.2.0"))
      case _            => Seq.empty
    })
  )

lazy val streams = (project in file("modules/graviton-streams"))
  .dependsOn(core)
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
  .dependsOn(core, streams, sharedProtocol.jvm)
  .settings(baseSettings,
    name := "graviton-runtime",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % V.zio,
      "dev.zio" %% "zio-streams" % V.zio,
      "org.scodec" %% "scodec-core" % "2.3.3",
      "dev.zio" %% "zio-metrics-connectors" % "2.2.1",
      "dev.zio" %% "zio-test"          % V.zio % Test,
      "dev.zio" %% "zio-test-sbt"      % V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % V.zio % Test,
    )
  )

lazy val proto = (project in file("modules/protocol/graviton-proto"))
  .settings(
    baseSettings,
    name := "graviton-proto",
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % Version.scalapbVersion,
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.9.6-0" % "protobuf",
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.9.6-0"
    ),
    Compile / PB.targets := Seq(
      scalapb.gen(
        flatPackage = false,
        javaConversions = false,
        grpc = true,
        singleLineToProtoString = true,
        asciiFormatToString = true,
        lenses = true
      ) -> (Compile / sourceManaged).value / "scalapb",
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value / "scalapb"
    )
  )

lazy val grpc = (project in file("modules/protocol/graviton-grpc"))
  .dependsOn(runtime, proto)
  .settings(
    baseSettings,
    name := "graviton-grpc",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % V.zio,
      "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core" % V.zioGrpc,
      "io.grpc" % "grpc-netty" % V.grpc,
      "io.grpc" % "grpc-api" % V.grpc,
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
      "dev.zio" %% "zio"        % V.zio,
      "dev.zio" %% "zio-http"   % V.zioHttp,
      "dev.zio" %% "zio-schema" % V.zioSchema,
      "dev.zio" %% "zio-json"   % "0.7.3",
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
      "dev.zio" %%% "zio"                 % V.zio,
      "dev.zio" %%% "zio-json"            % "0.7.3",
      "dev.zio" %%% "zio-schema"          % V.zioSchema,
      "dev.zio" %%% "zio-schema-derivation" % V.zioSchema,
      "dev.zio" %%% "zio-schema-json"     % V.zioSchema
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
