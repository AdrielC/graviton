import sbt._
import Keys._
import BuildHelper._
import _root_.mdoc.MdocPlugin
import org.scalajs.linker.interface.ModuleSplitStyle
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._
import sbtprotoc.ProtocPlugin.autoImport._
import scalapb.compiler.Version
import sbtassembly.AssemblyPlugin
import sbtassembly.AssemblyPlugin.autoImport.*
import sbtassembly.MergeStrategy
import sbtversionpolicy.Compatibility
import sbtversionpolicy.SbtVersionPolicyPlugin.autoImport.*
import com.typesafe.tools.mima.core.{DirectMissingMethodProblem, MissingTypesProblem, ProblemFilters, ReversedMissingMethodProblem}
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport.mimaBinaryIssueFilters

lazy val docSnippetMappings =
  settingKey[Seq[DocSnippet]]("Mappings between documentation files and compiled snippet sources.")

lazy val syncDocSnippets =
  taskKey[Unit]("Regenerate documentation snippet blocks from their source files.")

lazy val checkDocSnippets =
  taskKey[Unit]("Verify that documentation snippet blocks are up to date.")

lazy val verifyShardcakeDependencyGraph =
  taskKey[Unit]("Verify the audited Shardcake integration dependency graph.")

lazy val V = Dependencies.V

lazy val nettyHttpDependencies = Seq(
  "netty-buffer",
  "netty-codec-base",
  "netty-codec-compression",
  "netty-codec-http",
  "netty-codec-socks",
  "netty-common",
  "netty-handler",
  "netty-handler-proxy",
  "netty-pkitesting",
  "netty-resolver",
  "netty-transport",
  "netty-transport-classes-epoll",
  "netty-transport-classes-kqueue",
  "netty-transport-native-epoll",
  "netty-transport-native-kqueue",
  "netty-transport-native-unix-common",
).map("io.netty" % _ % V.netty)

lazy val nettyGrpcDependencies = Seq(
  "netty-buffer",
  "netty-codec",
  "netty-codec-http",
  "netty-codec-http2",
  "netty-codec-socks",
  "netty-common",
  "netty-handler",
  "netty-handler-proxy",
  "netty-resolver",
  "netty-transport",
  "netty-transport-native-unix-common",
).map("io.netty" % _ % V.nettyGrpc)

ThisBuild / scalaVersion := V.scala3
ThisBuild / organization := "io.github.adrielc"
ThisBuild / resolvers += Resolver.mavenCentral
ThisBuild / PB.protocVersion := "3.21.12"

// Semantic versioning
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / libraryDependencySchemes ++= Seq(
  "dev.zio" %% "zio-json" % VersionScheme.Always,
  "dev.zio" % "zio-json_sjs1_3" % VersionScheme.Always,
  // One-time 0.4.0 -> 0.5.0 repair: 0.017 is an older duplicate of the 0.0.17
  // line whose malformed version sorts above 0.0.51. The exposed Register
  // descriptors are verified compatible and Graviton's API still passes MiMa.
  // Remove these Always schemes before the next ZIO Blocks version change so a
  // future real incompatibility cannot be hidden.
  "dev.zio" %% "zio-blocks-schema" % VersionScheme.Always,
  "dev.zio" % "zio-blocks-schema_sjs1_3" % VersionScheme.Always,
  "dev.zio" %% "zio-blocks-chunk" % VersionScheme.Always,
  "dev.zio" % "zio-blocks-chunk_sjs1_3" % VersionScheme.Always,
  "dev.zio" %% "zio-blocks-typeid" % VersionScheme.Always,
  "dev.zio" % "zio-blocks-typeid_sjs1_3" % VersionScheme.Always,
  // One-time RC6 -> RC7 repair published from the same zio-pdf API line. RC7's
  // own compatibility and external-consumer gates pass. Remove before the next
  // zio-pdf version change so future incompatibilities remain visible.
  "io.github.adrielc" %% "zio-pdf" % VersionScheme.Always,
  // checker-qual contains compile-time annotations and no runtime behavior.
  "org.checkerframework" % "checker-qual" % VersionScheme.Always,
  // Protobuf's numeric train is not parsed correctly by sbt-version-policy.
  "com.google.protobuf" % "protobuf-java" % VersionScheme.Always,
  // These are transport-private gRPC implementation dependencies. They do not
  // appear in Graviton's public signatures; MiMa and the real listener tests
  // remain authoritative for the published Graviton API and runtime behavior.
  "com.google.guava" % "guava" % VersionScheme.Always,
  "com.google.j2objc" % "j2objc-annotations" % VersionScheme.Always,
  "io.perfmark" % "perfmark-api" % VersionScheme.Always,
) ++ (nettyHttpDependencies ++ nettyGrpcDependencies)
  // Netty's x.y.z.Final versions are likewise rejected as ordinary patch bumps.
  .map(module => module.organization % module.name % VersionScheme.Always)
  .distinct
