package quasar.core

import graviton.meta.{DynamicJsonCodec as DynJson, DynamicRecordCodec}
import zio.Chunk
import zio.json.ast.Json
import zio.schema.{DynamicValue, Schema}

import java.time.OffsetDateTime

/**
 * Quasar Metadata Envelope v1.1 (in-memory model).
 *
 * Design intent:
 * - Namespace payloads are held as [[zio.schema.DynamicValue.Record]] for lossless, schema-aware conversion.
 * - Typed decoding is only permitted when `status == Valid`.
 *
 * JSON shape is specified in `docs/design/quasar-metadata-envelope-v1.1.md`.
 */
final case class MetadataEnvelopeV1_1(
  system: MetadataEnvelopeV1_1.System,
  namespaces: Map[NamespaceUrn, MetadataEnvelopeV1_1.NamespaceEntry],
)

object MetadataEnvelopeV1_1:

  val EnvelopeIri: String = "urn:quasar:meta-envelope:v1.1"

  final case class System(
    envelope: String = EnvelopeIri,
    createdAt: OffsetDateTime,
    createdBy: CreatedBy,
    updatedAt: OffsetDateTime,
    source: Source,
  )

  final case class CreatedBy(
    principal: String
  )

  final case class Source(
    ingest: Option[String] = None,
    requestId: String,
  )

  enum Status:
    case valid, quarantined, unverified

  /**
   * Schema IRI wrapper in the envelope shape:
   *
   * {{{
   * "schema": { "$id": "<schema-iri>" }
   * }}}
   */
  final case class SchemaRef(
    id: SchemaUrn
  )

  final case class ErrorDiagnostic(
    path: String,
    code: String,
    message: String,
  )

  final case class NamespaceEntry(
    schema: Option[SchemaRef],
    status: Status,
    data: DynamicValue.Record,
    errors: Chunk[ErrorDiagnostic] = Chunk.empty,
  ):

    /**
     * Safe invariant: typed decoding is only allowed when the entry is marked valid.
     *
     * Returns:
     * - Left(...) if status is not valid or decoding fails
     * - Right(value) if status is valid and decoding succeeds
     */
    def decodeIfValid[A](schemaA: Schema[A]): Either[String, A] =
      status match
        case Status.valid       =>
          DynamicRecordCodec.fromRecord(schemaA, data)
        case Status.quarantined =>
          Left("Metadata is quarantined; typed decoding is forbidden.")
        case Status.unverified  =>
          Left("Metadata is unverified; typed decoding is forbidden.")

    /** Encode `data` as JSON object (schema-agnostic, lossless for JSON-compatible values). */
    def dataAsJsonObject: Either[String, Json.Obj] =
      DynJson.recordToJson(data)

  object NamespaceEntry:

    /** Build a namespace entry from a JSON object payload (enforces object-root). */
    def fromJsonObject(
      schema: Option[SchemaRef],
      status: Status,
      data: Json.Obj,
      errors: Chunk[ErrorDiagnostic] = Chunk.empty,
    ): Either[String, NamespaceEntry] =
      for record <- DynJson.jsonToRecord(data)
      yield NamespaceEntry(schema = schema, status = status, data = record, errors = errors)
