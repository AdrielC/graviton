package graviton.json

import com.github.andyglow.json.JsonFormatter
import com.github.andyglow.jsonschema.{AsValue, AsValueBuilder}
import json.Schema
import json.schema.Version
import json.schema.Version.Draft07
import zio.json.JsonDecoder
import zio.json.ast.Json

object JsonSchemas:

  private val DefaultSchemaId = "https://graviton.io/schema/anonymous"

  def renderString[T, V <: Version](schema: Schema[T], version: V)(using AsValueBuilder[V]): String =
    JsonFormatter.format(AsValue.schema(schema, version))

  def renderStringDraft07[T](schema: Schema[T], id: String = DefaultSchemaId): String =
    renderString(schema, Draft07(id))

  def renderJson[T, V <: Version](schema: Schema[T], version: V)(using AsValueBuilder[V]): Either[String, Json] =
    JsonDecoder[Json].decodeJson(renderString(schema, version))

  def renderJsonDraft07[T](schema: Schema[T], id: String = DefaultSchemaId): Either[String, Json] =
    renderJson(schema, Draft07(id))