ThisBuild / dependencyOverrides += "com.google.protobuf" % "protobuf-java" % V.protobuf
// Ivy considers the older 0.017 artifact numerically newer than 0.0.51. Pin
// the actual current ZIO Blocks release across the build so transitive
// dependencies cannot silently move Graviton back to the legacy line.
ThisBuild / dependencyOverrides ++= Seq(
  "dev.zio" %% "zio-blocks-schema" % V.zioBlocks,
  "dev.zio" %% "zio-blocks-chunk" % V.zioBlocks,
  "dev.zio" %% "zio-blocks-mediatype" % V.zioBlocks,
)
ThisBuild / homepage := Some(url("https://github.com/AdrielC/graviton"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/AdrielC/graviton"),
    "scm:git:https://github.com/AdrielC/graviton.git",
  )
)
// The v0.7.0 boundary replaces raw storage failures and collected inventory
// with typed errors, opaque native cursors, and streaming contracts. Return to
// BinaryCompatible immediately after the v0.7.0 tag.
ThisBuild / versionPolicyIntention := Compatibility.None
ThisBuild / versionPolicyIgnoredInternalDependencyVersions := Some("^\\d+\\.\\d+\\.\\d+\\+\\d+.*".r)
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / developers := List(
  Developer(
    "AdrielC",
    "Adriel Casellas",
    "adrielcasellas@gmail.com",
    url("https://github.com/AdrielC")
  )
)

