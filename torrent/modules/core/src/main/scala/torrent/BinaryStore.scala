package torrent

import java.security.MessageDigest

import torrent.BinaryKey.KeyMatcher
import torrent.ByteRange.Full
import torrent.StorageError.*

import zio.*
import zio.stream.*

import BinaryAttributes.withLength

/**
 * Low-level interface for binary storage backends. Allows storing, retrieving,
 * and managing binary data with flexible chunk-based access.
 *   - Only Writable keys can be created automatically.
 *   - Writable keys (Static, Random, Scoped) can be provided by user code.
 *   - insertWith only accepts Owned (compile and runtime safe).
 *   - Hash (CAS) keys are hidden internals.
 *   - Scoped keys must be non-empty.
 *   - No raw "require" — validation happens via safe ZIO or compile-time if
 *     possible.
 *   - KeyMatcher enables pure matching AST with no embedded functions.
 *   - BinaryAttributes separate "advertised" vs "verified" values.
 *   - Planned KMS encryption at storage layer.
 */
trait BinaryStore:

  /**
   * Stream sink that inserts bytes and returns a content-addressed (CAS) key.
   */
  def insertChunks(
    attributes: BinaryAttributes = BinaryAttributes.empty
  ): ZIO[Scope, SystemError, ZSink[Any, WriteError, Byte, Byte, BinaryKey.Borrowed]]

  /**
   * Stream sink that inserts bytes and returns a content-addressed (CAS) key.
   */
  def insertFile(
    attributes: BinaryAttributes = BinaryAttributes.empty
  ): ZIO[Scope, SystemError, ZPipeline[Any, WriteError, Byte, BinaryKey.Hashed]] =
    insertChunks(attributes)
      .zip(HashAlgorithm.ref.get)
      .zip(ZIO.scope)
      .map { (sink, algorithm: HashAlgo, scope) =>
        ZPipeline.fromFunction[Any, WriteError, Byte, BinaryKey.Hashed] { stream =>

          val insertSink =
            ZSink.fromChannel {
              ZChannel.unwrap {
                Ref.make(algorithm.getInstance).map { digest =>
                  for
                    digest    <- ZChannel.succeed(digest)
                    key       <- ZChannel.readWithCause(
                                   (in: Chunk[Byte]) =>
                                     ZChannel
                                       .fromZIO(digest.modify(d => (d.update(in.toArray), d)))
                                       .zipParRight(ZChannel.write(in).pipeTo(sink.channel)),
                                   (e: Cause[Throwable]) => ZChannel.failCause(e.map(WriteError.apply)),
                                   ZChannel.succeedNow
                                 )
                    newDigest <- ZChannel.fromZIO(digest.get)
                    hash      <- ZChannel.fromEither(algorithm.Key(newDigest))
                  yield hash
                }
              }
            }.mapError(WriteError.apply)

          ZStream.scoped {
            for
              (hashedKey, rest) <- stream.peel(insertSink).mapError(WriteError.apply)
              _                 <- rest
                                     .mapError(WriteError.apply)
                                     .run(ZSink.take(1))
                                     .flatMap(left =>
                                       ZIO
                                         .fail(WriteError(Throwable("Stream size exceeded insert limit")))
                                         .unless(left.nonEmpty)
                                     )
            yield hashedKey
          }
        }
      }

  /**
   * Stream sink that inserts under a user-provided writable key (random/static)
   * and returns true if overwritten.
   */
  def insertWith(key:        BinaryKey,
                 attributes: BinaryAttributes = BinaryAttributes.empty
  ): ZIO[Scope, SystemError, ZSink[Any, WriteError, Byte, Nothing, Boolean]]

  def getBinary(key: BinaryKey, byteRange: ByteRange = ByteRange.Full): IO[AccessError, Binary]

  /** Find verified attributes */
  def computeAttributes(key: BinaryKey, hint: Hint = Hint.empty): IO[AccessError, BinaryAttributes]

  /** Check existence quickly. */
  def exists(key: BinaryKey): IO[AccessError, Boolean]

  /** List keys matching a filter/matcher. */
  def listKeys(matcher: KeyMatcher): ZStream[Any, AccessError, BinaryKey]

  /** Copy a binary from one key to a writable key. */
  def copy(from: BinaryKey, to: BinaryKey): IO[StorageError, Unit]

  /** Delete a binary, returning true if it existed and was removed. */
  def delete(key: BinaryKey): IO[SystemError | DeleteError, Boolean]

  // Content-Addressable Storage Operations

  // /**
  //  * Insert with chunking policy, returning a manifest for reassembly
  //  */
  // def insertChunked(
  //   policy:     ChunkPolicy,
  //   attributes: BinaryAttributes = BinaryAttributes.empty
  // ): ZSink[Any, SystemError, Byte, Nothing, CasManifest] =
  //   ZSink.fromZIO(ZIO.fail(SystemError(new UnsupportedOperationException("CAS not implemented"))))

  // /**
  //  * Find binary by manifest, reassembling from chunks
  //  */
  // def findBinary(manifest: CasManifest): IO[StorageError.AccessError, Option[ZStream[Any, Throwable, Byte]]] =
  //   ZIO.fail(SystemError(new UnsupportedOperationException("CAS not implemented")))

  // /**
  //  * Find individual chunks by manifest
  //  */
  // def findChunks(manifest: CasManifest): ZStream[Any, StorageError.AccessError, Chunk[Byte]] =
  //   ZStream.fail(SystemError(new UnsupportedOperationException("CAS not implemented")))

  // /**
  //  * Store a single chunk with content-addressable key
  //  */
  // def storeChunk(data: Chunk[Byte]): IO[SystemError, CasChunk] =
  //   ZIO.fail(SystemError(new UnsupportedOperationException("CAS not implemented")))

