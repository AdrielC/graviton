package graviton.server.console

import zio.Config

/** The operator console is opt-in because its local workflow is intentionally unauthenticated. */
final case class ConsoleConfig(enabled: Boolean, allowRemoteBinding: Boolean)

object ConsoleConfig:
  val Default: ConsoleConfig = ConsoleConfig(enabled = false, allowRemoteBinding = false)

  val config: Config[ConsoleConfig] =
    (Config.boolean("enabled").withDefault(Default.enabled) ++
      Config.boolean("allow-remote-binding").withDefault(Default.allowRemoteBinding))
      .map(ConsoleConfig.apply)
      .nested("console")
      .nested("graviton")