// Generate Scaladoc one module at a time. Scala 3.8's renderer is not safe
// when these projects render concurrently in the same sbt process.
lazy val generateDocs     = taskKey[Unit]("Generate Scaladoc and copy to docs folder")
lazy val copyGeneratedDocs = taskKey[Unit]("Copy generated Scaladoc into the docs site")
copyGeneratedDocs := {
  val log = Keys.streams.value.log
  val targetDir = file("docs/public/scaladoc")
  val indexFile = targetDir / "index.html"

  log.info("Collecting generated Scaladoc for JVM modules...")

  val moduleDocs = List(
    // Core runtime surface
    "core"            -> (LocalProject("core") / Compile / doc).value,
    "streams"         -> (LocalProject("streams") / Compile / doc).value,
    "runtime"         -> (LocalProject("runtime") / Compile / doc).value,
    "backend-laws"    -> (LocalProject("backendLaws") / Compile / doc).value,
    "graviton-pdf"    -> (LocalProject("pdf") / Compile / doc).value,
    "graviton-shardcake" -> (LocalProject("shardcakeIntegration") / Compile / doc).value,

    // Protocol stack (JVM)
    "graviton-shared" -> (sharedProtocol.jvm / Compile / doc).value,
    "graviton-proto"  -> (LocalProject("proto") / Compile / doc).value,
    "graviton-grpc"   -> (LocalProject("grpc") / Compile / doc).value,
    "graviton-http"   -> (LocalProject("http") / Compile / doc).value,

    // Backends (JVM)
    "graviton-s3"     -> (LocalProject("s3") / Compile / doc).value,
    "graviton-pg"     -> (LocalProject("pg") / Compile / doc).value,
    "graviton-rocks"  -> (LocalProject("rocks") / Compile / doc).value,

    // Server wiring (JVM)
    "graviton-server" -> (LocalProject("server") / Compile / doc).value,
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
      |    <title>Graviton Scaladoc</title>
      |    <style>
      |      body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 2rem; }
      |      li { margin: 0.25rem 0; }
      |      code { background: rgba(0,0,0,0.06); padding: 0.1rem 0.3rem; border-radius: 0.3rem; }
      |    </style>
      |  </head>
      |  <body>
      |    <h1>Graviton Scaladoc</h1>
      |    <p>Choose a module:</p>
      |    <ul>
      |      <li><a href="./core/index.html">core</a></li>
      |      <li><a href="./streams/index.html">streams</a></li>
      |      <li><a href="./runtime/index.html">runtime</a></li>
      |      <li><a href="./graviton-pdf/index.html">graviton-pdf</a></li>
      |      <li><a href="./graviton-shardcake/index.html">graviton-shardcake</a></li>
      |      <li><a href="./graviton-shared/index.html">graviton-shared</a></li>
      |      <li><a href="./graviton-proto/index.html">graviton-proto</a></li>
      |      <li><a href="./graviton-grpc/index.html">graviton-grpc</a></li>
      |      <li><a href="./graviton-http/index.html">graviton-http</a></li>
      |      <li><a href="./graviton-s3/index.html">graviton-s3</a></li>
      |      <li><a href="./graviton-pg/index.html">graviton-pg</a></li>
      |      <li><a href="./graviton-rocks/index.html">graviton-rocks</a></li>
      |      <li><a href="./graviton-server/index.html">graviton-server</a></li>
      |    </ul>
      |  </body>
      |</html>
      |""".stripMargin
  )

  log.info(s"Scaladoc copied to $targetDir")
}

generateDocs := Def.sequential(
  LocalProject("core") / Compile / doc,
  LocalProject("streams") / Compile / doc,
  LocalProject("runtime") / Compile / doc,
  LocalProject("pdf") / Compile / doc,
  sharedProtocol.jvm / Compile / doc,
  LocalProject("proto") / Compile / doc,
  LocalProject("grpc") / Compile / doc,
  LocalProject("http") / Compile / doc,
  LocalProject("s3") / Compile / doc,
  LocalProject("pg") / Compile / doc,
  LocalProject("rocks") / Compile / doc,
  LocalProject("server") / Compile / doc,
  copyGeneratedDocs,
).value

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

// Link the streaming file analyzer and bounded PDF editor as separate ES modules
// for the documentation playground. Ordinary files never download the heavier
// document graph editor; Vite loads it only after the analyzer confirms a PDF.
lazy val buildContentLab = taskKey[Unit]("Build the streamed Scala.js content lab and copy it to docs")
lazy val prepareFrontendNodeModules = taskKey[Unit]("Expose docs npm modules to Scala.js Node test runners")

prepareFrontendNodeModules := {
  val source = (ThisBuild / baseDirectory).value / "docs" / "node_modules"
  val link   = (ThisBuild / baseDirectory).value / "node_modules"
  if (!source.isDirectory) {
    sys.error("Missing docs/node_modules. Run `npm ci --prefix docs` before Scala.js browser-module tests or docs builds.")
  }
  if (!java.nio.file.Files.exists(link.toPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
    java.nio.file.Files.createSymbolicLink(link.toPath, source.toPath)
  }
}

buildContentLab := {
  val log = Keys.streams.value.log
  log.info("Building streamed Scala.js content lab...")

  val _modules   = prepareFrontendNodeModules.value
  val _         = (contentLab / Compile / fullLinkJS).value
  val sourceDir = (contentLab / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
  val targetDir = file("docs/.vitepress/generated/content-lab")
  val _pdf      = (pdfContentLab / Compile / fullLinkJS).value
  val pdfSource = (pdfContentLab / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
  val pdfTarget = file("docs/.vitepress/generated/pdf-lab")

  log.info(s"Copying Scala.js output from $sourceDir to $targetDir for Vite bundling")
  IO.delete(targetDir)
  IO.createDirectory(targetDir)
  IO.copyDirectory(sourceDir, targetDir, overwrite = true)
  IO.delete(pdfTarget)
  IO.createDirectory(pdfTarget)
  IO.copyDirectory(pdfSource, pdfTarget, overwrite = true)

  log.info(s"Streamed content lab built and copied to $targetDir")
}

// Combined task to build all docs assets
lazy val buildDocsAssets = taskKey[Unit]("Build all documentation assets")
buildDocsAssets := Def.sequential(
  generateDocs,
  buildContentLab,
  buildFrontend
).value

lazy val docs = (project in file("docs-mdoc"))
  .enablePlugins(MdocPlugin)
  .dependsOn(core, runtime, streams)
  .settings(
    publish / skip := true,
    name := "graviton-docs",
    // Scala 3.8+ deprecated -Xfatal-warnings in favour of -Werror; strip the old flag to avoid
    // the deprecation warning itself becoming a fatal error.
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(o => o == "-Xfatal-warnings" || o == "-Werror"),
    mdocIn := (ThisBuild / baseDirectory).value / "docs",
    mdocOut := target.value / "mdoc-out",
    // Keep mdoc fast + deterministic: ignore generated assets and npm installs under docs/.
    // (These folders can appear in CI or local builds and create thousands of irrelevant pages/warnings.)
    mdocExtraArguments ++= Seq(
      "--exclude",
      "node_modules",
      "--exclude",
      ".vitepress/dist",
      "--exclude",
      ".vitepress/cache",
      "--exclude",
      ".vitepress/.temp",
      "--exclude",
      "public"
    ),
    mdocVariables += "version" -> version.value,
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "docs/snippets/src/main/scala",
    // mdoc 2.9.1 still pins vulnerable documentation-server/parser releases.
    // These dependencies are build-only and remain API compatible for our mdoc use.
    libraryDependencies ++= Seq(
      "io.undertow" % "undertow-core" % "2.4.2.Final",
      "org.jsoup" % "jsoup" % "1.23.1",
    ),
  )

lazy val cli = (project in file("modules/graviton-cli"))
  .dependsOn(runtime, streams)
  .settings(
    baseSettings,
    name := "graviton-cli",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % V.zio,
      "dev.zio" %% "zio-streams" % V.zio,
    ),
  )

lazy val root = (project in file(".")).aggregate(
  core,
  streams,
  runtime,
  backendLaws,
  pdf,
  cli,
  proto,
  grpc,
  http,
  s3,
  pg,
  rocks,
  security,
  shardcakeIntegration,
  server,
  sharedProtocol.jvm,
  sharedProtocol.js,
  frontend,
  docs,
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
  },
  verifyShardcakeDependencyGraph := {
    val log     = Keys.streams.value.log
    val modules = (shardcakeIntegration / Compile / update).value.allModules

    val scala3Libraries = modules.filter(_.name == "scala3-library_3")
    val zioBlocks       = modules.filter(module =>
      module.organization == "dev.zio" && module.name.startsWith("zio-blocks-")
    )
    val grpcNetty       = modules.filter(module =>
      module.organization == "io.grpc" && module.name == "grpc-netty"
    )
    val grpcNettyShaded = modules.filter(module =>
      module.organization == "io.grpc" && module.name == "grpc-netty-shaded"
    )
    val kryo = modules.filter(_.name == "kryo")

    def revisions(dependencies: Seq[ModuleID]): Set[String] = dependencies.map(_.revision).toSet
    def coordinates(dependencies: Seq[ModuleID]): String =
      dependencies.map(module => s"${module.organization}:${module.name}:${module.revision}").mkString(", ")

    if (revisions(scala3Libraries) != Set(V.scala3))
      sys.error(
        s"Shardcake integration must resolve only Scala ${V.scala3}; found ${coordinates(scala3Libraries)}"
      )
    if (zioBlocks.isEmpty || revisions(zioBlocks) != Set(V.zioBlocks))
      sys.error(
        s"Shardcake integration must resolve only ZIO Blocks ${V.zioBlocks}; found ${coordinates(zioBlocks)}"
      )
    if (grpcNetty.nonEmpty)
      sys.error(s"Unshaded grpc-netty is forbidden; found ${coordinates(grpcNetty)}")
    if (revisions(grpcNettyShaded) != Set(V.grpc))
      sys.error(
        s"Shardcake integration must resolve only grpc-netty-shaded ${V.grpc}; found ${coordinates(grpcNettyShaded)}"
      )
    if (kryo.nonEmpty)
      sys.error(s"Kryo is forbidden in the Shardcake integration; found ${coordinates(kryo)}")

    log.info(
      s"Verified Shardcake graph: Scala ${V.scala3}, ZIO Blocks ${V.zioBlocks}, " +
        s"grpc-netty-shaded ${V.grpc}, no grpc-netty, no Kryo."
    )
  }
)

lazy val core = (project in file("modules/graviton-core"))
  .dependsOn(sharedProtocol.jvm)
  .settings(baseSettings,
    name := "graviton-core",
    // v0.4.0 exposed these implementation traits with singleton-selected
    // parents. Their TASTy crashes a separate Scala compilation when it follows
    // BinaryAttributes into FileSize or ChunkCount. v0.5 keeps the names as
    // markers and moves the implementation to the static SizeTrait companion.
    // The static trait also requires sealed Int/Long evidence instead of an
    // unsafe runtime cast. No unrelated MiMa issue is excluded. Remove these
    // filters after v0.5.0 becomes the previous stable MiMa baseline.
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[MissingTypesProblem]("graviton.core.types$Size1"),
      ProblemFilters.exclude[MissingTypesProblem]("graviton.core.types$SizeLong1"),
      ProblemFilters.exclude[MissingTypesProblem]("graviton.core.types$IndexLong0"),
      ProblemFilters.exclude[ReversedMissingMethodProblem](
        "graviton.core.types#SizeTrait#Trait.graviton$core$types$SizeTrait$Trait$$sizeNumeric"
      ),
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-schema"  % V.zioSchema,
      "dev.zio" %% "zio-schema-derivation" % V.zioSchema,
      "com.kubuszok" %% "hearth" % "0.2.0",
      "io.getkyo" %% "kyo-data" % V.kyo,
      "io.getkyo" %% "kyo-core" % V.kyo,
      "io.getkyo" %% "kyo-prelude" % V.kyo,
      "io.getkyo" %% "kyo-zio" % V.kyo,
      "dev.zio" %% "zio-schema-json" % V.zioSchema,
      "dev.zio" %% "zio-prelude" % V.zioPrelude,
      "org.scodec" %% "scodec-core" % "2.3.3",
      "io.github.iltotore" %% "iron" % V.iron,
      "pt.kcry" %% "blake3" % V.blake3,
      "dev.zio" %% "zio-blocks-schema" % V.zioBlocks,
      "dev.zio" %% "zio-blocks-mediatype" % V.zioBlocks,
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
    // v0.5.0 emitted constructor, apply, and copy methods for these private
    // ingest-loop accumulators. Byte-reuse metrics added private fields without
    // changing any source-visible runtime API, but MiMa cannot recover the
    // Scala-private boundary from the classfiles. Exclude only those private
    // synthetic methods and continue checking every public runtime symbol.
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[DirectMissingMethodProblem]("graviton.runtime.stores.CasBlobStore#PersistAcc.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("graviton.runtime.stores.CasBlobStore#PersistAcc.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("graviton.runtime.stores.CasBlobStore#PersistAcc.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("graviton.runtime.stores.CasBlobStore#PersistSummary.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("graviton.runtime.stores.CasBlobStore#PersistSummary.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("graviton.runtime.stores.CasBlobStore#PersistSummary.copy"),
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % V.zio,
      "dev.zio" %% "zio-streams" % V.zio,
      "dev.zio" %% "zio-nio"     % V.zioNio,
      "dev.zio" %% "zio-config"          % V.zioConfig,
      "dev.zio" %% "zio-config-typesafe" % V.zioConfig,
      "dev.zio" %% "zio-blocks-schema" % V.zioBlocks,
      "dev.zio" %% "zio-blocks-mediatype" % V.zioBlocks,
      "org.scodec" %% "scodec-core" % "2.3.3",
      // Retain the connector facade in the published runtime POM for 0.5.x
      // dependency compatibility. Concrete exporters remain server concerns.
      "dev.zio" %% "zio-metrics-connectors" % V.zioMetricsConnectorsRuntimeCompat,
      "dev.zio" %% "zio-test"          % V.zio % Test,
      "dev.zio" %% "zio-test-sbt"      % V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % V.zio % Test,
    )
  )

lazy val backendLaws = (project in file("modules/graviton-backend-laws"))
  .dependsOn(runtime)
  .settings(
    baseSettings,
    name := "graviton-backend-laws",
    mimaPreviousArtifacts := Set.empty,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"          % V.zio,
      "dev.zio" %% "zio-test-sbt"      % V.zio,
      "dev.zio" %% "zio-test-magnolia" % V.zio,
    ),
  )

lazy val pdf = (project in file("modules/graviton-pdf"))
  .dependsOn(runtime)
  .settings(
    baseSettings,
    name := "graviton-pdf",
    libraryDependencies ++= Seq(
      "io.github.adrielc" %% "zio-pdf" % V.zioPdf,
      "dev.zio" %% "zio-blocks-schema" % V.zioBlocks,
      "dev.zio" %% "zio-blocks-chunk" % V.zioBlocks,
      "dev.zio" %% "zio-blocks-mediatype" % V.zioBlocks,
      "dev.zio" %% "zio-test"          % V.zio % Test,
      "dev.zio" %% "zio-test-sbt"      % V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % V.zio % Test,
    ),
  )

lazy val proto = (project in file("modules/protocol/graviton-proto"))
  .settings(
    baseSettings,
    name := "graviton-proto",
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % Version.scalapbVersion,
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.9.6-0" % "protobuf",
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.9.6-0",
      "com.google.protobuf" % "protobuf-java" % V.protobuf,
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
  .dependsOn(runtime, proto, security)
  .settings(
    baseSettings,
    name := "graviton-grpc",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % V.zio,
      "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core" % V.zioGrpc,
      "io.grpc" % "grpc-netty-shaded" % V.grpc,
      "io.grpc" % "grpc-api" % V.grpc,
      "com.google.protobuf" % "protobuf-java-util" % V.protobuf,
      "dev.zio" %% "zio-blocks-mediatype" % V.zioBlocks,
      "dev.zio" %% "zio-test"         % V.zio % Test,
      "dev.zio" %% "zio-test-sbt"     % V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % V.zio % Test,
    ),
  )

lazy val http = (project in file("modules/protocol/graviton-http"))
  // The HTTP runtime is transport-independent. gRPC is needed only by the
  // parity test and must not leak Netty 4.1 into the zio-http/Netty 4.2 server.
  .dependsOn(runtime, pdf, security, grpc % "test->compile")
  .settings(baseSettings,
    name := "graviton-http",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"        % V.zio,
      "dev.zio" %% "zio-http"   % V.zioHttp,
      "dev.zio" %% "zio-schema" % V.zioSchema,
      "dev.zio" %% "zio-schema-json" % V.zioSchema,
      "dev.zio" %% "zio-blocks-mediatype" % V.zioBlocks,
      "dev.zio" %% "zio-test"          % V.zio % Test,
      "dev.zio" %% "zio-test-sbt"      % V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % V.zio % Test
    ) ++ nettyHttpDependencies
  )

lazy val s3 = (project in file("modules/backend/graviton-s3"))
  .dependsOn(runtime)
  .settings(baseSettings,
    name := "graviton-s3",
    libraryDependencies ++= Seq(
      // Graviton uses the synchronous S3Client. The AWS services parent also
      // declares its asynchronous Netty 4.1 client at runtime; exclude that
      // unused transport so it cannot conflict with zio-http's Netty 4.2.
      ("software.amazon.awssdk" % "s3" % V.awsV2)
        .exclude("software.amazon.awssdk", "netty-nio-client"),
      "software.amazon.awssdk" % "apache-client" % V.awsV2,
    )
  )

lazy val pg = (project in file("modules/backend/graviton-pg"))
  .dependsOn(runtime, security)
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

lazy val security = (project in file("modules/security/graviton-security"))
  .dependsOn(runtime)
  .settings(
    baseSettings,
    name := "graviton-security",
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio"               % V.zio,
      "dev.zio"       %% "zio-streams"       % V.zio,
      "dev.zio"       %% "zio-json"          % V.zioJson,
      "org.postgresql" % "postgresql"        % V.pg,
      "com.nimbusds"   % "nimbus-jose-jwt"    % "10.9.1",
      "dev.zio"       %% "zio-test"          % V.zio % Test,
      "dev.zio"       %% "zio-test-sbt"      % V.zio % Test,
      "dev.zio"       %% "zio-test-magnolia" % V.zio % Test,
    ),
  )

lazy val shardcakeIntegration = (project in file("modules/integration/graviton-shardcake"))
  .dependsOn(runtime, pdf)
  .settings(
    baseSettings,
    name := "graviton-shardcake",
    // This is the first release line for the optional integration artifact.
    // Begin MiMa comparison after it has a published baseline.
    mimaPreviousArtifacts := Set.empty,
    libraryDependencies ++= Seq(
      "com.devsisters" %% "shardcake-entities" % V.shardcake,
      "com.devsisters" %% "shardcake-manager" % V.shardcake,
      ("com.devsisters" %% "shardcake-protocol-grpc" % V.shardcake)
        .exclude("io.grpc", "grpc-netty"),
      "io.grpc" % "grpc-netty-shaded" % V.grpc,
      "dev.zio" %% "zio" % V.zio,
      "dev.zio" %% "zio-streams" % V.zio,
      "dev.zio" %% "zio-http" % V.zioHttp,
      "dev.zio" %% "zio-config" % V.zioConfig,
      "dev.zio" %% "zio-blocks-schema" % V.zioBlocks,
      "dev.zio" %% "zio-blocks-schema-messagepack" % V.zioBlocks,
      "dev.zio" %% "zio-blocks-mediatype" % V.zioBlocks,
      "org.postgresql" % "postgresql" % V.pg,
      "dev.zio" %% "zio-test" % V.zio % Test,
      "dev.zio" %% "zio-test-sbt" % V.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % V.zio % Test,
      "io.zonky.test" % "embedded-postgres" % V.embeddedPg % Test,
    ),
  )

lazy val server = (project in file("modules/server/graviton-server"))
  .enablePlugins(AssemblyPlugin)
  .dependsOn(runtime, http, grpc, s3, pg, rocks, security, shardcakeIntegration)
  .settings(
    baseSettings,
    name := "graviton-server",
    publish / skip := true,
    assembly / mainClass := Some("graviton.server.Main"),
    assembly / assemblyJarName := s"graviton-server-${version.value}.jar",
    assembly / assemblyMergeStrategy := {
      val previous: String => MergeStrategy = (assembly / assemblyMergeStrategy).value
      (path: String) =>
        path match {
        case PathList("META-INF", "services", _*) => MergeStrategy.concat
        // Log4j2 discovers its built-in plugins from this binary cache. Keep
        // the core descriptor so the packaged server does not fall back to
        // runtime classpath scanning.
        case PathList("META-INF", "org", "apache", "logging", "log4j", "core", "config", "plugins", "Log4j2Plugins.dat") =>
          MergeStrategy.first
        case PathList("META-INF", _*)             => MergeStrategy.discard
        case "module-info.class"                  => MergeStrategy.discard
        // Scala 3.8 ships @unroll in scala-library. Some dependencies still
        // carry the compatibility artifact, so retain the compiler-owned copy.
        case PathList("scala", "annotation", "unroll.class") => MergeStrategy.first
        case PathList("scala", "annotation", "unroll.tasty") => MergeStrategy.first
        case path                                  => previous(path)
        }
    },
    Compile / sourceGenerators += Def.task {
      val output = (Compile / sourceManaged).value / "graviton" / "server" / "BuildInfo.scala"
      val currentVersion = version.value.replace("\\", "\\\\").replace("\"", "\\\"")
      IO.write(
        output,
        s"""package graviton.server
           |
           |private[server] object BuildInfo:
           |  val version: String = "$currentVersion"
           |""".stripMargin,
      )
      Seq(output)
    }.taskValue,
    libraryDependencies ++= Seq(
      // Route all SLF4J logs (including dependencies) through Log4j2.
      "org.apache.logging.log4j" % "log4j-api" % V.log4j,
      "org.apache.logging.log4j" % "log4j-core" % V.log4j,
      "org.apache.logging.log4j" % "log4j-slf4j2-impl" % V.log4j,
      "dev.zio" %% "zio-metrics-connectors-prometheus" % V.zioMetricsConnectors,
      "dev.zio" %% "zio-blocks-schema" % V.zioBlocks,
      // The Datastar attribute DSL and HTML algebra are compatible with the
      // server. Exclude zio-blocks-http-model because 0.0.51 publishes an
      // experimental zio.http model under the same package as zio-http 3.x.
      // Console responses use Datastar's supported text/html morph protocol,
      // so the conflicting ServerSentEvent implementation is not required.
      ("dev.zio" %% "zio-blocks-datastar" % V.zioBlocks)
        .exclude("dev.zio", "zio-blocks-http-model_3"),
      "io.zonky.test" % "embedded-postgres" % V.embeddedPg % Test,
    ),
  )

// Shared protocol models for JVM and JS
lazy val sharedProtocol = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/protocol/graviton-shared"))
  .settings(
    baseSettings,
    name := "graviton-shared",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"                   % V.zio,
      "dev.zio" %%% "zio-schema"            % V.zioSchema,
      "dev.zio" %%% "zio-schema-derivation" % V.zioSchema,
      "dev.zio" %%% "zio-schema-json"       % V.zioSchema,
      "dev.zio" %%% "zio-blocks-schema"     % V.zioBlocks,
      "dev.zio" %%% "zio-blocks-mediatype"  % V.zioBlocks,
      "io.github.iltotore" %%% "iron"        % V.iron,
      "dev.zio" %%% "zio-test"              % V.zio % Test,
      "dev.zio" %%% "zio-test-sbt"          % V.zio % Test,
    )
  )
  .jsSettings(
    Test / fork := false, // Scala.js tests cannot be forked
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
  )

// Frontend module with Scala.js
lazy val frontend = (project in file("modules/frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedProtocol.js)
  .settings(
    baseSettings,
    name := "graviton-frontend",
    publish / skip := true,
    Test / fork := false,  // Scala.js tests cannot be forked
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(ModuleSplitStyle.SmallModulesFor(List("graviton.frontend")))
    },
    libraryDependencies ++= Seq(
      "dev.zio"         %%% "zio"          % V.zio,
      "dev.zio"         %%% "zio-schema-json"     % V.zioSchema,
      "com.raquo"       %%% "laminar"      % V.laminar,
      "org.scala-js"    %%% "scalajs-dom"  % V.scalajsDom
    )
  )
// Browser-only streamed CAS comparison. It is kept out of graviton-shared so
// the published protocol artifact does not inherit a documentation UI runtime.
lazy val contentLab = (project in file("modules/frontend/graviton-content-lab"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedProtocol.js)
  .settings(
    baseSettings,
    name := "graviton-content-lab",
    publish / skip := true,
    Test / fork := false,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Test / test := (Test / test).dependsOn(LocalRootProject / prepareFrontendNodeModules).value,
    libraryDependencies ++= Seq(
      "dev.zio"              %%% "zio"         % V.zio,
      "dev.zio"              %%% "zio-streams" % V.zio,
      "io.github.adrielc"    %%% "zio-pdf"     % V.zioPdf,
      "io.github.iltotore"   %%% "iron"        % V.iron,
      "org.scala-js"         %%% "scalajs-dom" % V.scalajsDom,
      "dev.zio"              %%% "zio-test"     % V.zio % Test,
      "dev.zio"              %%% "zio-test-sbt" % V.zio % Test,
    ),
  )

// The document graph editor is separately linked so ordinary file comparison
// does not pay its download and parse cost. This module is loaded only after a
// PDF has been identified and remains protected by BrowserPdfTools' byte cap.
lazy val pdfContentLab = (project in file("modules/frontend/graviton-pdf-lab"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    baseSettings,
    name := "graviton-pdf-lab",
    publish / skip := true,
    Test / fork := false,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    libraryDependencies ++= Seq(
      "dev.zio"              %%% "zio"         % V.zio,
      "dev.zio"              %%% "zio-streams" % V.zio,
      "io.github.adrielc"    %%% "zio-pdf"     % V.zioPdf,
      "io.github.iltotore"   %%% "iron"        % V.iron,
      "org.scala-js"         %%% "scalajs-dom" % V.scalajsDom,
      "dev.zio"              %%% "zio-test"     % V.zio % Test,
      "dev.zio"              %%% "zio-test-sbt" % V.zio % Test,
    ),
  )
