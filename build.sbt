import cats.instances.set
enablePlugins(
  ZioSbtEcosystemPlugin,
  ZioSbtCiPlugin
)

import scala.sys.process.*
import sbtunidoc.ScalaUnidocPlugin.autoImport._
import sbt.io.Path

import sbt.librarymanagement.Artifact

ThisBuild / scalaVersion  := "3.7.3"
ThisBuild / organization  := "io.quasar"
ThisBuild / versionScheme := Some("semver-spec")
ThisBuild / name          := "graviton"
ThisBuild / turbo         := true

lazy val zioV            = "2.1.20"
lazy val zioPreludeV     = "1.0.0-RC41"
lazy val ironV           = "3.2.0"
lazy val zioSchemaV      = "1.7.4"
lazy val zioJsonV        = "0.6.2"
lazy val zioMetricsV     = "2.4.3"
lazy val zioCacheV       = "0.2.4"
lazy val zioRocksdbV     = "0.4.4"
lazy val zioConfigV      = "4.0.4"
lazy val zioHttpV        = "3.0.0"
lazy val testContainersV = "1.19.7"
lazy val zioLoggingV     = "2.2.4"
lazy val magnumV         = "2.0.0-M2"
lazy val postgresV       = "42.7.3"

lazy val generatePgSchemas = taskKey[Seq[java.io.File]](
  "Generate DB schemas and snapshot into modules/pg/src/main/scala/graviton/db"
)

// Task to bootstrap local Postgres and stream output via sbt logger
lazy val setUpPg = taskKey[Unit]("Bootstrap local Postgres (Podman) and stream logs")
lazy val autoBootstrapPg = settingKey[Boolean](
  "Run setUpPg automatically when sbt starts (controlled via GRAVITON_BOOTSTRAP_PG)"
)

lazy val docsSiteArchive = taskKey[java.io.File]("Bundle rendered documentation into a distributable zip")
lazy val docsSiteArtifact = Artifact("graviton-docs", "zip", "zip")
lazy val serveDocs = taskKey[Unit]("Serve documentation site locally")
// Custom tasks for docs
lazy val buildDocs = taskKey[Unit]("Build complete documentation site")
lazy val previewDocs = taskKey[Unit]("Preview documentation site")
lazy val installDocs = taskKey[Unit]("Install documentation dependencies")

lazy val MdocKeys = _root_.mdoc.MdocPlugin.autoImport

def envFlagEnabled(name: String): Boolean =
  sys.env
    .get(name)
    .exists { raw =>
      val normalized = raw.trim.toLowerCase(java.util.Locale.ROOT)
      normalized match {
        case "1" | "true" | "yes" | "on" => true
        case _                              => false
      }
    }

ThisBuild / setUpPg := {
  val log     = streams.value.log
  val workDir = (ThisBuild / baseDirectory).value

  val host       = (ThisBuild / pgHost).value
  val portString = (ThisBuild / pgPort).value
  val port       = scala.util.Try(portString.toInt).getOrElse(5432)
  val db         = (ThisBuild / pgDatabase).value
  val user       = (ThisBuild / pgUsername).value
  val password   = (ThisBuild / pgPassword).value

  def pgReachable(): Boolean = {
    // Use bash /dev/tcp check (same as ensure-postgres.sh)
    val cmd = Seq("bash", "-c", s"echo > /dev/tcp/${host}/${portString}")
    Process(cmd, workDir).!(ProcessLogger(_ => (), _ => ())) == 0
  }

  if (pgReachable()) {
    log.info(s"Postgres already reachable at $host:$port")
  } else {
    log.info("Postgres not reachable, running ensure-postgres.sh...")
    
    val cmd = Seq(
      "./scripts/ensure-postgres.sh",
      "--host", host,
      "--port", portString,
      "--db", db,
      "--user", user,
      "--password", password,
      "--engine", "auto"  // Docker → Podman → Native (with auto-install in CI)
    )
    
    // Run and capture output (export statements)
    val output = Process(cmd, workDir).!!(ProcessLogger(err => log.warn(err)))
    
    // Parse and set environment variables from output
    output.split("\n").foreach { line =>
      if (line.startsWith("export ")) {
        val parts = line.stripPrefix("export ").split("=", 2)
        if (parts.length == 2) {
          System.setProperty(parts(0), parts(1))
          log.info(s"Set ${parts(0)}=${parts(1)}")
        }
      }
    }
    
    if (pgReachable()) {
      log.info(s"Postgres is now reachable at $host:$port")
    } else {
      log.warn(s"ensure-postgres.sh completed but Postgres still not reachable at $host:$port")
    }
  }
}

