package graviton.blobstore.live

import graviton.*
import graviton.Bytes
import graviton.Hashing
import graviton.core.model.Size
import graviton.objectstore.*
import graviton.ranges.ByteRange
import zio.*
import zio.stream.*
import zio.test.*
import zio.Chunk
import scala.util.Try

object BlobStoreLiveSpec extends ZIOSpecDefault:
  def spec = suite("BlobStoreLive")(
    test("writes and reads via object store") {
      for
        objectStore <- inMemoryObjectStore
        blobStore    = BlobStoreLive.forHashPrefix(objectStore)
        bytes        = Chunk.fromArray("hello".getBytes("UTF-8"))
        hashBytes   <- Hashing.compute(Bytes(ZStream.fromChunk(bytes)), HashAlgorithm.SHA256)
        size        <- ZIO.fromEither(Size(bytes.length)).orDieWith(msg => IllegalArgumentException(msg))
        key          = BlockKey(Hash(hashBytes, HashAlgorithm.SHA256), size)
        _           <- blobStore.write(key, Bytes(ZStream.fromChunk(bytes)))
        read        <- blobStore.read(key)
        collected   <- ZIO.foreach(read)(_.runCollect)
      yield assertTrue(collected.contains(bytes))
    }
  )

  private def inMemoryObjectStore: UIO[ObjectStore] =
    Ref.make(Map.empty[ObjectPath, Chunk[Byte]]).map { ref =>
      new ObjectStore {
        def head(path: ObjectPath): IO[ObjectStoreError, Option[ObjectMetadata]] =
          ref.get.map { store =>
            store.get(path).map { chunk =>
              ObjectMetadata(chunk.length.toLong, None, None, None, Map.empty)
            }
          }

        def list(prefix: ObjectPath, recursive: Boolean): ZStream[Any, ObjectStoreError, ListedObject] =
          ZStream.fromIterable(Iterable.empty)

        def get(path: ObjectPath, range: Option[ByteRange]): ZStream[Any, ObjectStoreError, Byte] =
          ZStream.unwrap {
            ref.get.map { store =>
              store.get(path) match
                case Some(bytes) =>
                  range match
                    case Some(br) =>
                      val startEither  = Try(Math.toIntExact(br.startValue)).toEither
                      val lengthEither = Try(Math.toIntExact(br.lengthValue)).toEither
                      (startEither, lengthEither) match
                        case (Right(start), Right(len)) =>
                          val end = (start + len).min(bytes.length)
                          ZStream.fromChunk(bytes.slice(start, end))
                        case _                          => ZStream.fail(ObjectStoreError.Unexpected("invalid byte range", None))
                    case None     => ZStream.fromChunk(bytes)
                case None        => ZStream.fail(ObjectStoreError.Missing(path))
            }
          }

        def put(path: ObjectPath, data: ZStream[Any, Throwable, Byte], metadata: PutMetadata): IO[ObjectStoreError, Unit] =
          data.runCollect
            .mapError(ObjectStoreError.fromThrowable)
            .flatMap(chunk => ref.update(_.updated(path, chunk)))

        def putMultipart(path: ObjectPath, parts: ZStream[Any, Throwable, Byte], metadata: PutMetadata): IO[ObjectStoreError, Unit] =
          put(path, parts, metadata)

        def delete(path: ObjectPath): IO[ObjectStoreError, Boolean] =
          ref.modify(map => (map.contains(path), map - path))

        def copy(from: ObjectPath, to: ObjectPath, options: CopyOptions): IO[ObjectStoreError, Unit] =
          ref.get.flatMap { store =>
            store.get(from) match
              case Some(bytes) => ref.update(_.updated(to, bytes)).unit
              case None        => ZIO.fail(ObjectStoreError.Missing(from))
          }
      }
    }
