package graviton.core

import zio.*
import zio.stream.*
import graviton.core.model.*
import graviton.Bytes

trait BlobStore extends BlockStore:

  def insert(
    provided: BinaryAttributes,
  ): ZSink[Any, Throwable, Block, Nothing, (FileKey.CasKey.BlockKey, BinaryAttributes)]
  
  def insertWith(key: FileKey.WritableKey): ZSink[Any, Throwable, Block, Nothing, BlockManifest]

  def findBinary(key: FileKey): IO[Throwable, Option[Bytes]]

  def copy(from: FileKey, to: FileKey.WritableKey): IO[Throwable, Unit]
  override def delete(key: FileKey): IO[Throwable, Boolean]

  