end BinaryStore

object BinaryStore:

  /**
   * A no-op BinaryStore that returns empty or no-op values
   */
  def inMemory: ULayer[BinaryStore] =
    ZLayer.fromZIO:
      Ref
        .make(Map.empty[BinaryKey, Chunk[Byte]])
        .map: ref =>
          new BinaryStore:

            override def insertChunks(
              attributes: BinaryAttributes = BinaryAttributes.empty
            ): ZIO[Scope, SystemError, ZSink[Any, WriteError, Byte, Nothing, BinaryKey.Borrowed]] =
              for
                id <- BinaryKey.random
                _  <- ZIO.logInfo(s"inserting $id")
                _  <- ZIO.fail(SystemError(new RuntimeException("invalid attributes"))).when(false)
              yield ZSink.logSpan("insert"):
                ZSink.foreachChunk { (chunk: Chunk[Byte]) =>
                  (if chunk.size > 1000000 then ZIO.fail(WriteError(new RuntimeException("chunk too large")))
                   else ZIO.unit) *>
                    ref
                      .update(_.updatedWith(id) {
                        case Some(value) => Some(value ++ chunk)
                        case None        => Some(chunk)
                      })
                }.as(id)

            override def insertWith(key:        BinaryKey,
                                    attributes: BinaryAttributes = BinaryAttributes.empty
            ): ZIO[Scope, SystemError, ZSink[Any, WriteError, Byte, Nothing, Boolean]] =
              ZIO
                .logInfo(s"insertWith $key")
                .map: _ =>
                  ZSink.logSpan("insertWith"):
                    ZSink.logAnnotate(LogAnnotation("key", key.mkString)):
                      ZSink
                        .succeed(key)
                        .flatMap: id =>
                          ZSink
                            .foreachChunk: (chunk: Chunk[Byte]) =>
                              ZIO.logInfo(s"adding chunk ${chunk.size}") *>
                                ref.update(_.updatedWith(id) {
                                  case Some(value) => Some(value ++ chunk)
                                  case None        => Some(chunk)
                                })
                            .zipParRight(
                              ZSink.count.mapZIO: count =>
                                ZIO.logInfo(s"insertWith $key: $count bytes").as(true)
                            )

            override def copy(from: BinaryKey, to: BinaryKey): IO[StorageError, Unit] =
              ZIO.logInfo(s"Copying $from to $to") &>
                ref.get.flatMap: map =>
                  map.get(from) match
                    case Some(value) =>
                      ref.update(_.updatedWith(to) {
                        case Some(_) => Some(value)
                        case None    => Some(value)
                      })
                    case None        => ZIO.unit

            override def delete(key: BinaryKey): IO[DeleteError, Boolean] =
              ZIO.logInfo(s"Deleting $key") *>
                ref.get.flatMap: map =>
                  ref
                    .update(_.removed(key))
                    .as(map.contains(key))

            override def listKeys(matcher: KeyMatcher): ZStream[Any, SystemError | ReadError, BinaryKey] =
              ZStream.fromZIO(ref.get.map(_.keys).map(Chunk.fromIterable)).flattenChunks

            override def getBinary(key: BinaryKey, byteRange: ByteRange): IO[AccessError, Binary] =
              ZIO.logSpan("getBinary"):
                ZIO.logAnnotate(LogAnnotation("key", key.mkString)):
                  ref.get
                    .flatMap:
                      _.get(key) match
                        case Some(value) =>
                          ZIO.succeed(
                            Binary(key,
                                   BinaryAttributes.empty.withLength(value.size),
                                   ByteStream(ZStream.fromChunk(value))
                            )
                          )
                        case None        =>
                          ZIO.fail(NotFound(key))
                    .logError
                    .tap(v => ZIO.logInfo(v.toString))

            override def computeAttributes(key:  BinaryKey,
                                           hint: Hint
            ): IO[SystemError | ReadError | NotFound, BinaryAttributes] =
              // For now, just compute basic attributes
              getBinary(key, Full).flatMap { binary =>
                binary.data.runCount.map { count =>
                  BinaryAttributes.empty.withLength(count)
                }
              }.mapError {
                case nf: NotFound => nf
                case other        => ReadError(new RuntimeException(s"Failed to compute attributes: $other"))
              }

            override def exists(key: BinaryKey): IO[ReadError | SystemError, Boolean] =
              ref.get.map(_.contains(key))
