package graviton.backend.rocks

import graviton.runtime.kv.KeyValueStore
import org.rocksdb.{Options, RocksDB as JRocksDB}
import zio.*

import java.nio.file.Path

final class RocksKeyValueStore private[rocks] (private[rocks] val db: JRocksDB) extends KeyValueStore:

  override def put(key: String, value: Array[Byte]): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking(db.put(key.getBytes("UTF-8"), value))

  override def get(key: String): ZIO[Any, Throwable, Option[Array[Byte]]] =
    ZIO.attemptBlocking(Option(db.get(key.getBytes("UTF-8"))))

  override def delete(key: String): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking(db.delete(key.getBytes("UTF-8")))

object RocksKeyValueStore:

  JRocksDB.loadLibrary()

  def open(path: Path): ZIO[Scope, Throwable, RocksKeyValueStore] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val opts = new Options().setCreateIfMissing(true)
        val db   = JRocksDB.open(opts, path.toAbsolutePath.toString)
        new RocksKeyValueStore(db)
      }
    )(store => ZIO.attemptBlocking(store.db.close()).orDie)

  def layer(path: Path): ZLayer[Any, Throwable, KeyValueStore] =
    ZLayer.scoped(open(path))
