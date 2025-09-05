name := "dbcodegen"

lazy val scalateV = "1.10.1"
lazy val postgresV = "42.7.1"
lazy val schemacrawlerV = "16.27.1"

libraryDependencies ++= Seq(
  "us.fatehi" % "schemacrawler-tools"       % schemacrawlerV,
  "us.fatehi" % "schemacrawler-postgresql"  % schemacrawlerV,
  "us.fatehi" % "schemacrawler-utility"     % schemacrawlerV,
  "us.fatehi" % "schemacrawler-api"         % schemacrawlerV,
  "us.fatehi" % "schemacrawler-sqlite"     % schemacrawlerV,
  "us.fatehi" % "schemacrawler-mysql"      % schemacrawlerV,
  "org.postgresql" % "postgresql"           % postgresV,
  "org.flywaydb"          % "flyway-core"              % "10.6.0",
  // "mysql"                 % "mysql-connector-java"     % "8.0.33",
  //   "org.mariadb.jdbc"      % "mariadb-java-client"      % "3.1.2",A
  "org.slf4j" % "slf4j-simple" % "2.0.16", // Better logging output control
  "org.scalatra.scalate" %% "scalate-core" % scalateV exclude("org.scala-lang.modules", "scala-collection-compat_2.13"),
  "org.scalatra.scalate" %% "scalate-util" % scalateV exclude("org.scala-lang.modules", "scala-collection-compat_2.13")
)

// Exclude conflicting cross-version dependencies globally for this module
excludeDependencies ++= Seq(
  ExclusionRule("org.scala-lang.modules", "scala-collection-compat_2.13")
)

Compile / run / fork := true

// Configure logging to reduce noise and use proper output streams
Compile / run / javaOptions ++= Seq(
  "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", // Reduce SchemaCrawler noise
  "-Dorg.slf4j.simpleLogger.showDateTime=false",
  "-Dorg.slf4j.simpleLogger.showThreadName=false", 
  "-Dorg.slf4j.simpleLogger.showLogName=false",
  "-Dorg.slf4j.simpleLogger.showShortLogName=true",
  "-Djava.util.logging.config.file=", // Disable java.util.logging
  "-Djava.util.logging.SimpleFormatter.format=[%1$tH:%1$tM:%1$tS] %4$s: %5$s%6$s%n"
)

// local to tool module: allow warnings
ThisBuild / scalacOptions --= Seq("-Werror", "-Xfatal-warnings")