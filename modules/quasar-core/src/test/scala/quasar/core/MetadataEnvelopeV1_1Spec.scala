package quasar.core

import quasar.core.MetadataEnvelopeV1_1.*
import zio.schema.DeriveSchema
import zio.test.*

object MetadataEnvelopeV1_1Spec extends ZIOSpecDefault:

  final case class UploadMeta(originalFileName: String, contentType: String)
  object UploadMeta:
    given zio.schema.Schema[UploadMeta] = DeriveSchema.gen[UploadMeta]

  override def spec: Spec[TestEnvironment, Any] =
    suite("MetadataEnvelopeV1_1")(
      test("decodeIfValid forbids typed decoding when quarantined") {
        val entry = NamespaceEntry(
          schema = None,
          status = Status.quarantined,
          data = zio.schema.DynamicValue.Record(zio.schema.TypeId.Structural, scala.collection.immutable.ListMap.empty),
        )

        assertTrue(entry.decodeIfValid(UploadMeta.given_Schema_UploadMeta).isLeft)
      },
      test("decodeIfValid forbids typed decoding when unverified") {
        val entry = NamespaceEntry(
          schema = None,
          status = Status.unverified,
          data = zio.schema.DynamicValue.Record(zio.schema.TypeId.Structural, scala.collection.immutable.ListMap.empty),
        )

        assertTrue(entry.decodeIfValid(UploadMeta.given_Schema_UploadMeta).isLeft)
      },
    )
