package torrent

import torrent.BinaryKey.KeyMatcher
import torrent.StorageError.*
import torrent.utils.CopyTool

import zio.*
import zio.metrics.*
import zio.prelude.NonEmptyList
import zio.stream.*

/**
 * A utility to chain stores. The first store in the given list is the "main"
 * store. It will be used for all read operations. Write operations happen first
 * on the main store and if successful they are applied to all others.
 */
final case class StackedBinaryStore private (
  stores:  NonEmptyList[BinaryStore],
  maxOpen: Int
) extends BinaryStore:

  import StackedBinaryStore.metrics.*

  override def toString: String = s"StackedBinaryStore(stores=${stores.mkString(", ")}, maxOpen=$maxOpen)"

  val main: BinaryStore = stores.head

  override def listKeys(matcher: KeyMatcher): ZStream[Any, AccessError, BinaryKey] =
    ZStream.logAnnotate("operation", "listIds").drain ++ main.listKeys(matcher)

  override def copy(from: BinaryKey, to: BinaryKey): IO[StorageError, Unit] = ???

  // override def insertChunks(attributes: BinaryAttributes): ZIO[Scope, Throwable, ZSink[Any, WriteError, FileChunk, Nothing, BinaryKey.Generated]] = ???

  // override def insertChunksWith(key: BinaryKey.Owned, attributes: BinaryAttributes): ZIO[Scope, SystemError, ZSink[Any, WriteError, FileChunk, Nothing, Boolean]] = ???

  /**
   * Processes the stream through all remaining stores (after the main store)
   */
  private def withRemaining[A](
    f: BinaryStore => ZStream[Any, StorageError, A]
  ): ZPipeline[Any, StorageError, BinaryStore, A] =
    ???
//    val all = ZStream.fromIterable(stores.tail).flatMap(f)
//    if maxOpen > 1 then
//      all.distributedWithDynamic(maxOpen)(ZIO.succeed(_))
//    else
//      all

  def insertChunks(
    attributes: BinaryAttributes
  ): ZIO[Scope, SystemError, ZSink[Any, WriteError, Byte, Byte, BinaryKey.Borrowed]] =
    ???
    // ZIO.logInfo(s"insertChunks $attributes").ensuring(ZIO.logInfo("insertChunks done"))
    // .map: _ =>

    //   val bytesCounter = ZPipeline.mapZIO[Any, Nothing, Byte, Byte](byte =>
    //     ZIO.logAnnotate("operation", "insert") {
    //       ZIO.logAnnotate("attributes", attributes.toJson) {
    //         bytesWritten.increment.as(byte)
    //       }
    //     }
    //   )

    //   bytesCounter
    //     .andThen(main.insertChunks(attributes))
    //     .andThen(withRemaining(_.insertWith(id)))
    //     .tap(_ => writes.increment)
    //     .flatMap { id =>
    //       ZStream.succeed(id)
    //     }
    //     .catchAll(e =>
    //       ZStream.fromZIO(
    //         writeErrors.increment *>
    //         ZIO.logError(s"Insert error: ${e.message}") *>
    //         ZIO.fail(e)
    //       )
    //     )

  override def insertWith(key:        BinaryKey,
                          attributes: BinaryAttributes
  ): ZIO[Scope, SystemError, ZSink[Any, WriteError, Byte, Nothing, Boolean]] =
    ZIO
      .logInfo(s"insertWith $key")
      .map: _ =>
        ZSink.count.mapZIO: bytes =>
          if bytes > 0 then ZIO.succeed(true)
          else ZIO.succeed(false)

//    ZPipeline.mapZIO[Any, Nothing, Byte, Byte]: byte =>
//      ZStream.logAnnotate("operation", "insertWith").drain ++
//        ZStream.logAnnotate("fileId", id.toString).drain ++ {
//          val mainInsert = main.insertWith(id).tap(_ => bytesWritten.increment)
//          val restInsert = withRemaining(_.insertWith(id))
//
//          ((mainInsert andThen restInsert)).mapError(e =>
//              writeErrors.increment *>
//              ZIO.logError(s"InsertWith error for id ${id}: ${e.message}")
//            )
//        }

  override def getBinary(key: BinaryKey, byteRange: ByteRange): IO[ReadError | SystemError | NotFound, Binary] = ???
