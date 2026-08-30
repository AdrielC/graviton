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
    new ManifestReferenceSource:
      override val referencedBlocks: ZStream[Any, StoreError, BinaryKey.Block] =
        ZStream.fromChunk(manifests.toChunk).flatMap { repository =>
          repository.streamSummaries.flatMap { case (blob, _) =>
            repository.streamBlockRefs(blob).map(_.key)
          }
        }

  def repositoriesLayer(manifests: NonEmptyChunk[BlobManifestRepo]): ZLayer[Any, Nothing, ManifestReferenceSource] =
    ZLayer.succeed(repositories(manifests))
