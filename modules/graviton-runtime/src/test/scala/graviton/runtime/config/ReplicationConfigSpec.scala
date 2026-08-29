package graviton.runtime.config

import zio.test.*

object ReplicationConfigSpec extends ZIOSpecDefault:
  override def spec = suite("ReplicationConfig")(
    test("parses named failure-domain targets and validates quorum") {
      val result = for
        targets <- ReplicaTargetConfig.parseList("west|rack-a|/srv/a,east|rack-b|/srv/b")
        config  <- ReplicationConfig(targets, Some(2), writeQuorum = Some(2)).validate
      yield config

      assertTrue(
        result.exists(_.targets.map(_.name.value).toList == List("west", "east")),
        result.exists(_.targets.map(_.failureDomain.value).toSet == Set("rack-a", "rack-b")),
        result.exists(_.effectiveDesiredReplicas == 2),
        result.exists(_.effectiveWriteQuorum == 2),
      )
    },
    test("rejects malformed targets, duplicate names, and impossible quorum") {
      val malformed = ReplicaTargetConfig.parseList("missing-fields")
      val duplicate = ReplicaTargetConfig.parseList("same|rack-a|/a,same|rack-b|/b")
      val quorum    = for
        targets <- ReplicaTargetConfig.parseList("only|rack-a|/a")
        config  <- ReplicationConfig(targets, Some(1), writeQuorum = Some(2)).validate
      yield config

      assertTrue(malformed.isLeft, duplicate.isLeft, quorum.isLeft)
    },
  )
