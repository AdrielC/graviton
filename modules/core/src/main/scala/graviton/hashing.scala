package graviton

import zio.*
import zio.stream.*
import java.security.MessageDigest
import io.github.rctcwyvrn.blake3.Blake3
import graviton.GravitonError.{ChunkerFailure, HashingFailure}
import graviton.core.model.Block

import graviton.domain.HashBytes
import scodec.bits.ByteVector
import zio.prelude.{NonEmptySortedSet, NonEmptySortedMap, Hash as _, *}

import graviton.core.{toNonEmptyChunk, mapZIOSortedMap, mapZIO, given}

type HashOp[-R, +A] = ZIO[R, HashingFailure, A]

/**
 * Helpers for computing hashes over byte streams. Supports both SHA variants
 * (for FIPS deployments) and Blake3. Hashes are exposed via [[Hash]] which
 * captures the algorithm alongside the digest bytes.
 */
object Hashing:

  type HashAlgos = NonEmptySortedMap[HashAlgorithm, Option[MessageDigest]]

  object ref:

    val defaultHashAlgorithmRef: FiberRef[HashAlgos] = 
      Unsafe.unsafely:
        FiberRef.unsafe.make(
          initial = NonEmptySortedMap(HashAlgorithm.default -> None),
          fork = (a: HashAlgos) => a.mapValues(n => n.fold(None)(
            md => Some(md.clone().asInstanceOf[MessageDigest]))
          ),
          join = (a, b) => a ++ b
        )

    def getHashAlgos: UIO[HashAlgos] =
      defaultHashAlgorithmRef.get

    def getHashAlgo: UIO[HashAlgorithm] =
      getHashAlgos.map(_.head._1)

    def locallyWith(algos: HashAlgos): [R, E, A] => (HashAlgos => ZIO[R, E, A]) => ZIO[R, E, A] =
      [R, E, A] => 
        (f: HashAlgos => ZIO[R, E, A]) => 
          defaultHashAlgorithmRef.locally(algos):
            defaultHashAlgorithmRef.getWith(f)

    def locally[R, E, A](algos: HashAlgos): ZIO[R, E, A] => ZIO[R, E, A] =
      defaultHashAlgorithmRef.locally(algos)(_: ZIO[R, E, A])
    
    def addHashAlgo(algo: HashAlgorithm, algos: HashAlgorithm*): UIO[HashAlgos] =
      defaultHashAlgorithmRef.updateAndGet(
        _ ++ NonEmptySortedMap(algo -> None, algos.map(algo => algo -> None)*)
      )
  
  end ref

  /** Create an incremental hasher for the chosen algorithm. */
  def getHashers: HashOp[Scope, NonEmptySortedMap[HashAlgorithm, Hasher]] =
    ref.getHashAlgos.flatMap:
      algos => 
        val algs = NonEmptyChunk.fromIterableOption(algos.toList).get
        for 
          m <- algs
          .mapZIOPar(algo => algo._2.fold(
            hasher(NonEmptySortedMap(algo._1 -> None)).map(algo._1 -> _))(
            md => hasher(NonEmptySortedMap(algo._1 -> Some(md))).map(algo._1 -> _)
          ))
          .map(c => NonEmptySortedMap.fromNonEmptyChunk(c))
          .mapError(hashingFailure(algs.head._1))
        yield m
  
  /** Create an incremental hasher for the chosen algorithm. */
  def hasher(algo: NonEmptySortedMap[HashAlgorithm, Option[MessageDigest]]): HashOp[Scope, Hasher] =
    Hasher.messageDigest(algo)

  /** Create an incremental hasher for the chosen algorithm. */
  def hasher: HashOp[Scope, Hasher] =
    Hashing.ref.getHashAlgos.flatMap(algo => Hasher.messageDigest(algo))


  private def wrapHashing[A](algo: HashAlgorithm)(effect: Task[A]): HashOp[Any, A] =
    effect.mapError(hashingFailure(algo))

  private[graviton] def hashingFailure(algo: HashAlgorithm)(err: Throwable): HashingFailure =
    HashingFailure(algo, Option(err.getMessage).getOrElse(err.toString), Some(err))

  private def chunkerFailure(err: Throwable): ChunkerFailure =
    ChunkerFailure(Option(err.getMessage).getOrElse(err.toString), Some(err))

  /** Compute the digest of an entire byte stream using the configured default algorithm. */
  // def compute(stream: Bytes): HashOp[Any, HashBytes] =
  //   ref.getHashAlgo.flatMap(algo => compute(stream, algo))

  /** Compute the digest of an entire byte stream for the provided algorithm. */
  def compute(stream: Bytes): HashOp[Any, Hash.MultiHash] =
    ZIO.scoped:
      ref.getHashAlgos.flatMap: algo =>
        hasher(algo).flatMap { h =>
        for
          _ <- wrapHashing(algo.head._1)(stream.runForeachChunk(h.update))
          (digest: Hash.MultiHash) <- h.digest
        yield digest
      }

  def compute(stream: Bytes, algo: HashAlgorithm): HashOp[Any, Hash.MultiHash] =
    ZIO.scoped:
        hasher(NonEmptySortedMap(algo -> None)).flatMap { h =>
        for
          _ <- wrapHashing(algo)(stream.runForeachChunk(h.update))
          (digest: Hash.MultiHash) <- h.digest
        yield digest
      }

  /** A sink that consumes a byte stream and yields its digest. */
  def sink(
    algo: HashAlgorithm
  ): ZSink[Any, ChunkerFailure, Byte, Nothing, Hash.MultiHash] =
    ZSink.unwrapScoped {
      hasher(NonEmptySortedMap(algo -> None))
      .mapError(chunkerFailure).map { h =>
        ZSink
          .foreachChunk[Any, ChunkerFailure, Byte](chunk => h.update(chunk).mapError(chunkerFailure))
          .mapZIO(_ => h.digest.mapError(chunkerFailure).map(bytes => (algo -> bytes))
          .map(o => o._2))
      }
    }

  /**
   * Produce a stream of rolling digests for the incoming byte stream. Each
   * emitted [[Hash]] represents the digest of all bytes seen so far. This is a
   * ZIO-port of fs2's `Scan` utility.
   */
  def rolling(
    stream: Blocks
  ): Stream[Throwable, Hash.MultiHash] =
    ZStream.unwrapScoped {
      ref.getHashAlgos.flatMap: algo =>
        val algos = algo.mapZIOSortedMap:
          case (algo, Some(md)) => hasher(NonEmptySortedMap(algo -> Option({md.reset(); md}))).map(algo -> _)
          case (algo, None) => hasher(NonEmptySortedMap(algo -> None)).map(algo -> _)
        // .map(c => NonEmptySortedMap.fromNonEmptyChunk(c))

        algos.map: h =>
          stream.mapZIO { (block: Block) =>
            for
              _   <- h.toNonEmptyChunk.mapZIOSortedMap((algo, hasher) => hasher._2.update(block.bytes.toChunk).mapError(Hashing.hashingFailure(algo)))
              dig <- h.mapZIOSortedMap((algo, hasher) => hasher._2.snapshot.mapError(Hashing.hashingFailure(algo))).map(
                c => c.toNonEmptyChunk.map(c => c._1 -> c._2.bytes)
              )
            yield Hash.MultiHash(dig.flatMap(_._2).toNonEmptyChunk)
          }
    }

  def rolling(
    stream: Blocks,
    algo: HashAlgorithm
  ): Stream[Throwable, Hash.SingleHash] =
    ZStream.unwrapScoped {
      hasher(NonEmptySortedMap(algo -> None)).map { h =>
        stream.mapZIO { (block: Block) =>
          for
            _   <- h.update(block.bytes.toChunk).mapError(Hashing.hashingFailure(algo))
            dig <- h.snapshot.mapError(Hashing.hashingFailure(algo))
          yield Hash.SingleHash(algo, dig.bytes.head._2)
        }
      }
    }
