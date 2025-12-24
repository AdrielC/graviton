package graviton.runtime.legacy

import java.nio.file.Path

final case class LegacyRepo(name: String, root: Path)

final case class LegacyRepos(repos: List[LegacyRepo]):
  lazy val byName: Map[String, LegacyRepo] = repos.map(r => r.name -> r).toMap
