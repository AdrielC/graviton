package graviton.core.scan

import graviton.core.bytes.{Digest, HashAlgo, Hasher}
import zio.Chunk

/**
 * A maximally-composed (but still practical) ingest telemetry scan.
 *
 * - Designed for Graviton/Quasar ingest streams that operate on `Chunk[Byte]`
 * - Emits one [[IngestTelemetryEvent]] per input chunk, plus a final event on `flush`
 * - Uses all Scan operators: `>>>`, `dimap`, `|||`, `+++`, `&&&`, `first`, `second`, `labelled`
 *
 * Notes:
 * - This scan is intentionally **non-throwing**: hash initialization/digest failures are surfaced as `Left(String, ...)`.
 */
object IngestTelemetryScan:

  sealed trait IngestTelemetryEvent derives CanEqual
  object IngestTelemetryEvent:
    final case class Step(
      route: String,
      ordinal: Long,
      bytes: Long,
      chunks: Long,
      chunkLen: Int,
      routeLen: Int,
      mimeHint: Option[String],
      halted: Boolean,
    ) extends IngestTelemetryEvent

    final case class Final(
      bytes: Long,
      chunks: Long,
      mimeHint: Option[String],
      sha256: Either[String, Digest],
    ) extends IngestTelemetryEvent

  /** Internal state (no casts). */
  final case class State(
    bytes: Long,
    chunks: Long,
    ordinal: Long,
    sniff: Chunk[Byte],
    halt: Boolean,
    hasher: Either[String, Hasher],
  )

  private def sniffMime(prefix: Chunk[Byte]): Option[String] =
    if prefix.startsWith(Chunk[Byte](0x25, 0x50, 0x44, 0x46)) then Some("application/pdf") // %PDF
    else if prefix.startsWith(Chunk[Byte](0x50, 0x4b, 0x03, 0x04)) then Some("application/zip") // PK..
    else if prefix.startsWith(Chunk[Byte](0x1f.toByte, 0x8b.toByte)) then Some("application/gzip") // gzip
    else None

  private sealed trait CoreOut derives CanEqual
  private object CoreOut:
    final case class Step0(
      route: String,
      ordinal: Long,
      bytes: Long,
      chunks: Long,
      mimeHint: Option[String],
      halted: Boolean,
    ) extends CoreOut

    final case class Final0(
      bytes: Long,
      chunks: Long,
      mimeHint: Option[String],
      sha256: Either[String, Digest],
    ) extends CoreOut

  /**
   * Build the scan.
   *
   * @param maxBytes if total bytes exceed this, `halted=true` is emitted (scan keeps running; caller decides what to do)
   * @param sniffBytes how many leading bytes to keep for mime sniffing
   * @param largeCutoff bytes >= this are routed as "large"
   */
  def apply(
    maxBytes: Long,
    sniffBytes: Int = 16,
    largeCutoff: Int = 1024 * 1024,
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ): Scan[Chunk[Byte], IngestTelemetryEvent, State, Any] =

    // --- routing using ||| -------------------------------------------------
    val classify: Scan[Chunk[Byte], Either[Chunk[Byte], Chunk[Byte]], Scan.NoState, Any] =
      Scan.pure { chunk =>
        if chunk.length >= math.max(1, largeCutoff) then Right(chunk) else Left(chunk)
      }

    val small: Scan[Chunk[Byte], (Chunk[Byte], String), Scan.NoState, Any] =
      Scan.pure(chunk => (chunk, "small"))

    val large: Scan[Chunk[Byte], (Chunk[Byte], String), Scan.NoState, Any] =
      Scan.pure(chunk => (chunk, "large"))

    val tagRoute: Scan[Either[Chunk[Byte], Chunk[Byte]], (Chunk[Byte], String), Scan.NoState, Any] =
      (small ||| large).map(_.fold(identity, identity))

    val tagged: Scan[Chunk[Byte], (Chunk[Byte], String), Scan.NoState, Any] =
      classify >>> tagRoute

    // Use second (route normalization) + first (no-op identity on bytes) just to exercise tuple lifting.
    val normalizeRoute: Scan[(Chunk[Byte], String), (Chunk[Byte], String), Scan.NoState, Any] =
      Scan.pure[String, String](_.toLowerCase).second[Chunk[Byte]]

    val idBytesOnFirst: Scan[(Chunk[Byte], String), (Chunk[Byte], String), Scan.NoState, Any] =
      Scan.id[Chunk[Byte]].first[String]

    // --- core stateful fold ------------------------------------------------
    val init: State =
      State(
        bytes = 0L,
        chunks = 0L,
        ordinal = 0L,
        sniff = Chunk.empty,
        halt = false,
        hasher = Hasher.hasher(algo, None),
      )

    val core: Scan[(Chunk[Byte], String), CoreOut, State, Any] =
      Scan.fold[(Chunk[Byte], String), CoreOut, State](init) { (s, in) =>
        val (chunk, route) = in

        // counters
        val nextOrdinal = s.ordinal + 1L
        val nextChunks  = s.chunks + 1L
        val nextBytes   = s.bytes + chunk.length.toLong

        // sniff prefix buffer
        val nextSniff =
          if s.sniff.length >= sniffBytes then s.sniff
          else (s.sniff ++ chunk).take(math.max(1, sniffBytes))

        // hasher
        val nextHasher: Either[String, Hasher] =
          s.hasher match
            case Right(h)       =>
              val _ = h.update(chunk)
              Right(h)
            case left @ Left(_) => left

        val halted = s.halt || (maxBytes > 0 && nextBytes > maxBytes)

        val nextState =
          s.copy(
            bytes = nextBytes,
            chunks = nextChunks,
            ordinal = nextOrdinal,
            sniff = nextSniff,
            halt = halted,
            hasher = nextHasher,
          )

        val out =
          CoreOut.Step0(
            route = route,
            ordinal = nextOrdinal,
            bytes = nextBytes,
            chunks = nextChunks,
            mimeHint = sniffMime(nextSniff),
            halted = halted,
          )

        (nextState, out)
      } { s =>
        val digest   = s.hasher.flatMap(_.digest)
        val finalOut =
          CoreOut.Final0(
            bytes = s.bytes,
            chunks = s.chunks,
            mimeHint = sniffMime(s.sniff),
            sha256 = digest,
          )
        (s, Chunk.single(finalOut))
      }

    // --- compose lens computation using +++ --------------------------------
    val lens0: Scan[(Chunk[Byte], String), (Int, Int), Scan.NoState, Any] =
      Scan.pure[Chunk[Byte], Int](_.length) +++ Scan.pure[String, Int](_.length)

    val lens: Scan[(Chunk[Byte], String), (Int, Int), Scan.NoState, Any] =
      new Scan[(Chunk[Byte], String), (Int, Int), Scan.NoState, Any]:
        def init(): Scan.NoState =
          lens0.init()

        def step(state: Scan.NoState, in: (Chunk[Byte], String)): (Scan.NoState, (Int, Int)) =
          lens0.step(state, in)

        def flush(state: Scan.NoState): (Scan.NoState, Chunk[(Int, Int)]) =
          // Important: emit one tail element so `&&&` can preserve `core`'s Final.
          (state, Chunk.single((0, 0)))

    // --- combine using &&& (record output) ---------------------------------
    val combined =
      core.labelled["event"] &&& lens.labelled["lens"]

    // Finally: full pipeline from raw bytes to events
    (tagged >>> normalizeRoute >>> idBytesOnFirst >>> combined).map { r =>
      val (chunkLen, routeLen) = r.right.value
      r.left.value match
        case CoreOut.Step0(route, ordinal, bytes, chunks, mimeHint, halted) =>
          IngestTelemetryEvent.Step(
            route = route,
            ordinal = ordinal,
            bytes = bytes,
            chunks = chunks,
            chunkLen = chunkLen,
            routeLen = routeLen,
            mimeHint = mimeHint,
            halted = halted,
          )
        case CoreOut.Final0(bytes, chunks, mimeHint, sha256)                =>
          IngestTelemetryEvent.Final(
            bytes = bytes,
            chunks = chunks,
            mimeHint = mimeHint,
            sha256 = sha256,
          )
    }
