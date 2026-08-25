// Version is computed from git tags by sbt-dynver
// Releases are computed from annotated vX.Y.Z tags by sbt-dynver.
ThisBuild / version := dynverGitDescribeOutput.value
  .mkVersion(versionFmt, fallbackVersion(dynverCurrentDate.value))

ThisBuild / dynverSonatypeSnapshots := true

def versionFmt(out: sbtdynver.GitDescribeOutput): String = {
  val tag = out.ref.dropPrefix
  if (out.isCleanAfterTag) tag
  else s"$tag+${out.commitSuffix.distance}-${out.commitSuffix.sha}"
}

def fallbackVersion(d: java.util.Date): String = s"0.1.0-${sbtdynver.DynVer.timestamp(d)}-SNAPSHOT"
