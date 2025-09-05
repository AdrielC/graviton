addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.14"

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.3")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.12.0")
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")
addSbtPlugin("com.github.cb372" % "sbt-explicit-dependencies" % "0.2.16")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.2")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.0")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.5.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.0")

// TypeLevel plugins - keep versions in sync
addSbtPlugin("org.typelevel" % "sbt-typelevel-site" % "0.7.7")
addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.1")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.3.2")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.16.0")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.4")


addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.16")
// sbt bloop
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "2.0.12")

libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "2.3"

resolvers ++= Resolver.sonatypeOssRepos("snapshots")

dependencyOverrides += "ch.epfl.scala" % "scalafix-interfaces" % "0.14.3+49-6d81fdd0-SNAPSHOT"