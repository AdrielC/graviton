package graviton.runtime.kv

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import graviton.runtime.stores.StoreError
import zio.{Chunk, IO}

type KvKey = KvKey.T
object KvKey extends RefinedType[String, MinLength[1] & MaxLength[1024]]

type KvValue = Chunk[Byte] :| MaxLength[33554432]
object KvValue:
  val MaxBytes: Int = 32 * 1024 * 1024

  def fromChunk(value: Chunk[Byte]): Either[String, KvValue] =
    value.refineEither[MaxLength[33554432]]

  def fromArray(value: Array[Byte]): Either[String, KvValue] =
    fromChunk(Chunk.fromArray(value))

trait KeyValueStore:
  def put(key: KvKey, value: KvValue): IO[StoreError, Unit]
  def get(key: KvKey): IO[StoreError, Option[KvValue]]
  def delete(key: KvKey): IO[StoreError, Unit]
