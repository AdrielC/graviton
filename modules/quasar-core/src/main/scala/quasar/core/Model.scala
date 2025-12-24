package quasar.core

import zio.json.*

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Quasar domain model (tenant-implicit).
 *
 * This module intentionally avoids storage/backend concerns: it is the stable surface
 * for document identity, versions, content refs, and governed metadata shapes.
 */
final case class DocumentId(value: UUID) derives JsonCodec
final case class VersionId(value: UUID) derives JsonCodec

final case class NamespaceUrn(value: String) derives JsonCodec
object NamespaceUrn:
  given JsonFieldEncoder[NamespaceUrn] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[NamespaceUrn] = JsonFieldDecoder.string.map(NamespaceUrn(_))

final case class SchemaUrn(value: String) derives JsonCodec

enum ContentKind derives JsonCodec:
  case blob, view

final case class ContentRef(kind: ContentKind, key: String) derives JsonCodec

final case class MetadataEntryId(value: String) derives JsonCodec

final case class MetadataEntry(
  id: MetadataEntryId,
  schema: SchemaUrn,
  data: zio.json.ast.Json.Obj,
) derives JsonCodec

final case class MetadataBundle(namespaces: Map[NamespaceUrn, MetadataEntry]) derives JsonCodec

enum MetadataMode derives JsonCodec:
  case canonical, derived

final case class Producer(name: String, version: String) derives JsonCodec

final case class Provenance(
  producer: Producer,
  producedAt: OffsetDateTime,
  input: ContentRef,
  confidence: Option[Double] = None,
) derives JsonCodec
