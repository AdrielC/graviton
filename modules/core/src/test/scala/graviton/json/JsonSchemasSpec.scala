package graviton.json

import json.Schema
import json.Schema.`object`
import json.Schema.`object`.Field
import zio.json.ast.Json
import zio.test.*

object JsonSchemasSpec extends ZIOSpecDefault:

  private val sampleSchema =
    `object`[Unit](
      Field("id", Schema.`string`[String]),
      Field("size", Schema.`number`[Long]),
      Field("mime", Schema.`string`[String]),
    )

  def spec =
    suite("JsonSchemas")(
      test("render Draft07 schema as Json AST with expected properties") {
        val schemaResult = JsonSchemas.renderJsonDraft07(sampleSchema)
        val assertion    = schemaResult match
          case Right(Json.Obj(fields)) =>
            val root          = fields.toMap
            val hasObjectType = root.get("type").contains(Json.Str("object"))
            val propertiesOk  = root.get("properties").exists {
              case Json.Obj(props) =>
                val propMap = props.toMap
                propMap.contains("id") && propMap.contains("size") && propMap.contains("mime")
              case _               => false
            }
            hasObjectType && propertiesOk
          case _                       => false
        assertTrue(assertion)
      }
    )
