package graviton.frontend.components

import com.raquo.laminar.api.L.*
import graviton.frontend.GravitonApi
import graviton.shared.ApiModels.*
import zio.*

import scala.concurrent.ExecutionContext.Implicits.global

/** Interactive schema explorer backed by the Scala.js + ZIO runtime. */
object SchemaViewer {

  def apply(api: GravitonApi): HtmlElement = {
    val schemasVar  = Var(List.empty[ObjectSchema])
    val filterVar   = Var("")
    val selectedVar = Var(Option.empty[String])
    val loadingVar  = Var(true)
    val errorVar    = Var(Option.empty[String])
    val runtime     = Runtime.default

    def loadSchemas(): Unit = {
      loadingVar.set(true)
      errorVar.set(None)

      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.listSchemas).onComplete {
          case scala.util.Success(list) =>
            schemasVar.set(list)
            if (selectedVar.now().isEmpty) {
              selectedVar.set(list.headOption.map(_.name))
            }
            loadingVar.set(false)
          case scala.util.Failure(err)  =>
            errorVar.set(Some(err.getMessage))
            loadingVar.set(false)
        }
      }
    }

    def schemaMatches(schema: ObjectSchema, query: String): Boolean = {
      val lower = query.toLowerCase
      schema.name.toLowerCase.contains(lower) ||
      schema.category.toLowerCase.contains(lower) ||
      schema.summary.exists(_.toLowerCase.contains(lower)) ||
      schema.fields.exists { field =>
        field.name.toLowerCase.contains(lower) ||
        field.dataType.toLowerCase.contains(lower)
      }
    }

    val filteredSchemasSignal: Signal[List[ObjectSchema]] =
      schemasVar.signal.combineWith(filterVar.signal).map { (schemas, filter) =>
        val trimmed = filter.trim
        if (trimmed.isEmpty) schemas
        else schemas.filter(schemaMatches(_, trimmed))
      }

    val selectedSchemaSignal: Signal[Option[ObjectSchema]] =
      selectedVar.signal.combineWith(schemasVar.signal).map { (maybeName, schemas) =>
        maybeName.flatMap(name => schemas.find(_.name == name))
      }

    div(
      cls := "schema-viewer",
      onMountCallback(_ => loadSchemas()),
      h2("🧬 Schema Explorer"),
      p(
        cls := "schema-subtitle",
        "Browse the structures Graviton exposes over its APIs. Everything you see here runs with Scala.js + ZIO in the browser.",
      ),
      div(
        cls := "schema-controls",
        input(
          cls         := "schema-search",
          tpe         := "search",
          placeholder := "Filter by name, category, or field…",
          controlled(
            value <-- filterVar.signal,
            onInput.mapToValue --> filterVar.writer,
          ),
        ),
        button(
          cls         := "schema-reload",
          "↻ Refresh",
          onClick.mapTo(()) --> (_ => loadSchemas()),
          disabled <-- loadingVar.signal,
        ),
      ),
      child <-- api.offlineSignal.map { offline =>
        if (!offline) emptyNode
        else div(cls := "schema-offline", "Demo data loaded locally. Start a Graviton node to pull live schemas.")
      },
      child <-- errorVar.signal.map {
        case None        => emptyNode
        case Some(error) => div(cls := "schema-error", s"⚠️ $error")
      },
      child <-- loadingVar.signal.map { loading =>
        if (loading) div(cls := "schema-loading", "⏳ Fetching schema catalog…")
        else emptyNode
      },
      div(
        cls := "schema-layout",
        div(
          cls := "schema-grid",
          children <-- filteredSchemasSignal.split(_.name) { (name, _, schemaSignal) =>
            val fieldsCountSignal = schemaSignal.map(_.fields.length)
            val categorySignal    = schemaSignal.map(_.category)
            val versionSignal     = schemaSignal.map(_.version)

            div(
              cls := "schema-card",
              cls("active") <-- selectedVar.signal.map(_.contains(name)),
              onClick.mapTo(Some(name)) --> selectedVar.writer,
              h3(child.text <-- schemaSignal.map(_.name)),
              p(
                cls := "schema-meta",
                span(child.text <-- categorySignal),
                " • ",
                span(child.text <-- versionSignal),
              ),
              p(
                cls := "schema-summary",
                child.text <-- schemaSignal.map(_.summary.getOrElse("No summary provided.")),
              ),
              div(
                cls := "schema-stats",
                span(child.text <-- fieldsCountSignal.map(count => s"Fields: $count")),
              ),
            )
          },
          child <-- filteredSchemasSignal.combineWith(loadingVar.signal).map { (schemas, loading) =>
            if (schemas.isEmpty && !loading)
              div(cls := "schema-empty", "No schemas match that filter.")
            else emptyNode
          },
        ),
        child <-- selectedSchemaSignal.map {
          case Some(schema) =>
            div(
              cls := "schema-detail",
              h3(schema.name),
              div(
                cls := "schema-detail-meta",
                span(cls := "schema-chip", s"Category: ${schema.category}"),
                span(cls := "schema-chip", s"Version: ${schema.version}"),
                schema.summary.map(text => span(cls := "schema-summary-inline", text)).getOrElse(emptyNode),
              ),
              div(
                cls := "table-scroll schema-fields-table-wrapper",
                table(
                  cls := "schema-fields-table",
                  thead(
                    tr(
                      th("Field"),
                      th("Type"),
                      th("Cardinality"),
                      th("Nullable"),
                      th("Description"),
                    )
                  ),
                  tbody(
                    schema.fields.map { field =>
                      tr(
                        td(field.name),
                        td(field.dataType),
                        td(field.cardinality),
                        td(if field.nullable then "Yes" else "No"),
                        td(field.description.getOrElse("—")),
                      )
                    }
                  ),
                ),
              ),
              schema.sampleJson.fold(emptyNode) { json =>
                div(
                  cls := "schema-sample",
                  h4("Sample JSON"),
                  pre(code(json)),
                )
              },
            )
          case None         =>
            div(
              cls := "schema-detail-placeholder",
              p("Select a schema to inspect its fields."),
            )
        },
      ),
    )
  }
}