end Hashing

/** Abstraction over incremental hashing implementations. */
trait Hasher extends AutoCloseable: 
  self =>

    import Hasher.Hashable
  
    def algorithm: UIO[NonEmptySortedSet[HashAlgorithm]]
    def update(chunk: Hashable): HashOp[Any, Unit]
    def snapshot: HashOp[Any, Hash.MultiHash]
    def digest: HashOp[Any, Hash.MultiHash]
    def closed: UIO[Boolean]

    protected def _close: Task[Unit]

    def closeTask(using t: Trace): Task[Unit] =
      ZIO.ifZIO(closed)(
        ZIO.logError(s"Hasher ${algorithm} already closed"),
        (_close.eventually
        .timeoutFail(GravitonError.BackendUnavailable(
          s"Hasher ${algorithm} timed out on close"
        ))(10.seconds)
        .onDoneCause(
          error = cause => ZIO.logErrorCause(cause),
          success = _ => ZIO.debug(s"Hasher ${algorithm} closed")
        ) *> ZIO.debug(s"Hasher ${algorithm} closed"))
      )

    override def close = 
      synchronized:
        Unsafe.unsafely:
          Runtime.default.unsafe.run(closeTask).getOrThrowFiberFailure()

object Hasher:
  
  type Hashable = Array[Byte] | Chunk[Byte] | Byte | java.nio.ByteBuffer | ByteVector

  /** MessageDigest backed hasher (SHA family). */
  def messageDigest(algo: NonEmptySortedMap[HashAlgorithm, Option[MessageDigest]]
  )(using Trace): ZIO[Scope, HashingFailure, Hasher] = 

    (ZIO.runtime[Scope]).flatMap: (rt) =>
      val scope = rt.environment.get[Scope]

      ZIO.fromAutoCloseable:

        @volatile var p: Promise[Throwable, Boolean] = 
          Unsafe.unsafe:
            (unsafe: Unsafe) ?=>
              rt.unsafe.run:
                ZIO.fiberIdWith: (fid: FiberId) => 
                  val p = Promise.unsafe.make[Throwable, Boolean](fid)
                  val scope = rt.environment.get[Scope]
                  scope.addFinalizer(p.succeed(true)).as(p)
                .provideSomeLayer(ZLayer.succeed(scope))
              .getOrThrowFiberFailure()

        for 
          md <- NonEmptyChunk.fromIterable(algo.head, algo.tail).mapZIO:
            case (algo, md) => algo match
              case HashAlgorithm.SHA256 => md.fold(
                Ref.Synchronized.make(MessageDigest.getInstance("SHA-256"))
              )(
                md => Ref.Synchronized.make(md)
              ).map(algo -> _)
              case HashAlgorithm.SHA512 => md.fold(
                Ref.Synchronized.make(MessageDigest.getInstance("SHA-512"))
              )(
                md => Ref.Synchronized.make(md)
              ).map(algo -> _)
              case HashAlgorithm.Blake3 => md.fold(
                Ref.Synchronized.make(Blake3MessageDigest())
              )(
                md => Ref.Synchronized.make(md)
              ).map(algo -> _)
          .map(NonEmptySortedMap.fromNonEmptyChunk)
          // .mapError(e => Hashing.hashingFailure(algo.head._1)(e))

          cl <- SubscriptionRef.make(false)

          _ <- cl.changes.mapZIO: b => 
            p.succeed(b) *> ZIO.attempt:
              ZIO.fiberIdWith: (fid: FiberId) => 
                p = Promise.unsafe.make[Throwable, Boolean](fid)
                scope.addFinalizer(p.succeed(true)).as(p)
              .provideSomeLayer(ZLayer.succeed(scope))
          .runLast.fork

        yield new Hasher:
          self: Hasher =>

            def digest: HashOp[Any, Hash.MultiHash] =
              md.mapZIOSortedMap((algo, md) => md.get.flatMap(o => ZIO.fromEither(HashBytes.either(Chunk.fromArray(o.digest()))
              .left.map(e => Hashing.hashingFailure(algo)(Throwable(e))))))
              .map(Hash.MultiHash(_))

            def closed: UIO[Boolean] = 
              cl.get

            protected def _close: Task[Unit] =
              md.mapZIO(
                (_, md) => 
                  md.updateZIO:
                    md => 
                      ZIO.attempt:                    
                        md.reset()
                        md
              ).unit
              


            def algorithm: UIO[NonEmptySortedSet[HashAlgorithm]] = 
              ZIO.succeed(algo.keySet)

            def update(chunk: Hashable): HashOp[Any, Unit] =
            
              (chunk match
                case array: Array[Byte]  => 
                  md.mapZIOSortedMap:
                    (_, md) => 
                      md.update { md => md.update(array); md } 
                  .unit
                case chunk: Chunk[Byte]  => 
                  md.mapZIOSortedMap:
                    (_, md) => 
                      md.update { md => md.update(chunk.toArray); md }
                  .unit
                case byte: Byte          => 
                  md.mapZIOSortedMap:
                    (_, md) => 
                      md.update { md => md.update(byte); md }
                  .unit
                case byteBuffer: java.nio.ByteBuffer => 
                  md.mapZIOSortedMap:
                    (_, md) => 
                      md.update { md => md.update(byteBuffer); md }
                  .unit
                case byteVector: ByteVector => 
                  md.mapZIOSortedMap:
                    (_, md) => 
                      md.update { md => md.update(byteVector.toArray); md }
                  .unit)
            def snapshot: HashOp[Any, Hash.MultiHash] =
              val cloned: HashOp[Any, NonEmptySortedMap[HashAlgorithm, MessageDigest]] =
                md.mapZIOSortedMap:
                  (_, md) =>
                    md.updateAndGetZIO: md => 
                      ZIO.attempt:
                        md.clone().asInstanceOf[MessageDigest]
                      .orElse(ZIO.succeed(md))

              cloned.flatMap: md => 
                md.mapZIOSortedMap((algo, md) => ZIO.fromEither(HashBytes.either(Chunk.fromArray(md.digest()))
                .left.map(e => Hashing.hashingFailure(algo)(Throwable(e)))))
                .map(o => Hash.MultiHash(o.map(o => o._1 -> o._2)))

      .mapErrorCause: e => 
        Cause.fail:
          HashingFailure(
            algo.head._1, 
            e.prettyPrint,
            None
          )
              
          
      
    

  /** Pure-Java Blake3 hasher. */
  def blake3: ZIO[Scope, HashingFailure, Hasher] = 
    ZIO.attempt {
      val bl: Blake3 = Blake3.newInstance()
      new Hasher:
        protected def _close: Task[Unit] = ZIO.unit
        def closed: UIO[Boolean] = ZIO.succeed(false)
        def algorithm: UIO[NonEmptySortedSet[HashAlgorithm]] = 
          ZIO.succeed(NonEmptySortedSet(HashAlgorithm.Blake3))
        def update(chunk: Hashable): HashOp[Any, Unit] = ZIO.attempt {
          chunk match
            case array: Array[Byte]    => bl.update(array)
            case chunk: Chunk[Byte]    => bl.update(chunk.toArray)
            case byte: Byte            => bl.update(Array(byte))
            case byteBuffer: java.nio.ByteBuffer => bl.update(byteBuffer.array())
            case byteVector: ByteVector => bl.update(byteVector.toArray)
        }.mapError(Hashing.hashingFailure(HashAlgorithm.Blake3))
        def snapshot: HashOp[Any, Hash.MultiHash] = 
          ZIO.attempt(Hash.MultiHash(NonEmptySortedMap(HashAlgorithm.Blake3 -> HashBytes.applyUnsafe(Chunk.fromArray(bl.digest())))))
          .mapError(Hashing.hashingFailure(HashAlgorithm.Blake3))
        def digest: HashOp[Any, Hash.MultiHash] = 
          ZIO.attempt(Hash.MultiHash(NonEmptySortedMap(HashAlgorithm.Blake3 -> HashBytes.applyUnsafe(Chunk.fromArray(bl.digest())))))
          .mapError(Hashing.hashingFailure(HashAlgorithm.Blake3))
          
    }.mapErrorCause: e => 
      Cause.fail:
        HashingFailure(
          HashAlgorithm.Blake3, 
          e.prettyPrint, 
          Some(e).filterNot(e => e.isEmpty | e.isDie).map(_.squash)
        )