ThisBuild / autoBootstrapPg := envFlagEnabled("GRAVITON_BOOTSTRAP_PG")

// Run setUpPg automatically on sbt startup
ThisBuild / onLoad := {
  val prev = (ThisBuild / onLoad).value
  val shouldBootstrap = (ThisBuild / autoBootstrapPg).value
  (state: State) => {
    val s1 = prev(state)
    if (shouldBootstrap) "setUpPg" :: s1 else s1
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
  .aggregate(core, db, fs, s3, tika, metrics, pg, docs)
  .settings(name := "graviton")

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name := "graviton-core"
  )
  .settings(commonSettings)

lazy val db = project
  .in(file("modules/db"))
  .settings(
    name := "graviton-db"
  )
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.augustnagro"   %% "magnum"     % magnumV,
      "com.augustnagro"   %% "magnumpg"   % magnumV,
      "com.augustnagro"   %% "magnumzio"  % magnumV,
    )
  )

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

lazy val dbcodegen = project
  .in(file("dbcodegen"))
  .settings(
    name    := "graviton-dbcodegen",
    runMain := Some("dbcodegen.DbMain"),
  )

lazy val pg = project
  .in(file("modules/pg"))
  .dependsOn(core, db, dbcodegen)
  .settings(
    name              := "graviton-pg",
    // on-demand schema generation snapshot directory (checked into VCS)
    Compile / unmanagedSourceDirectories += (
      baseDirectory.value / "src" / "main" / "scala" / "graviton" / "db"
    ),
    generatePgSchemas := {
      val log = streams.value.log
      log.info("Generating PG schemas...")

      val templatePath = (baseDirectory.value / "codegen" / "magnum.ssp").getAbsolutePath
      val outputPath   = (baseDirectory.value / "src" / "main" / "scala" / "graviton" / "pg" / "generated").getAbsolutePath

      log.info(s"Template: $templatePath")
      log.info(s"Output: $outputPath")

      // Set system properties before running
      val oldTemplate = System.getProperty("dbcodegen.template")
      val oldOut      = System.getProperty("dbcodegen.out")
      val oldJdbcUrl  = System.getProperty("dbcodegen.jdbcUrl")
      val oldUsername = System.getProperty("dbcodegen.username")
      val oldPassword = System.getProperty("dbcodegen.password")

      try {
        System.setProperty("dbcodegen.template", templatePath)
        System.setProperty("dbcodegen.out", outputPath)
        System.setProperty("dbcodegen.jdbcUrl", s"jdbc:postgresql://${pgHost.value}:${pgPort.value}/${pgDatabase.value}")
        System.setProperty("dbcodegen.username", pgUsername.value)
        System.setProperty("dbcodegen.password", pgPassword.value)

        // Run the main class
        (dbcodegen / Compile / runMain).toTask(" dbcodegen.DbMain").value

        log.info("Schema generation completed successfully")
        Seq(file(outputPath))
      } finally {
        // Restore old system properties
        if (oldTemplate != null) System.setProperty("dbcodegen.template", oldTemplate)
        else System.clearProperty("dbcodegen.template")

        if (oldOut != null) System.setProperty("dbcodegen.out", oldOut)
        else System.clearProperty("dbcodegen.out")

        if (oldJdbcUrl != null) System.setProperty("dbcodegen.jdbcUrl", oldJdbcUrl)
        else System.clearProperty("dbcodegen.jdbcUrl")

        if (oldUsername != null) System.setProperty("dbcodegen.username", oldUsername)
        else System.clearProperty("dbcodegen.username")

        if (oldPassword != null) System.setProperty("dbcodegen.password", oldPassword)
        else System.clearProperty("dbcodegen.password")
      }
    },
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
  .settings(
    Test / resourceGenerators += Def.task {
      val ddl     = baseDirectory.value / "ddl.sql"
      val target  = (Test / resourceManaged).value / "ddl.sql"
      IO.copyFile(ddl, target)
      Seq(target)
    }
  )

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
  .enablePlugins(MdocPlugin, WebsitePlugin)
  .dependsOn(core, fs, s3, tika, metrics, pg)
  .settings(
    websiteDir := (target.value / "website").toPath,
  )
  .settings(
    projectName := "Graviton",
    mainModuleName := "graviton",
    projectHomePage := "https://github.com/AdrielC/graviton",
    projectStage := ProjectStage.Development,
    docsVersioningScheme := WebsitePlugin.VersioningScheme.SemanticVersioning,
    MdocKeys.mdocIn := baseDirectory.value / "src" / "main" / "mdoc",
    MdocKeys.mdocVariables := Map(
      "VERSION" -> version.value,
      "SCALA_VERSION" -> scalaVersion.value,
      "GITHUB_ORG" -> "AdrielC",
      "GITHUB_REPO" -> "graviton"
    ),

    // Docs build pipeline
    installDocs := {
      val log = streams.value.log
      
      val webDir = websiteDir.value.toFile()

      {
        log.info("Creating new website scaffold...")
        Process(
          s"""npm init docusaurus@latest website classic --package-manager npm --skip-install""",
          webDir
        ).!
        
        Process("npm install", webDir).!
        Process("npm install prism-react-renderer@next prismjs @mdx-js/react d3 react-force-graph", webDir).!
        
        log.info("✓ Website dependencies installed")
      }
    },
    
    buildDocs := {
      val log = streams.value.log
      
      val webDir = websiteDir.value
      // 1. Install if needed
      if (!webDir.toFile.exists()) {
        installDocs.value
      } else {
        log.info("✓ Website dependencies already installed")
        installDocs.value
      }
      
      // 2. Copy Matrix theme CSS
      val cssSrc = baseDirectory.value / "css"
      val cssDst = (webDir / "src" / "css")
      if ((cssSrc / "custom.css").exists()) {
        IO.copyDirectory(cssSrc, cssDst.toFile())
        log.info("✓ Copied Matrix theme CSS")
      }
      
      // 3. Copy sidebars.js and package.json
      val sidebarsSrc = baseDirectory.value / "sidebars.js"
      val sidebarsDst = (webDir / "sidebars.js").toFile
      if (sidebarsSrc.exists()) {
        IO.copyFile(sidebarsSrc, sidebarsDst)
        log.info("✓ Copied sidebars.js")
      }

      val packageJsonSrc = baseDirectory.value / "package.json"
      val packageJsonDst = (webDir / "package.json").toFile
      if (packageJsonSrc.exists()) {
        IO.copyFile(packageJsonSrc, packageJsonDst)
        log.info("✓ Copied package.json")
      }
      
      // 4. Generate ScalaDoc
      (ScalaUnidoc / doc).value
      val apiSrc = (ScalaUnidoc / unidoc / target).value
      val apiDst = (webDir / "static" / "api").toFile
      if (apiSrc.exists()) {
        IO.delete(apiDst)
        IO.copyDirectory(apiSrc, apiDst)
        log.info("✓ Copied ScalaDoc to static/api/")
      }
      
      // 5. Write docusaurus.config.js
      val configFile = (webDir / "docusaurus.config.js").toFile
      val configContents =
        s"""const lightCodeTheme = require('prism-react-renderer/themes/github');
           |const darkCodeTheme = require('prism-react-renderer/themes/vsDark');
           |
           |module.exports = {
           |  title: 'Graviton',
           |  url: 'https://adrielc.github.io',
           |  baseUrl: '/graviton/',
           |  organizationName: 'io.quasar',
           |  projectName: 'graviton',
           |  onBrokenLinks: 'ignore',
           |  onBrokenMarkdownLinks: 'warn',
           |  favicon: 'img/favicon.ico',
           |  i18n: { defaultLocale: 'en', locales: ['en'] },
           |  themeConfig: {
           |    colorMode: { defaultMode: 'dark', respectPrefersColorScheme: false },
           |    navbar: {
           |      title: 'Graviton',
           |      items: [
           |        { to: '/docs/', label: 'Docs', position: 'left' },
           |        { href: '/graviton/api/', label: 'API', position: 'left' },
           |        { to: '/vis', label: 'Visualize', position: 'left' },
           |        { href: 'https://github.com/AdrielC/graviton', label: 'GitHub', position: 'right' }
           |      ]
           |    },
           |    prism: {
           |      additionalLanguages: ['scala'],
           |      theme: lightCodeTheme,
           |      darkTheme: darkCodeTheme
           |    }
           |  },
           |  presets: [
           |    [
           |      'classic',
           |      {
           |        docs: {
           |          routeBasePath: 'docs',
           |          path: 'docs',
           |          sidebarPath: './sidebars.js'
           |        },
           |        blog: false,
           |        theme: {
           |          customCss: './src/css/custom.css'
           |        }
           |      }
           |    ]
           |  ]
           |};""".stripMargin
      IO.write(configFile, configContents)
      log.info("✓ Wrote docusaurus.config.js")
      
      // 6. Write Prism Scala language support
      val prismDir = (webDir / "src" / "theme")
      java.nio.file.Files.createDirectories(prismDir)
      val prismFile = (prismDir / "prism-include-languages.js").toFile
      val prismJs =
        """module.exports = function prismIncludeLanguages(PrismObject) {
          |  // Scala language definition
          |  PrismObject.languages.scala = {
          |    'triple-quoted-string': {
          |      pattern: /\"\"\"[\\s\\S]*?\"\"\"/,
          |      alias: 'string'
          |    },
          |    'string': {
          |      pattern: /("|')(?:\\.|(?!\1)[^\\\r\n])*\1/,
          |      greedy: true
          |    },
          |    'keyword': /\b(?:abstract|case|catch|class|def|do|else|extends|final|finally|for|forSome|if|implicit|import|lazy|match|new|null|object|override|package|private|protected|return|sealed|self|super|this|throw|trait|try|type|val|var|while|with|yield)\b/,
          |    'builtin': /\b(?:String|Int|Long|Short|Byte|Boolean|Double|Float|Char|Any|AnyRef|AnyVal|Unit|Nothing)\b/,
          |    'number': /\b0x(?:[\da-f]*\.)?[\da-f]+|(?:\d+\.?\d*|\.\d+)(?:e\d+)?[dfl]?/i,
          |    'symbol': /'[^\d\s\\]\w*/,
          |    'function': /\b(?!(?:if|while|for|return|else)\b)[a-z_]\w*(?=\s*[({])/i,
          |    'punctuation': /[{}[\];(),.:]/,
          |    'operator': /<-|=>|\b(?:->|<-|<:|>:|\|)\b|[+\-*/%&|^!=<>]=?|\b(?:and|or)\b/
          |  };
          |};""".stripMargin
      IO.write(prismFile, prismJs)
      log.info("✓ Added Scala syntax highlighting")
      
      // 7. Create interactive visualization component
      val componentsDir = webDir / "src" / "components"
      IO.createDirectory(componentsDir.toFile)
      val visFile = (componentsDir / "BinaryStorageVis.js").toFile
      val visJs =
        """import React, { useEffect, useRef } from 'react';
          |
          |export default function BinaryStorageVis() {
          |  const canvasRef = useRef(null);
          |
          |  useEffect(() => {
          |    const canvas = canvasRef.current;
          |    if (!canvas) return;
          |    const ctx = canvas.getContext('2d');
          |    const W = canvas.width;
          |    const H = canvas.height;
          |
          |    const nodes = [
          |      { id: 'manifest', group: 1, x: W * 0.2, y: H * 0.5 },
          |      { id: 'block1', group: 2, x: W * 0.45, y: H * 0.3 },
          |      { id: 'block2', group: 2, x: W * 0.45, y: H * 0.5 },
          |      { id: 'block3', group: 2, x: W * 0.45, y: H * 0.7 },
          |      { id: 'store1', group: 3, x: W * 0.75, y: H * 0.35 },
          |      { id: 'store2', group: 3, x: W * 0.75, y: H * 0.65 },
          |    ];
          |    const links = [
          |      ['manifest', 'block1'],
          |      ['manifest', 'block2'],
          |      ['manifest', 'block3'],
          |      ['block1', 'store1'],
          |      ['block2', 'store1'],
          |      ['block2', 'store2'],
          |      ['block3', 'store2'],
          |    ];
          |
          |    const colorFor = g => (g === 1 ? '#00ff41' : g === 2 ? '#3fb950' : '#58d46e');
          |
          |    function draw() {
          |      ctx.clearRect(0, 0, W, H);
          |      ctx.strokeStyle = '#21262d';
          |      ctx.lineWidth = 2;
          |      links.forEach(([a, b]) => {
          |        const na = nodes.find(n => n.id === a);
          |        const nb = nodes.find(n => n.id === b);
          |        ctx.beginPath();
          |        ctx.moveTo(na.x, na.y);
          |        ctx.lineTo(nb.x, nb.y);
          |        ctx.stroke();
          |      });
          |      nodes.forEach(n => {
          |        ctx.fillStyle = colorFor(n.group);
          |        ctx.beginPath();
          |        ctx.arc(n.x, n.y, 10, 0, Math.PI * 2);
          |        ctx.fill();
          |        ctx.fillStyle = '#e6edf3';
          |        ctx.font = '12px sans-serif';
          |        ctx.fillText(n.id, n.x + 12, n.y + 4);
          |      });
          |    }
          |
          |    draw();
          |  }, []);
          |
          |  return (
          |    <div style={{ height: 520, background: '#0d1117', padding: '10px', border: '1px solid #21262d' }}>
          |      <canvas ref={canvasRef} width={900} height={500} style={{ width: '100%', maxWidth: '100%' }} />
          |    </div>
          |  );
          |}""".stripMargin
      IO.write(visFile, visJs)
      log.info("✓ Added interactive visualization component")

      // 7.5. Ensure homepage redirects to /docs/
      val pagesDir = (webDir / "src" / "pages")
      java.nio.file.Files.createDirectories(pagesDir)
      val indexPage = (pagesDir / "index.js").toFile
      val indexJs =
        """import React, { useEffect } from 'react';
          |import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
          |
          |export default function Home() {
          |  const { siteConfig } = useDocusaurusContext();
          |  useEffect(() => {
          |    const base = (siteConfig && siteConfig.baseUrl) ? siteConfig.baseUrl : '/';
          |    window.location.replace(base + 'docs/');
          |  }, []);
          |  return null;
          |}
          |""".stripMargin
      IO.write(indexPage, indexJs)
      log.info("✓ Added homepage redirect to /docs/")
      
      // 8. Run mdoc
      (Compile / mdoc).toTask("").value
      log.info("✓ Generated mdoc content")
      
      // 9. Build website
      Process("npm run build", Some(webDir.toFile)).!
      log.info("✓ Built website")
      
      log.info(s"Website built at: ${webDir}/build")
    },
    
    previewDocs := {
      val log = streams.value.log
      val webDir = websiteDir.value
      
      buildDocs.value
      
      log.info("Starting preview server...")
      Process("npm run serve", Some((webDir).toFile)).!
    }
  )
  
  

// Convenience aliases for database operations
addCommandAlias(
  "genPg",
  "dbcodegen/runMain dbcodegen.DbMain " +
    "-Ddbcodegen.template=modules/pg/codegen/magnum.ssp " +
    "-Ddbcodegen.out=modules/pg/src/main/scala/graviton/db",
)

addCommandAlias("bootstrapPg", "setUpPg")

Global / excludeLintKeys ++= Set(
  ThisBuild / pgDatabase,
  ThisBuild / pgPassword,
  ThisBuild / pgUsername,
  docs / Compile / publishArtifact,
  docs / mainModuleName,
  docs / projectStage,
)

addCommandAlias(
  "inspectPgConstraints",
  "dbcodegen/runMain dbcodegen.DbMain " +
    "-Ddbcodegen.inspect-only=true",
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
