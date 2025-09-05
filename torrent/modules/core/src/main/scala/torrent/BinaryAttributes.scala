package torrent

import java.time.Instant

import zio.*
import zio.constraintless.TypeList
import zio.json.{ JsonDecoder, JsonEncoder }
import zio.schema.codec.{ BinaryCodec, JsonCodec }
import zio.schema.{ DynamicValue, Schema }

/**
 * Stores attributes of binary data using the type-safe Attribute system with
 * schema migration and audit trail support
 */
opaque type BinaryAttributes <: Attribute.AttributeMap = Attribute.AttributeMap

object BinaryAttributes:

  def apply(attributes: Attribute.AttributeMap): BinaryAttributes =
    attributes

  def apply[A <: TypeList](attributes: Map[String, DynamicValue]): BinaryAttributes =
    Attribute.AttributeMap.fromDynamicMap(attributes)

  given schema: Schema[BinaryAttributes] = Schema[Map[String, DynamicValue]]
    .transform(
      Attribute.AttributeMap.fromDynamicMap,
      _.toDynamicMap
    )

  // Use the same approach as AttributeMap to avoid DynamicValue JSON issues
  given JsonEncoder[BinaryAttributes]              = JsonEncoder[Attribute.AttributeMap].contramap(identity)
  given JsonDecoder[BinaryAttributes]              = JsonDecoder[Attribute.AttributeMap].map(identity)
  given binaryCodec: BinaryCodec[BinaryAttributes] = JsonCodec.schemaBasedBinaryCodec

  val empty: BinaryAttributes = Attribute.AttributeMap.empty

  def apply(attributes: (Attribute[?, ?], Any)*): BinaryAttributes =
    val builder = Attribute.AttributeMap.builder
    attributes
      .foldLeft(builder) { case (b, (attr, value)) =>
        // This is a bit unsafe but needed for the varargs approach
        b.add[Nothing, Any](attr.asInstanceOf[Attribute[Nothing, Any]], value)
      }
      .build

  extension (attr: BinaryAttributes)

    def withContentType(mimeType: MediaType): BinaryAttributes =
      attr.set(Attribute.ContentType, mimeType.toString)

    def withLength(length: Long): BinaryAttributes =
      attr.set(Attribute.Length, length)

    def withCreated(created: Instant): BinaryAttributes =
      attr.set(Attribute.Created, created)

    def withModified(modified: Instant): BinaryAttributes =
      attr.set(Attribute.Modified, modified)

    def withFileName(name: String): BinaryAttributes =
      attr.set(Attribute.FileName, name)

    def withValue[V](name: Attribute[?, V], value: V): BinaryAttributes =
      attr.set(name, value)

    def getValue[A](key: Attribute[?, A]): Option[A] =
      attr.get(key)

    def getValueMigrated[A](key: Attribute[?, A]): Option[A] =
      attr.getMigrated(key)

    def getOrFail[A](key: Attribute[?, A]): IO[String, A] =
      attr.getOrFail(key)

    def contains[A](key: Attribute[?, A]): Boolean =
      attr.contains(key)

    def remove[A](key: Attribute[?, A]): BinaryAttributes =
      attr.remove(key)

    def ++(other: BinaryAttributes): BinaryAttributes =
      attr ++ other

    // Audit trail methods removed in simplified version
    def getAuditTrail[Name <: String, A: Schema](
      attributeName: Name
    )(using schema: Schema[Attribute.AttributeEntry[Name, A]]): Chunk[Attribute.AttributeEntry[Name, A]] =
      attr.toAuditableMap
        .get(attributeName)
        .map {
          case DynamicValue.Sequence(entries) =>
            entries.collect { case dv @ DynamicValue.Record(fields, _) =>
              dv.toTypedValue[Attribute.AttributeEntry[Name, A]].toOption
            }.flatten
          case _                              => Chunk.empty
        }
        .getOrElse(Chunk.empty)

    def getLatestEntry[Name <: String, A: Schema](
      attributeName: Name
    )(using schema: Schema[Attribute.AttributeEntry[Name, A]]): Option[Attribute.AttributeEntry[Name, A]] =
      attr.toAuditableMap
        .get(attributeName)
        .collect {
          case DynamicValue.Sequence(entries) if entries.nonEmpty =>
            entries.lastOption.flatMap {
              case dv @ DynamicValue.Record(_, _) =>
                dv.toTypedValue[Attribute.AttributeEntry[Name, A]].toOption
              case _                              => None
            }
          case _                                                  => None
        }
        .flatten

    def toAuditableMap: Map[String, DynamicValue] =
      attr.underlying.map { case (key, value) =>
        key.name -> value
      }

end BinaryAttributes
