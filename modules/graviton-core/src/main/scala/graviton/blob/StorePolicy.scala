package graviton.blob

import _root_.zio.json.{DeriveJsonCodec, JsonCodec}

enum BlobLayout derives JsonCodec:
  case FramedManifestChunks
  case MonolithicObject

final case class StorePolicy(
  layout: BlobLayout,
  minPartSize: Int = StorePolicy.DefaultMinPartSize,
):
  require(minPartSize > 0, "minPartSize must be positive")

object StorePolicy:
  val DefaultMinPartSize: Int  = 5 * 1024 * 1024
  given JsonCodec[StorePolicy] = DeriveJsonCodec.gen[StorePolicy]

trait PolicyResolver:
  def policyFor(scheme: String): StorePolicy

object PolicyResolver:
  final case class Static(
    overrides: Map[String, StorePolicy],
    fallback: StorePolicy,
  ) extends PolicyResolver:
    override def policyFor(scheme: String): StorePolicy =
      overrides.getOrElse(scheme, fallback)

  val default: PolicyResolver = Static(
    overrides = Map(
      "file" -> StorePolicy(BlobLayout.FramedManifestChunks),
      "s3"   -> StorePolicy(BlobLayout.FramedManifestChunks),
    ),
    fallback = StorePolicy(BlobLayout.FramedManifestChunks),
  )
