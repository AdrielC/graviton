package graviton.core.scan

import kyo.*

/**
 * Optional Scan helpers that embed `kyo.Parse` programs.
 *
 * These scans produce *parser effects* as outputs (they do not execute the parser).
 * Callers can interpret the resulting `A < Abort[ParseFailed]` values in a Kyo runtime.
 */
object KyoParseScans:

  /** Produce a Kyo parse program that parses an integer from the given input text. */
  def int: FreeScan[Prim, String, Int < Abort[ParseFailed]] =
    FS.map[String, Int < Abort[ParseFailed]] { input =>
      Parse.run(input)(Parse.int)
    }
