package graviton.backend.rocks

import graviton.runtime.kv.{KeyValueStore, KvKey, KvValue}
import graviton.runtime.stores.{StoreBackend, StoreError, StoreOperation}
import org.rocksdb.{Options, RocksDB as JRocksDB}
import zio.*

import java.nio.file.Path

final class RocksKeyValueStore private[rocks] (
  private[rocks] val db: JRocksDB,
  private val options: Options,
) extends KeyValueStore:

  override def put(key: KvKey, value: KvValue): IO[StoreError, Unit] =
    ZIO
      .attemptBlocking(db.put(key.value.getBytes("UTF-8"), value.toArray))
      .mapError(StoreError.fromThrowable(StoreOperation.PutKeyValue, StoreBackend.RocksDb, retryUnknown = true))

  override def get(key: KvKey): IO[StoreError, Option[KvValue]] =
    ZIO
      .attemptBlocking(Option(db.get(key.value.getBytes("UTF-8"))))
      .mapError(StoreError.fromThrowable(StoreOperation.GetKeyValue, StoreBackend.RocksDb, retryUnknown = true))
      .flatMap {
        case None        => ZIO.succeed(None)
        case Some(value) =>
          ZIO
            .fromEither(KvValue.fromArray(value))
            .mapError(message =>
              StoreError.CorruptData(
                StoreOperation.GetKeyValue,
                s"RocksDB value for '${key.value}' exceeds ${KvValue.MaxBytes} bytes: $message",
              )
            )
            .map(Some(_))
      }

  override def delete(key: KvKey): IO[StoreError, Unit] =
    ZIO
      .attemptBlocking(db.delete(key.value.getBytes("UTF-8")))
      .mapError(StoreError.fromThrowable(StoreOperation.DeleteKeyValue, StoreBackend.RocksDb, retryUnknown = true))

  private[rocks] def close(): Unit =
    try db.close()
    finally options.close()

object RocksKeyValueStore:

  JRocksDB.loadLibrary()

  def open(path: Path): ZIO[Scope, StoreError, RocksKeyValueStore] =
    ZIO
      .acquireRelease(
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
      .mapError(StoreError.fromThrowable(StoreOperation.HealthCheck, StoreBackend.RocksDb, retryUnknown = true))

  def layer(path: Path): ZLayer[Any, StoreError, KeyValueStore] =
    ZLayer.scoped(open(path))
