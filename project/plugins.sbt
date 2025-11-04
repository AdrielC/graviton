val zioSbtVersion = "0.4.0-alpha.32"

addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.5.2")
// addSbtPlugin("com.github.cornerman" % "sbt-db-codegen" % "0.5.2")

addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-ci"        % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-website"   % zioSbtVersion)

// Semantic versioning from git tags
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.0.1")
addSbtPlugin("com.thesamet"  % "sbt-protoc" % "1.0.6")

addSbtPlugin("ch.epfl.scala"  % "sbt-scalafix"  % "0.14.0")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"  % "2.5.5")
addSbtPlugin("org.scoverage"  % "sbt-scoverage" % "2.3.1")
addSbtPlugin("org.typelevel"  % "sbt-tpolecat"  % "0.5.2")

resolvers ++= Resolver.sonatypeOssRepos("public")

// Scala.js for frontend
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.17.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

resolvers ++= Resolver.sonatypeOssRepos("public")

// Resolve eviction between transitive plugin deps requiring different
// scala-parser-combinators versions (1.x vs 2.x) in the build definition
ThisBuild / libraryDependencySchemes +=
  ("org.scala-lang.modules" %% "scala-parser-combinators" % "always")
