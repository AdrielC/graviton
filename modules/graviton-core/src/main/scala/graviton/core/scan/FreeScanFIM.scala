package graviton.core.scan

/**
 * Integration layer between FreeScan and FIM (Free Invariant Monoidal) state.
 *
 * This module bridges the existing FreeScan (which uses Rec/named-tuples for state)
 * with the new FIM algebra for modular state composition.
 *
 * Key capabilities:
 * - Attach FIM state specifications to scans
 * - Compose state via FIM operators (⊗, imap)
 * - Interpret FIM specs into Rec at runtime
 * - Optimize state via algebraic rewrites
 *
 * Example:
 * {{{
 *   // Define state as FIM expression
 *   val stateSpec = FIM.counter("bytes") ⊗ FIM.flag("done")
 *
 *   // Create scan with FIM state
 *   val scan = FreeScan.withFIMState(stateSpec) { spec =>
 *     // Build scan using the state spec
 *     ???
 *   }
 *
 *   // Compose states modularly
 *   val scan2 = scan.extendState(FIM.counter("chunks"))
 * }}}
 */

/**
 * FreeScan extended with FIM state specification.
 *
 * This wraps a regular FreeScan and carries a FIM specification
 * that describes what state components are used and how they compose.
 */
final case class FreeScanWithFIM[F[_], G[_], I, O, S <: Rec](
  scan: FreeScan[F, G, I, O, S],
  stateSpec: FIM[S],
):

  /**
   * Extend state with additional component (monoidal product).
   */
  def extendState[T <: Rec](additional: FIM[T]): FreeScanWithFIM[F, G, I, O, Merge[S, T]] =
    val newSpec = stateSpec.product(additional).asInstanceOf[FIM[Merge[S, T]]]
    // For actual implementation, would need to modify the scan's step/init/flush
    // to handle the extended state. This is a placeholder.
    ???

  /**
   * Transform state type via isomorphism.
   */
  def imapState[T <: Rec](iso: Iso[S, T]): FreeScanWithFIM[F, G, I, O, T] =
    FreeScanWithFIM(
      scan.asInstanceOf[FreeScan[F, G, I, O, T]],
      stateSpec.imap(iso),
    )

  /**
   * Optimize the state specification.
   */
  def optimizeState: FreeScanWithFIM[F, G, I, O, S] =
    copy(stateSpec = FIMOptimize.simplify(stateSpec))

  /**
   * Extract labels used in this scan's state.
   */
  def stateLabels: Set[String] =
    FIMOptimize.extractLabels(stateSpec)

  /**
   * Eliminate unused state components.
   */
  def eliminateDeadState(usedLabels: Set[String]): FreeScanWithFIM[F, G, I, O, S] =
    copy(stateSpec = FIMOptimize.eliminateDeadState(stateSpec, usedLabels))

object FreeScanWithFIM:

  /**
   * Create a FreeScan with explicit FIM state specification.
   */
  def withFIMState[F[_], G[_], I, O, S <: Rec](
    spec: FIM[S]
  )(
    buildScan: FIM[S] => FreeScan[F, G, I, O, S]
  ): FreeScanWithFIM[F, G, I, O, S] =
    FreeScanWithFIM(buildScan(spec), spec)

  /**
   * Lift existing FreeScan with inferred FIM spec from Rec.
   *
   * This attempts to reconstruct a FIM spec from the Rec type.
   * Note: This is lossy - we can't recover the full FIM structure from Rec alone.
   */
  def fromFreeScan[F[_], G[_], I, O, S <: Rec](
    scan: FreeScan[F, G, I, O, S]
  ): FreeScanWithFIM[F, G, I, O, S] =
    // Extract initial state to infer structure
    val init = InitF.evaluate(extractInit(scan))
    val spec = inferFIMFromRec(init).asInstanceOf[FIM[S]]
    FreeScanWithFIM(scan, spec)

  /**
   * Extract init from a FreeScan (helper).
   */
  private def extractInit[F[_], G[_], I, O, S <: Rec](fs: FreeScan[F, G, I, O, S]): InitF[S] =
    fs match
      case FreeScan.Prim(init, _, _)  => init
      case FreeScan.Seq(left, right)  =>
        val leftInit  = extractInit(left)
        val rightInit = extractInit(right)
        InitF.map2(leftInit, rightInit)(rec.merge(_, _)).asInstanceOf[InitF[S]]
      case FreeScan.Dimap(base, _, _) => extractInit(base)
      case FreeScan.Par(a, b)         =>
        val aInit = extractInit(a)
        val bInit = extractInit(b)
        InitF.map2(aInit, bInit)(rec.merge(_, _)).asInstanceOf[InitF[S]]
      case FreeScan.Choice(l, r)      =>
        val lInit = extractInit(l)
        val rInit = extractInit(r)
        InitF.map2(lInit, rInit)(rec.merge(_, _)).asInstanceOf[InitF[S]]
      case FreeScan.Fanout(a, b)      =>
        val aInit = extractInit(a)
        val bInit = extractInit(b)
        InitF.map2(aInit, bInit)(rec.merge(_, _)).asInstanceOf[InitF[S]]

  /**
   * Infer FIM spec from a Rec value (best effort).
   *
   * This is inherently limited since Rec doesn't carry enough type information
   * to reconstruct the full FIM structure. We create a simple product of labeled components.
   */
  private def inferFIMFromRec(r: Rec): FIM[Rec] =
    // For EmptyTuple, return Unit
    if r == EmptyTuple then FIM.unit.asInstanceOf[FIM[Rec]]
    else
      // For non-empty, we'd need runtime reflection of the tuple structure
      // This is a simplified placeholder
      FIM.unit.asInstanceOf[FIM[Rec]]

