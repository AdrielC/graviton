package graviton.frontend.components

import com.raquo.laminar.api.L.*
import graviton.shared.schema.SchemaExplorer

object SchemaExplorerView {

  def apply(graph: SchemaExplorer.Graph): HtmlElement = {
    val nodes    = graph.nodes.map(node => node.id -> node).toMap
    val openVar  = Var(Set(graph.root.id))
    val visited0 = Set.empty[String]

    div(
      cls := "schema-explorer",
      renderNode(graph.root.id, nodes, openVar, depth = 0, visited = visited0),
    )
  }

  private def renderNode(
    nodeId: String,
    nodes: Map[String, SchemaExplorer.Node],
    openVar: Var[Set[String]],
    depth: Int,
    visited: Set[String],
  ): HtmlElement =
    nodes.get(nodeId) match {
      case None                                   =>
        div(cls := "schema-node schema-node-missing", s"Unknown node: $nodeId")
      case Some(node) if visited.contains(nodeId) =>
        div(cls := "schema-node schema-node-cycle", span(cls := "schema-node-cycle-label", s"↺ ${node.label}"))
      case Some(node)                             =>
        val hasChildren = node.fields.nonEmpty || node.cases.nonEmpty || node.collection.nonEmpty
        val icon        = if (hasChildren) Some(toggle(nodeId, openVar)) else None
        val header      =
          div(
            cls := "schema-node-header",
            icon.getOrElse(span(cls := "schema-node-spacer", "•")),
            div(
              cls := "schema-node-meta",
              span(cls := "schema-node-kind", node.kind.toString.toUpperCase),
              span(cls := "schema-node-title", node.label),
            ),
          )

        val childrenNode =
          child <-- openVar.signal.map { open =>
            if (hasChildren && open.contains(nodeId))
              div(
                cls := "schema-node-children",
                renderFields(nodeId, node.fields, nodes, openVar, depth + 1, visited + nodeId),
                renderCases(nodeId, node.cases, nodes, openVar, depth + 1, visited + nodeId),
                node.collection.map(renderCollection(_, nodes, openVar, depth + 1, visited + nodeId)).getOrElse(div()),
              )
            else div()
          }

        div(
          cls := "schema-node",
          header,
          div(cls := "schema-node-summary", node.summary),
          childrenNode,
        )
    }

  private def toggle(nodeId: String, openVar: Var[Set[String]]): HtmlElement =
    button(
      cls := "schema-node-toggle",
      child.text <-- openVar.signal.map(open => if (open.contains(nodeId)) "▾" else "▸"),
      onClick.mapTo(nodeId) --> { id =>
        openVar.update(open => if (open.contains(id)) open - id else open + id)
      },
    )

  private def renderFields(
    ownerId: String,
    fields: List[SchemaExplorer.Field],
    nodes: Map[String, SchemaExplorer.Node],
    openVar: Var[Set[String]],
    depth: Int,
    visited: Set[String],
  ): HtmlElement =
    if (fields.isEmpty) div()
    else
      div(
        cls := "schema-fields",
        h4("Fields"),
        fields.map { field =>
          div(
            cls := "schema-field-row",
            span(cls := "schema-field-name", field.name),
            span(cls := "schema-field-type", field.typeName),
            span(cls := "schema-field-optional", if (field.optional) "optional" else "required"),
            renderAnnotations(field.annotations),
            if (field.targetId == ownerId) span(cls := "schema-field-self", "↺ self")
            else
              detailsTag(
                summaryTag("Inspect type"),
                renderNode(field.targetId, nodes, openVar, depth, visited),
              ),
          )
        },
      )

  private def renderCases(
    ownerId: String,
    cases: List[SchemaExplorer.Case],
    nodes: Map[String, SchemaExplorer.Node],
    openVar: Var[Set[String]],
    depth: Int,
    visited: Set[String],
  ): HtmlElement =
    if (cases.isEmpty) div()
    else
      div(
        cls := "schema-cases",
        h4("Cases"),
        cases.map { caze =>
          div(
            cls := "schema-case-row",
            span(cls := "schema-case-name", caze.name),
            renderAnnotations(caze.annotations),
            if (caze.targetId == ownerId) span(cls := "schema-field-self", "↺ self")
            else
              detailsTag(
                summaryTag("Inspect case"),
                renderNode(caze.targetId, nodes, openVar, depth, visited),
              ),
          )
        },
      )

  private def renderCollection(
    info: SchemaExplorer.CollectionInfo,
    nodes: Map[String, SchemaExplorer.Node],
    openVar: Var[Set[String]],
    depth: Int,
    visited: Set[String],
  ): HtmlElement =
    div(
      cls := "schema-collection",
      h4("Collection"),
      div(
        cls := "schema-collection-entry",
        span(cls := "schema-collection-type", info.elementTypeName),
        renderAnnotations(info.annotations),
        detailsTag(
          summaryTag("Inspect element"),
          renderNode(info.elementTargetId, nodes, openVar, depth, visited),
        ),
      ),
    )

  private def renderAnnotations(values: List[String]): HtmlElement =
    if (values.isEmpty) span()
    else span(cls := "schema-annotations", values.mkString("[", ", ", "]"))
}
