package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import zio.{NonEmptyChunk, ZIO, ZLayer}
import zio.stream.ZStream

/** Streaming mark source for every manifest that can reference one block domain. */
trait ManifestReferenceSource:
  def referencedBlocks: ZStream[Any, StoreError, BinaryKey.Block]

object ManifestReferenceSource:
  val service: ZIO[ManifestReferenceSource, Nothing, ManifestReferenceSource] =
    ZIO.service[ManifestReferenceSource]

  def repository(manifests: BlobManifestRepo): ManifestReferenceSource =
    repositories(NonEmptyChunk(manifests))

  /**
   * Build one domain-wide mark source. Repositories and their manifests are
   * traversed sequentially so tenant count does not multiply open cursors.
   */
  def repositories(manifests: NonEmptyChunk[BlobManifestRepo]): ManifestReferenceSource =
    streaming(ZStream.fromChunk(manifests.toChunk))

  /**
   * Build a domain-wide mark source when the repository set itself is durable
   * and streamed. At most one manifest cursor is open at a time, regardless of
   * tenant count.
   */
  def streaming(manifests: ZStream[Any, StoreError, BlobManifestRepo]): ManifestReferenceSource =
    new ManifestReferenceSource:
      override val referencedBlocks: ZStream[Any, StoreError, BinaryKey.Block] =
        manifests.flatMap { repository =>
          repository.streamSummaries.flatMap { case (blob, _) =>
            repository.streamBlockRefs(blob).map(_.key)
          }
        }

  def repositoriesLayer(manifests: NonEmptyChunk[BlobManifestRepo]): ZLayer[Any, Nothing, ManifestReferenceSource] =
    ZLayer.succeed(repositories(manifests))
