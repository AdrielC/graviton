package graviton.core.scan

import kyo.*
import kyo.Record.`~`
import kyo.Tag.given

/**
 * "Sicko mode" Kyo-first Scan DSL.
 *
 * - Uses **kyo.Pipe** as the Scan representation
 * - Uses **kyo.Record** (joined named tuples) for structured outputs
 * - Embeds **kyo.Parse** directly as part of the streaming effect set
 *
 * This is intentionally additive: it doesn't break the existing ZIO-based `FreeScan` runner.
 */
object KyoSickoScan:

  type Scan[A, B, S] = Pipe[A, B, S]

  def id[A](using Tag[A]): Pipe[A, A, Any] =
    Pipe.identity[A]

  extension [A, B, S](scan: Pipe[A, B, S])

    infix def >>>[C, S2](next: Pipe[B, C, S2])(using Tag[B], Tag[C]): Pipe[A, C, S & S2] =
      scan.join(next)

    inline def asField[Label <: String & Singleton](using ValueOf[Label]): Pipe[A, Record[Label ~ B], S] =
      scan.mapPure[B, Record[Label ~ B]] { b =>
        (Record.empty & (summon[ValueOf[Label]].value ~ b)).asInstanceOf[Record[Label ~ B]]
      }

  object parse:

    /** Parse an integer from each input string. */
    def int: Pipe[String, Int, Abort[ParseFailed]] =
      Pipe.identity[String].map { (s: String) =>
        Parse.run(s)(Parse.int)
      }

    /**
     * Parse a hex chunk-size prefix (e.g. `"A;ext=1"` -> 10).
     * Stops at the first `;` (if present) and parses hex digits.
     */
    def hexChunkSize: Pipe[String, Int, Abort[ParseFailed]] =
      Pipe.identity[String].map { (s: String) =>
        val hexPart   = s.takeWhile(_ != ';')
        val hexDigits =
          Parse.readWhile((c: Char) => c.isDigit || ('a' <= c && c <= 'f') || ('A' <= c && c <= 'F'))

        Parse.run(hexPart.trim)(hexDigits).map { digits =>
          Integer.parseInt(kyo.Text.show(digits), 16)
        }
      }
