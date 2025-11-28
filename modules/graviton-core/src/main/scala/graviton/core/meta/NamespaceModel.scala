package graviton.core.meta

import zio.schema.DynamicValue

final case class NamespaceHeader(
  id: Option[SchemaId],
  schema: Option[SchemaId],
  version: Option[SemVerRepr],
  source: Option[String],
)

object NamespaceHeader:
  val empty: NamespaceHeader = NamespaceHeader(None, None, None, None)

/** Canonical in-memory representation of one namespace’s metadata. */
final case class NamespaceBlock(
  schema: Option[NamespaceHeader],
  data: DynamicValue.Record,
)

object NamespaceBlock:
  def apply(data: DynamicValue.Record): NamespaceBlock = NamespaceBlock(None, data)

/** Entire namespaced metadata map as held *inside Graviton*. */
final case class NamespacesDyn(
  namespaces: Map[NamespaceUrn, NamespaceBlock],
  schemas: Map[NamespaceUrn, SchemaId],
)

object NamespacesDyn:
  val empty: NamespacesDyn = NamespacesDyn(Map.empty, Map.empty)
