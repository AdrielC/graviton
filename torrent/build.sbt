import Dependencies._
import sbt.file
import BuildHelper.{gitBaseUrl, gitServer}
import com.github.sbt.git.SbtGit.GitKeys._
import org.typelevel.scalacoptions.{ScalacOption, ScalacOptions}



ThisBuild / publishTo := {
  val mavenPath = "/api/packages/tybera/maven"
  if (isSnapshot.value)
    Some("gitea-snapshots" at s"$gitBaseUrl$mavenPath").map(_.withAllowInsecureProtocol(true))
  else
    Some("gitea-releases" at s"$gitBaseUrl$mavenPath").map(_.withAllowInsecureProtocol(true))
}

// Publishing configuration
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / Test / publishArtifact := false


// Base configuration
ThisBuild / organization := "com.tybera"
ThisBuild / organizationName := "Tybera"
ThisBuild / startYear := Some(2025)
ThisBuild / scalaVersion := "3.7.2"

// Git configuration
ThisBuild / git.useGitDescribe := true
ThisBuild / git.baseVersion := "0.1.0"
ThisBuild / git.gitTagToVersionNumber := { tag =>
  if(tag matches "[0-9]+\\..*") Some(tag)
  else None
}


// Credentials for Gitea Maven registry
ThisBuild / credentials += {
  val user =
    sys.env.getOrElse("MAVEN_USER", sys.env.getOrElse("CI_USER", "git.runner"))
  val token =
    sys.env.getOrElse("MAVEN_TOKEN", sys.env.getOrElse("CI_TOKEN", ""))
  Credentials(
    realm = "Gitea Package API",
    host = gitServer,
    userName = user,
    passwd = token
  )
}

// Publishing configuration
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / Test / publishArtifact := false

// semanticdb
ThisBuild / semanticdbEnabled := true
// ThisBuild / semanticdbVersion := "4.13.1"


