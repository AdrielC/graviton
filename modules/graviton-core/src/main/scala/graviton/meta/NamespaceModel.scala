package graviton.meta

import zio.schema.DynamicValue.Record

/** Metadata describing a namespace’s schema provenance (embedded next to each block of DynamicValue data). */
final case class NamespaceHeader(
  id: Option[SchemaId],
  schema: Option[SchemaId],
  version: Option[SemVerRepr],
  source: Option[String],
)

/** Canonical in-memory representation of one namespace’s metadata. */
final case class NamespaceBlock(
  schema: Option[NamespaceHeader],
  data: Record,
)

object NamespaceBlock:
  def empty(data: Record): NamespaceBlock = NamespaceBlock(schema = None, data = data)

/** Entire namespaced metadata map as held inside Graviton. */
final case class NamespacesDyn(
  namespaces: Map[NamespaceUrn, NamespaceBlock],
  schemas: Map[NamespaceUrn, SchemaId],
)

object NamespacesDyn:
  val empty: NamespacesDyn = NamespacesDyn(Map.empty, Map.empty)
