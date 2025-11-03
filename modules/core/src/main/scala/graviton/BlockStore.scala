package graviton

import zio.*
import zio.stream.*
import graviton.core.{BinaryAttributes, BlockManifest, BlockManifestEntry}
import graviton.core.model.Block



trait BlockStore:
  /**
   * Build a one-block sink that:
   *  - reads up to the implementation's MaxBlockSize bytes
   *  - hashes & stores that block (deduplicated)
   *  - emits exactly one BlockKey
   *  - peels any leftover Byte beyond the block boundary
   *  - treats BinaryAttributes as ingest hints/claims
   */
  def storeBlock(attrs: BinaryAttributes): ZPipeline[Any & Scope, GravitonError, Block, BlockManifestEntry]
  
  def put: ZSink[Any, Throwable, Block, Nothing, BlockManifest]
  
  def get(
    key: BlockKey,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]]

  def has(key: BlockKey): IO[Throwable, Boolean]
  def delete(key: BlockKey): IO[Throwable, Boolean]
  def list(selector: BlockKeySelector): ZStream[Any, Throwable, BlockKey]
  def gc(config: GcConfig): UIO[Int]
