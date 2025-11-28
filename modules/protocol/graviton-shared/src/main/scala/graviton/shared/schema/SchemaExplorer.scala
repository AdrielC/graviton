package graviton.shared.schema

import zio.Chunk
import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.schema.{Schema, TypeId}
import zio.schema.Schema.{Collection, Enum, Lazy, Optional, Primitive, Record, Transform}

import scala.collection.mutable

object SchemaExplorer {

  enum NodeKind derives JsonCodec {
    case Product, Sum, Collection, Optional, Primitive, Opaque
  }

  final case class Field(
    name: String,
    optional: Boolean,
    annotations: List[String],
    targetId: String,
    typeName: String,
  ) derives JsonCodec

  final case class Case(
    name: String,
    annotations: List[String],
    targetId: String,
    typeName: String,
  ) derives JsonCodec

  final case class CollectionInfo(
    elementTargetId: String,
    elementTypeName: String,
    annotations: List[String],
  ) derives JsonCodec

  final case class Node(
    id: String,
    kind: NodeKind,
    typeName: String,
    label: String,
    summary: String,
    annotations: List[String],
    fields: List[Field] = Nil,
    cases: List[Case] = Nil,
    collection: Option[CollectionInfo] = None,
  ) derives JsonCodec

  final case class NodeRef(
    id: String,
    typeName: String,
    kind: NodeKind,
  ) derives JsonCodec

  final case class Graph(
    root: NodeRef,
    nodes: List[Node],
  ) derives JsonCodec

  def describe[A](schema: Schema[A]): Graph = {
    val registry = new Registry
    val rootId   = registry.register(schema)
    val rootNode = registry.nodes(rootId)
    Graph(NodeRef(rootNode.id, rootNode.typeName, rootNode.kind), registry.nodes.values.toList)
  }

  private final class Registry {
    val nodes: mutable.LinkedHashMap[String, Node] = mutable.LinkedHashMap.empty

    def register(schema: Schema[?]): String = {
      val cleaned = unwrap(schema)
      val id      = fingerprint(cleaned)
      if (!nodes.contains(id)) {
        val node = buildNode(id, cleaned)
        nodes.update(id, node)
      }
      id
    }

    private def buildNode(id: String, schema: Schema[?]): Node = schema match {
      case record: Record[?] =>
        val fields = record.fields.map { field =>
          val targetId = register(field.schema)
          Field(
            name = field.name.toString,
            optional = field.optional,
            annotations = asStrings(field.annotations),
            targetId = targetId,
            typeName = nodeTypeName(field.schema),
          )
        }.toList
        Node(
          id = id,
          kind = NodeKind.Product,
          typeName = nodeTypeName(record),
          label = nodeLabel(record.id),
          summary = schemaSummary(record),
          annotations = asStrings(record.annotations),
          fields = fields,
        )

      case enum: Enum[?] =>
        val cases = enum.cases.map { c =>
          val targetId = register(c.schema)
          Case(
            name = c.caseName,
            annotations = asStrings(c.annotations),
            targetId = targetId,
            typeName = nodeTypeName(c.schema),
          )
        }.toList
        Node(
          id = id,
          kind = NodeKind.Sum,
          typeName = nodeTypeName(enum),
          label = nodeLabel(enum.id),
          summary = schemaSummary(enum),
          annotations = asStrings(enum.annotations),
          cases = cases,
        )

      case opt: Optional[?] =>
        val valueId = register(opt.schema)
        val field   = Field(
          name = "value",
          optional = true,
          annotations = asStrings(opt.annotations),
          targetId = valueId,
          typeName = nodeTypeName(opt.schema),
        )
        Node(
          id = id,
          kind = NodeKind.Optional,
          typeName = nodeTypeName(opt),
          label = s"Option[${nodeTypeName(opt.schema)}]",
          summary = schemaSummary(opt),
          annotations = asStrings(opt.annotations),
          fields = List(field),
        )

      case collection: Collection[?, ?] =>
        val elementId = register(collection.elementSchema)
        Node(
          id = id,
          kind = NodeKind.Collection,
          typeName = nodeTypeName(collection),
          label = nodeTypeName(collection),
          summary = schemaSummary(collection),
          annotations = asStrings(collection.annotations),
          collection = Some(
            CollectionInfo(
              elementTargetId = elementId,
              elementTypeName = nodeTypeName(collection.elementSchema),
              annotations = asStrings(collection.elementSchema.annotations),
            )
          ),
        )

      case primitive: Primitive[?] =>
        Node(
          id = id,
          kind = NodeKind.Primitive,
          typeName = primitive.standardType.tag,
          label = primitive.standardType.tag,
          summary = schemaSummary(primitive),
          annotations = asStrings(primitive.annotations),
        )

      case other =>
        Node(
          id = id,
          kind = NodeKind.Opaque,
          typeName = nodeTypeName(other),
          label = nodeTypeName(other),
          summary = schemaSummary(other),
          annotations = asStrings(other.annotations),
        )
    }

    private def unwrap(schema: Schema[?]): Schema[?] = schema match {
      case transform: Transform[?, ?, ?] => unwrap(transform.schema)
      case lazySchema: Schema.Lazy[?]    => unwrap(lazySchema.schema)
      case other                         => other
    }
  }

  private def asStrings(values: Chunk[Any]): List[String] =
    values.map(_.toString).toList

  private def fingerprint(schema: Schema[?]): String =
    s"${schema.getClass.getName}:${schema.ast.hashCode().toHexString}"

  private def nodeTypeName(schema: Schema[?]): String = schema match {
    case record: Record[?] => nodeLabel(record.id)
    case enum: Enum[?]     => nodeLabel(enum.id)
    case _                 => schema.getClass.getSimpleName
  }

  private def nodeLabel(id: TypeId): String = id match {
    case TypeId.Structural                    => "<anonymous>"
    case named: TypeId.Nominal @unchecked     => named.fullyQualified
  }

  private def schemaSummary(schema: Schema[?]): String = schema.ast.toString
}
