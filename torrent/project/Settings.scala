import sbt._
import sbt.Keys._
import Dependencies.Versions
import BuildHelper.gitBaseUrl
import BuildHelper.gitBaseUrl

object Settings {

  lazy val publishSettings = {
      val internalRepo = url(s"$gitBaseUrl/api/packages/tybera/maven")
      Seq(
        pomIncludeRepository := { _ => false },
        publishMavenStyle := true,
        publishTo := {
          if (isSnapshot.value)
            Some("gitea-snapshots".at(internalRepo + "/snapshots").withAllowInsecureProtocol(true))
          else
            Some("gitea-releases".at(internalRepo + "/releases").withAllowInsecureProtocol(true))
        },
        homepage := Some(internalRepo),
        scmInfo := Some(
          ScmInfo(
            internalRepo,
            s"scm:git:$gitBaseUrl/tybera/torrent.git"
          )
        ),
        developers := List(
          Developer(
            id = "acasellas",
            name = "Adriel Casellas",
            email = "acasellas@tybera.com",
            url = url("http://git.tybera.net/acasellas")
          )
        )
    )
  }
}
