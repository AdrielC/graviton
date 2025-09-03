package graviton

import zio.*
import zio.stream.*

trait BlockStore:
  def put: ZSink[Any, Throwable, Byte, Nothing, BlockKey]
  def get(key: BlockKey): IO[Throwable, Option[Bytes]]
  def has(key: BlockKey): IO[Throwable, Boolean]
  def delete(key: BlockKey): IO[Throwable, Boolean]
  def list(selector: BlockKeySelector): ZStream[Any, Throwable, BlockKey]
