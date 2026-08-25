package graviton.backend.rocks

import graviton.runtime.kv.{KeyValueStore, KvKey, KvValue}
import org.rocksdb.{Options, RocksDB as JRocksDB}
import zio.*

import java.nio.file.Path

final class RocksKeyValueStore private[rocks] (
  private[rocks] val db: JRocksDB,
  private val options: Options,
) extends KeyValueStore:

  override def put(key: KvKey, value: KvValue): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking(db.put(key.value.getBytes("UTF-8"), value.toArray))

  override def get(key: KvKey): ZIO[Any, Throwable, Option[KvValue]] =
    ZIO.attemptBlocking(Option(db.get(key.value.getBytes("UTF-8")))).flatMap {
      case None        => ZIO.succeed(None)
      case Some(value) =>
        ZIO
          .fromEither(KvValue.fromArray(value))
          .mapError(message => new IllegalStateException(s"RocksDB value for '${key.value}' exceeds ${KvValue.MaxBytes} bytes: $message"))
          .map(Some(_))
    }

  override def delete(key: KvKey): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking(db.delete(key.value.getBytes("UTF-8")))

  private[rocks] def close(): Unit =
    try db.close()
    finally options.close()

object RocksKeyValueStore:

  JRocksDB.loadLibrary()

  def open(path: Path): ZIO[Scope, Throwable, RocksKeyValueStore] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val opts = new Options().setCreateIfMissing(true)
        try
          val db = JRocksDB.open(opts, path.toAbsolutePath.toString)
          new RocksKeyValueStore(db, opts)
        catch
          case error: Throwable =>
            opts.close()
            throw error
      }
    )(store => ZIO.attemptBlocking(store.close()).orDie)

  def layer(path: Path): ZLayer[Any, Throwable, KeyValueStore] =
    ZLayer.scoped(open(path))