// Common settings
lazy val commonSettings = Seq(
  // Compiler options
  tpolecatCiModeOptions := Set.empty,
  tpolecatDevModeOptions := Set.empty,
  tpolecatExcludeOptions := Set.empty,
  Test / tpolecatExcludeOptions += ScalacOptions.warnUnusedImports,
  // Add our own compiler options
  Compile / scalacOptions ++= Seq(
    // "-Xsemanticdb",
    // "-Xplugin:semanticdb"
    // "-Werror",
    // "-Xfatal-warnings",
    // "-Xkind-projector",    // For kind projector syntax
    // "-deprecation",        // Warn about deprecated features
    // "-encoding", "UTF-8",  // Specify character encoding used by source files
    // "-feature",            // Emit warnings about features that should be imported explicitly
    // "-unchecked",         // Enable additional warnings about generated code
    // "-Wunused:imports",   // Warn about unused imports
    // "-Wunused:privates",  // Warn about unused private members
    // "-Wunused:locals",    // Warn about unused local definitions
    // "-Wunused:explicits", // Warn about unused explicit parameters
    // "-Wunused:implicits", // Warn about unused implicit parameters
    // "-Wunused:params",    // Warn about unused parameters
    // "-Wvalue-discard"     // Warn when non-Unit expression results are unused
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

  // Publishing settings
  publishMavenStyle := true,
  Test / publishArtifact := false,
  pomIncludeRepository := { _ => false }
)

import laika.ast.Path.Root
import laika.helium.Helium
import laika.helium.config._
import laika.config.{Versions => LaikaVersions, _}
import laika.format.Markdown
import laika.theme.config._
import laika.helium.config.{TextLink, IconLink}

// Custom task for complete site generation
lazy val generateSite = taskKey[Unit](
  "Generate the complete documentation site including API docs and guides"
)

lazy val docs = project
  .in(file("modules/docs"))
  .enablePlugins(TypelevelSitePlugin)
  .dependsOn(core, fs, s3, postgres, pdf)
  .settings(
    publish / skip := true,
    mdocIn := baseDirectory.value / "src" / "docs",
    mdocOut := baseDirectory.value / "target" / "mdoc",
    laikaExtensions := Seq(
      laika.config.PrettyURLs,
      laika.format.Markdown.GitHubFlavor,
      laika.config.SyntaxHighlighting
    ),
    tlSiteApiUrl := Some(url(s"$gitBaseUrl/tybera/torrent/wiki")),
    tlSiteApiPackage.withRank(KeyRanks.Invisible) := Some("torrent"),
    laikaIncludeEPUB := true,
    laikaIncludePDF := true,
    laikaIncludeAPI := true,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      core,
      fs,
      s3,
      postgres,
      pdf
    ),
    ScalaUnidoc / unidoc / target := baseDirectory.value / "target" / "docs" / "site" / "scaladoc",
    generateSite := Def
      .sequential(
        root / clean,
        (Compile / compile)
          .all(ScopeFilter(inProjects(core, fs, s3, postgres, pdf))),
        Compile / unidoc,
        mdoc.toTask(""),
        tlSite
      )
      .value,
    mdocVariables := Map(
      "VERSION" -> version.value,
      "SCALA_VERSION" -> scalaVersion.value
    ),
    tlSiteHelium := {
      tlSiteHelium.value.site
        .downloadPage(
          title = "Documentation Downloads",
          description = Some(
            "Download the documentation in EPUB or PDF format for offline reading"
          ),
          downloadPath = Root / "downloads",
          includeEPUB = true,
          includePDF = true
        )
        .epub
        .coverImages(CoverImage(Root / "images" / "torrent-cover.png"))
        .pdf
        .coverImages(CoverImage(Root / "images" / "torrent-docs.png"))
        .all
        .themeColors(
          primary = Color.hex("4ade80"), // Green 400 - Bright, electric green
          secondary = Color.hex("22c55e"), // Green 600 - Deep, rich green
          primaryMedium =
            Color.hex("86efac"), // Green 300 - Light, energetic green
          primaryLight = Color.hex("021910"), // Super dark green background
          text =
            Color.hex("f0fdf4"), // Green 50 - Almost white text for contrast
          background =
            Color.hex("010d08"), // Nearly black with slight green tint
          bgGradient = (
            Color.hex("021910"),
            Color.hex("010d08")
          ) // Dark to darker gradient
        )
        .site
        .metadata(
          title = Some("Torrent"),
          authors = Seq("Tybera"),
          language = Some("en"),
          version = Some(version.value)
        )
        .site
        .landingPage(
          logo = Some(
            laika.ast.Image(
              laika.ast.InternalTarget(Root / "images" / "logo.png")
            )
          ),
          title = Some("Torrent"),
          subtitle = Some("A ZIO-native Content-Addressable Storage system"),
          latestReleases = Seq(
            ReleaseInfo("Latest Release", version.value)
          ),
          titleLinks = Seq(
            LinkGroup.create(
              IconLink.external(
                s"$gitBaseUrl/tybera/torrent",
                HeliumIcon.github
              )
            )
          ),
          teasers = Seq(
            Teaser(
              "Content-Addressable Storage",
              "Store and retrieve data using cryptographic hashes for integrity and deduplication"
            ),
            Teaser(
              "ZIO Native",
              "Built on ZIO for type-safe, composable, and concurrent data operations"
            ),
            Teaser(
              "Multiple Backends",
              "Support for local filesystem, S3, and PostgreSQL storage backends"
            )
          )
        )
        .site
        .topNavigationBar(
          homeLink = IconLink.internal(Root / "index.md", HeliumIcon.home),
          navLinks = Seq(
            TextLink.internal(
              Root / "getting-started" / "installation.md",
              "Getting Started"
            ),
            TextLink.internal(
              Root / "core-concepts" / "index.md",
              "Core Concepts"
            ),
            TextLink.internal(Root / "api-reference" / "index.md", "API"),
            TextLink.internal(Root / "guides" / "index.md", "Guides"),
            IconLink.external(s"$gitBaseUrl/tybera/torrent", HeliumIcon.github)
          )
        )
        .site
        .mainNavigation(
          depth = 3,
          includePageSections = true
        )
        .site
        .pageNavigation(
          enabled = true,
          depth = 2,
          sourceBaseURL = Some(
            s"$gitBaseUrl/tybera/torrent/src/branch/${git.gitCurrentBranch.value}/modules/docs/src/docs"
          ),
          keepOnSmallScreens = false
        )
        .site
        .favIcons(Favicon.internal(Root / "images" / "logo.png"))
        .site
        .darkMode
        .disabled
    }
  )

lazy val root = project
  .in(file("."))
  .enablePlugins(BuildInfoPlugin, GitVersioning, TypelevelSitePlugin)
  .enablePlugins(BuildInfoPlugin, GitVersioning, TypelevelSitePlugin)
  .settings(commonSettings)
  .settings(
    name := "torrent",
    publish / skip := true
  )
  .aggregate(core, fs, s3, postgres, pdf, examples)

lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(mimaSettings("torrent-core"))
  .settings(commonSettings)
  .settings(mimaSettings("torrent-core"))
  .settings(commonSettings)
  .settings(mimaSettings("torrent-core"))
  .settings(
    name := "torrent-core",
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      BuildInfoKey.action("buildTime") {
        java.time.OffsetDateTime
          .now(java.time.ZoneOffset.UTC)
          .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        java.time.OffsetDateTime
          .now(java.time.ZoneOffset.UTC)
          .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      },
      BuildInfoKey.action("gitCommit") { // scalafix:ok
        gitHeadCommit.value
      },
      BuildInfoKey.action("gitBranch") {
        gitCurrentBranch.value
      }
    ),
    buildInfoPackage := "torrent.build",
    buildInfoObject := "BuildInfo",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-prelude" % Versions.zioPrelude,
      "dev.zio" %% "zio-schema" % Versions.zioSchema,
      "dev.zio" %% "zio-schema-derivation" % Versions.zioSchema,
      "dev.zio" %% "zio-schema-json" % Versions.zioSchema,
      "dev.zio" %% "zio-schema-protobuf" % Versions.zioSchema,
      "pt.kcry" %%% "blake3" % Versions.blake3,
      "dev.zio" %% "zio-logging" % Versions.zioLogging,
      "io.github.iltotore" %% "iron-zio" % Versions.iron,
      "io.github.iltotore" %% "iron-zio-json" % Versions.iron,
      "org.scodec" %% "scodec-bits" % Versions.scodecBits,
      "org.scodec" %% "scodec-core" % Versions.scodecCore,
      "commons-codec" % "commons-codec" % Versions.commonsCodec,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
    )
  )

lazy val examples = project
  .in(file("modules/examples"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "torrent-examples",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
    )
  )

lazy val fs = project
  .in(file("modules/fs"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "torrent-fs",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-nio" % Versions.zioNio,
      "dev.zio" %% "zio-prelude" % Versions.zioPrelude,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-nio" % Versions.zioNio,
      "dev.zio" %% "zio-prelude" % Versions.zioPrelude,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
    )
  )

lazy val s3 = project
  .in(file("modules/s3"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(  
    name := "torrent-s3",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-prelude" % Versions.zioPrelude,
      "dev.zio" %% "zio-aws-s3" % Versions.zioAws,
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-prelude" % Versions.zioPrelude,
      "dev.zio" %% "zio-aws-s3" % Versions.zioAws,
      "dev.zio" %% "zio-aws-core" % Versions.zioAws,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
    )
  )

lazy val postgres = project
  .in(file("modules/postgres"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "torrent-postgres",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-prelude" % Versions.zioPrelude,
      "com.augustnagro" %% "magnum" % Versions.magnum,
      "org.postgresql" % "postgresql" % Versions.postgresql,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test,
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-prelude" % Versions.zioPrelude,
      "com.augustnagro" %% "magnum" % Versions.magnum,
      "org.postgresql" % "postgresql" % Versions.postgresql,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
    )
  )

lazy val pdf = project
  .in(file("modules/pdf"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "torrent-pdf",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-prelude" % Versions.zioPrelude,
      "org.scodec" %% "scodec-core" % Versions.scodecCore,
      "org.scodec" %% "scodec-bits" % Versions.scodecBits,
      "org.apache.pdfbox" % "pdfbox" % Versions.pdfbox,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test,
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-prelude" % Versions.zioPrelude,
      "org.scodec" %% "scodec-core" % Versions.scodecCore,
      "org.scodec" %% "scodec-bits" % Versions.scodecBits,
      "org.apache.pdfbox" % "pdfbox" % Versions.pdfbox,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
    )
  )

// Common MiMa settings
def mimaSettings(projectName: String) = Seq(
  mimaPreviousArtifacts := {
    val ver = version.value
    if (ver.endsWith("SNAPSHOT") || ver.contains("-")) Set.empty
    else Set(organization.value %% projectName % ver)
  },
  mimaReportBinaryIssues := Def.taskDyn {
    val ver = version.value
    if (ver.endsWith("SNAPSHOT") || ver.contains("-"))
      Def.task { () }
    else
      Def.task { mimaReportBinaryIssues.value }
  }.value
)