//    ZIO.logAnnotate("operation", "getBinary") {
//      ZIO.logAnnotate("fileId", id.toString) {
//        reads.increment *>
//          main.getBinary(id, range).tapBoth({
//            e =>
//              readErrors.increment *> ZIO.logError(s"FindBinary error for id ${id}: ${e.message}")
//          }, _ => bytesRead.increment(range.length(Long.MaxValue)))
//      }
//    }

  override def exists(id: BinaryKey): IO[AccessError, Boolean] =
    ZIO.logAnnotate("operation", "exists") {
      main
        .exists(id)
        .tapError(e =>
          readErrors.increment *>
            ZIO.logError(s"Exists error for id ${id}: ${e.getMessage}")
        )
    }

  override def delete(key: BinaryKey): IO[DeleteError, Boolean] = ???
//    ZIO.logAnnotate("operation", "delete") {
//      ZIO.logAnnotate("fileId", id.toString) {
//        main.delete(id)
//          .flatMap(_ => withRemaining(s => ZStream.fromZIO(s.delete(id))).runDrain)
//          .tapError(e => ZIO.logError(s"Delete error for id ${id}: ${e.message}")) @@ deletes
//      }
//    }

  override def computeAttributes(key: BinaryKey, hint: Hint): IO[AccessError, BinaryAttributes] =
    ZIO.logAnnotate("operation", "computeAttr") {
      ZIO.logAnnotate("fileId", key.toString) {
        main
          .computeAttributes(key, hint)
          .tapError(e =>
            readErrors.increment *>
              ZIO.logError(s"ComputeAttr error for id $key: ${(e: Throwable).getMessage}")
          )
      }
    }

  /**
   * Copy all content from the main store to the remaining stores
   */
  def copyMainToRest: IO[StorageError, List[CopyTool.CopyStats]] =
    val maxConcurrent = math.max(java.lang.Runtime.getRuntime.availableProcessors() * 1.5, 4).toInt

    ZIO.logSpan("copyMainToRest") {
      ZIO.foreach(stores.tail) { targetStore =>
        ZIO.scoped {
          CopyTool.copyAll(main, targetStore, chunkSize = 100, maxConcurrent)
        }
      }
    }

object StackedBinaryStore:
  /**
   * Creates a StackedBinaryStore with at least two stores
   */
  def of(
    main: BinaryStore,
    next: BinaryStore,
    rest: BinaryStore*
  ): RIO[Metrics, BinaryStore] =
    apply(NonEmptyList(main, next :: rest.toList*))

  /**
   * Creates a StackedBinaryStore
   *
   * If only one store is provided, just returns that store without wrapping
   */
  def apply(
    stack: NonEmptyList[BinaryStore]
  ): RIO[Metrics, BinaryStore] =
    stack.tail match
      case Nil => ZIO.succeed(stack.head)
      case _   =>
        for
          _      <- ZIO.logInfo("Creating StackedBinaryStore")
          cpus    = java.lang.Runtime.getRuntime.availableProcessors()
          maxOpen = math.min(cpus, stack.tail.size)
        yield new StackedBinaryStore(stack, maxOpen)

  /**
   * Creates a Layer for a StackedBinaryStore
   */
  def layer(
    stores:  NonEmptyList[BinaryStore],
    maxOpen: Option[Int] = None
  ): RLayer[Metrics, BinaryStore] =
    ZLayer.fromZIO:
      for
        _            <- ZIO.logInfo("Creating StackedBinaryStore")
        cpus          = java.lang.Runtime.getRuntime.availableProcessors()
        actualMaxOpen = maxOpen.getOrElse(math.min(cpus, stores.tail.size))
      yield stores.tail match
        case Nil => stores.head
        case _   => new StackedBinaryStore(stores, actualMaxOpen)

  object metrics:
    // Metrics definitions
    private[StackedBinaryStore] val reads        = Metric.counter("torrent_stacked_reads")
    private[StackedBinaryStore] val writes       = Metric.counter("torrent_stacked_writes")
    private[StackedBinaryStore] val deletes      = Metric.counter("torrent_stacked_deletes")
    private[StackedBinaryStore] val readErrors   = Metric.counter("torrent_stacked_read_errors")
    private[StackedBinaryStore] val writeErrors  = Metric.counter("torrent_stacked_write_errors")
    private[StackedBinaryStore] val bytesRead    = Metric.counter("torrent_stacked_bytes_read")
    private[StackedBinaryStore] val bytesWritten = Metric.counter("torrent_stacked_bytes_written")
