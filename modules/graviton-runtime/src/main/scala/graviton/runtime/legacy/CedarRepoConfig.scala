package graviton.runtime.legacy

import java.nio.file.Path

final case class CedarRepo(name: String, root: Path)

final case class CedarRepos(repos: List[CedarRepo]):
  lazy val byName: Map[String, CedarRepo] = repos.map(r => r.name -> r).toMap
