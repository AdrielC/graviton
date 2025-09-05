name := "dbcodegen"

libraryDependencies ++= Seq(
  "us.fatehi" % "schemacrawler-tools"       % "16.21.1",
  "us.fatehi" % "schemacrawler-postgresql"  % "16.21.1",
  "us.fatehi" % "schemacrawler-utility"     % "16.21.1",
  "us.fatehi" % "schemacrawler-api"         % "16.21.1",
  "org.postgresql" % "postgresql"           % "42.7.1"
)

Compile / run / fork := true

// local to tool module: allow warnings
ThisBuild / scalacOptions --= Seq("-Werror", "-Xfatal-warnings")


