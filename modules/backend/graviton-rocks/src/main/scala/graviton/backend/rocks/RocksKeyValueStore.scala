package graviton.backend.rocks

import graviton.runtime.kv.KeyValueStore
import org.rocksdb.{Options, RocksDB as JRocksDB}
import zio.*

import java.nio.file.Path

final class RocksKeyValueStore private[rocks] (
  private[rocks] val db: JRocksDB,
  private val options: Options,
) extends KeyValueStore:

  override def put(key: String, value: Array[Byte]): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking(db.put(key.getBytes("UTF-8"), value))

  override def get(key: String): ZIO[Any, Throwable, Option[Array[Byte]]] =
    ZIO.attemptBlocking(Option(db.get(key.getBytes("UTF-8"))))

  override def delete(key: String): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking(db.delete(key.getBytes("UTF-8")))

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
