ThisBuild / scalaVersion := "3.7.2"
ThisBuild / organization := "io.quasar"

lazy val zioV   = "2.1.20"
lazy val ironV  = "3.2.0"

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % zioV,
    "dev.zio" %% "zio-streams" % zioV,
    "io.github.iltotore" %% "iron" % ironV,
    "dev.zio" %% "zio-test" % zioV % Test,
    "dev.zio" %% "zio-test-sbt" % zioV % Test,
    "dev.zio" %% "zio-test-magnolia" % zioV % Test
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

lazy val root = (project in file(".")).aggregate(core, filesystem, s3, tika)
  .settings(name := "graviton")

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name := "graviton-core"
  )
  .settings(commonSettings)

lazy val filesystem = project
  .in(file("modules/filesystem"))
  .dependsOn(core)
  .settings(
    name := "graviton-filesystem"
  )
  .settings(commonSettings)

lazy val s3 = project
  .in(file("modules/s3"))
  .dependsOn(core)
  .settings(
    name := "graviton-s3",
    libraryDependencies += "software.amazon.awssdk" % "s3" % "2.25.59"
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