/**
 * Extension methods to add FIM support to existing FreeScan.
 */
extension [F[_], G[_], I, O, S <: Rec](scan: FreeScan[F, G, I, O, S])

  /**
   * Attach a FIM state specification to this scan.
   */
  def withStateSpec(spec: FIM[S]): FreeScanWithFIM[F, G, I, O, S] =
    FreeScanWithFIM(scan, spec)

  /**
   * Infer FIM spec from the scan's state structure.
   */
  def inferStateSpec: FreeScanWithFIM[F, G, I, O, S] =
    FreeScanWithFIM.fromFreeScan(scan)

/**
 * Builder for creating scans with rich FIM state composition.
 */
object FreeScanBuilder:

  /**
   * Start building a scan with specified state.
   */
  def withState[S](spec: FIM[S]): PartialScan[S] =
    PartialScan(spec)

  /**
   * Start with stateless scan.
   */
  def stateless: PartialScan[Unit] =
    PartialScan(FIM.unit)

  final case class PartialScan[S](spec: FIM[S]):

    /**
     * Add state component (monoidal product).
     */
    def and[T](additional: FIM[T]): PartialScan[(S, T)] =
      PartialScan(spec ⊗ additional)

    /**
     * Transform state type.
     */
    def via[T](iso: Iso[S, T]): PartialScan[T] =
      PartialScan(spec.imap(iso))

    /**
     * Build the scan with step/init/flush functions.
     */
    def build[F[_], G[_], I, O, SR <: Rec](spec0: FIM[SR])(
      buildInit: FIM[SR] => InitF[SR],
      buildStep: FIM[SR] => BiKleisli[F, G, I, (SR, O)],
      buildFlush: FIM[SR] => (SR => G[Option[O]]),
    ): FreeScanWithFIM[F, G, I, O, SR] =
      val init  = buildInit(spec0)
      val step  = buildStep(spec0)
      val flush = buildFlush(spec0)
      val scan  = FreeScan
        .Prim(
          init.asInstanceOf[InitF[Rec]],
          step.asInstanceOf[BiKleisli[F, G, I, (Rec, O)]],
          flush.asInstanceOf[Rec => G[Option[O]]],
        )
        .asInstanceOf[FreeScan[F, G, I, O, SR]]
      FreeScanWithFIM(scan, spec0)

/**
 * Common FIM state patterns for scans.
 */
object ScanStates:

  /**
   * Byte counter state.
   */
  def byteCounter: FIM[Long] =
    FIM.counter("bytes")

  /**
   * Chunk/record counter.
   */
  def chunkCounter: FIM[Long] =
    FIM.counter("chunks")

  /**
   * Completion flag.
   */
  def doneFlag: FIM[Boolean] =
    FIM.flag("done", initial = false)

  /**
   * Error flag.
   */
  def errorFlag: FIM[Boolean] =
    FIM.flag("error", initial = false)

  /**
   * Buffer/accumulator of type A.
   */
  def buffer[A](zero: A): FIM[A] =
    FIM.accumulator("buffer", zero)

  /**
   * Hash state (SHA-256 digest).
   */
  def hashState: FIM[Array[Byte]] =
    FIM.labeled("hash")(Array.empty[Byte])

  /**
   * Alias in FIM object for convenience.
   */
  extension (fimObj: FIM.type) def hashState: FIM[Array[Byte]] = ScanStates.hashState

  /**
   * Combined: bytes + chunks + done.
   */
  def basicStats: FIM[((Long, Long), Boolean)] =
    (byteCounter ⊗ chunkCounter) ⊗ doneFlag

  /**
   * Named tuple version of basic stats.
   *
   * In full Scala 3 implementation, this would use NamedTuple:
   * (bytes = Long, chunks = Long, done = Boolean)
   */
  type BasicStatsRec = Rec

  def basicStatsRec: FIM[BasicStatsRec] =
    val spec = byteCounter ⊗ chunkCounter ⊗ doneFlag
    // Convert to Rec via interpreter
    spec.asInstanceOf[FIM[BasicStatsRec]]

/**
 * Optimization hints for FIM-based scans.
 */
object FIMHints:

  /**
   * Mark a state component as "hot" (frequently updated).
   *
   * Interpreters can use this hint to choose mutable Ref-based representation.
   */
  extension [S](spec: FIM[S])
    def hot: FIM[S] =
      // In full implementation, would wrap in a marker
      spec

  /**
   * Mark as "cold" (rarely updated).
   *
   * Interpreters can use immutable representation.
   */
  extension [S](spec: FIM[S])
    def cold: FIM[S] =
      spec

  /**
   * Mark as requiring atomic updates.
   */
  extension [S](spec: FIM[S])
    def atomic: FIM[S] =
      spec
