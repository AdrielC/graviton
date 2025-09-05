package torrent

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import scala.collection.immutable.ListMap

import zio.*
import zio.constraintless.TypeList.{ ::, End }
import zio.json.*
import zio.schema.codec.BinaryCodecs
import zio.schema.meta.{ ExtensibleMetaSchema, MetaSchema, Migration, SchemaInstances }
import zio.schema.syntax.*
import zio.schema.{ DeriveSchema, DynamicValue, Schema, TypeId, derived }

export Attribute.Hint

/**
 * Type-safe attribute keys for heterogeneous maps with schema migration support
 *
 * Each Attribute[A] captures both the type A and its Schema, enabling:
 *   - Type-safe retrieval with automatic schema migration
 *   - Pattern matching over known attributes
 *   - Extensible attribute definitions
 *   - Auditability with multiple versioned values
 *   - Build info tracking for provenance
 */
enum Attribute[+Name <: String: ValueOf, +A: {Schema, Tag}] derives Schema:

  def name: Name = valueOf[Name]

  // Standard binary attributes as case objects for pattern matching
  case Length      extends Attribute["length", Long]
  case FileName    extends Attribute["file-name", String]
  case ContentType extends Attribute["content-type", String]

  case Modified       extends Attribute["modified", Instant]
  case Created        extends Attribute["created", Instant]
  case Sha256         extends Attribute["sha-256", Bytes]
  case Md5            extends Attribute["md5", Bytes]
  case Size           extends Attribute["size", Long]
  case Checksum       extends Attribute["checksum", String]
  case Encoding       extends Attribute["encoding", String]
  case Language       extends Attribute["language", String]
  case Author         extends Attribute["author", String]
  case Title          extends Attribute["title", String]
  case Subject        extends Attribute["subject", String]
  case Keywords       extends Attribute["keywords", List[String]]
  case PageCount      extends Attribute["page-count", Int]
  case WordCount      extends Attribute["word-count", Int]
  case Application    extends Attribute["application", String]
  case Version        extends Attribute["version", String]
  // Build and audit attributes
  case BuildVersion   extends Attribute["build-version", String]
  case BuildTimestamp extends Attribute["build-timestamp", Instant]
  case BuildCommit    extends Attribute["build-commit", String]
  case ProcessedBy    extends Attribute["processed-by", String]
  case ProcessedAt    extends Attribute["processed-at", Instant]

  override lazy val hashCode: Int =
    typeId.hashCode ^ schema.hashCode

  protected lazy val tag: Tag[? <: A] = Tag[A]

  protected lazy val schema: Schema[? <: A] = Schema[A]

  lazy val typeId: TypeId.Nominal =
    TypeId.parse(s"torrent.Attribute.[\"${name}\", ${tag.tag}]") match
      case t @ TypeId.Nominal(_, _, _) => t
      case TypeId.Structural           => TypeId.Nominal(Chunk("torrent"), Chunk("Attribute"), name)

  protected lazy val ast: MetaSchema = schema.ast

  override def equals(other: Any): Boolean = other match
    case that: Attribute[?, ?] =>
      (name == that.name) &&
      (((tag <:< that.tag) | (tag =:= that.tag)) ||
        migrate(that).isRight)
    case _                     => false

  /**
   * Attempt to extract and migrate a typed value from DynamicValue
   */
  def extract(value: DynamicValue): Either[String, A] =
    value.toTypedValue[A]

  def migrate(other: Attribute[?, ?]): Either[String, Chunk[Migration]] =
    if migrationDB.containsKey(other.hashCode) then Right(migrationDB.get(other.hashCode))
    else
      val migrations = Migration.derive(this.ast, other.ast)
      migrations.foreach(migrationDB.put(other.hashCode, _))
      migrations

  private lazy val migrationDB: ConcurrentHashMap[Int, Chunk[Migration]] = ConcurrentHashMap()

  /**
   * Attempt to migrate a DynamicValue
   */
  def extractDynamic(key: Attribute[?, ?], value: DynamicValue): Either[String, DynamicValue] =
    for
      migrations <- migrate(key)
      value      <- migrations.foldLeft[Either[String, DynamicValue]](Right(value)): (acc, migration) =>
                      acc.flatMap(migration.migrate)
    yield value

  /**
   * Attempt to extract and migrate a typed value from DynamicValue
   */
  def extract(key: Attribute[?, ?], value: DynamicValue): Either[String, A] =
    extractDynamic(key, value).flatMap(extract)

  /**
   * Migrate from another schema to this one
   */
  def migrate(value: DynamicValue): Either[String, A] =
    value.migrate[A]

  /**
   * Migrate from another schema to this one
   */
  private[torrent] def toDynamic[AA >: A](value: AA): DynamicValue =
    value.asInstanceOf[A].dynamic

object Attribute:

  val valuesMap: Map[String, Attribute[String, ?]] =
    values.map(a => a.name -> a).toMap

  def fromName(name: String): Option[Attribute[String, ?]] =
    valuesMap.get(name)

  given [N <: String, A] => Schema[Attribute[N, A]] =
    DeriveSchema.gen[Attribute[N, A]]

  object codecs:

    import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

    type AttributeTypes = String :: Long :: List[String] :: Bytes :: Int :: Instant :: End

    given binaryCodecs: BinaryCodecs[AttributeTypes] = BinaryCodecs.make[AttributeTypes]

    given extensibleMetaSchema: Schema[ExtensibleMetaSchema[AttributeTypes]] =
      ExtensibleMetaSchema.schema[AttributeTypes]

    given schema: Schema[DynamicValue] =
      extensibleMetaSchema.coerce(Schema[DynamicValue]).toOption.getOrElse(Schema[DynamicValue])

  end codecs

  /**
   * Auditable attribute entry with metadata
   */
  case class AttributeEntry[+Name <: String, A](
    attribute: Attribute[? <: Name, A],
    value:     A,
    timestamp: Instant = Instant.now(),
    buildInfo: Option[BuildInfo] = None,
    source:    Option[String] = None
  ):

    def name: Name = attribute.asInstanceOf[Attribute[Name, A]].name

    def toDynamic: (String, DynamicValue) =
      attribute.name -> attribute.toDynamic(value)

    def toAuditableDynamic: (String, DynamicValue) =
      val auditData = Map(
        "value"     -> attribute.toDynamic(value),
        "timestamp" -> Schema[Instant].toDynamic(timestamp),
        "buildInfo" -> buildInfo.map(Schema[BuildInfo].toDynamic).getOrElse(DynamicValue.NoneValue),
        "source"    -> source.map(Schema[String].toDynamic).getOrElse(DynamicValue.NoneValue)
      )
      attribute.name -> Schema[Map[String, DynamicValue]].toDynamic(auditData)

  end AttributeEntry

  object AttributeEntry:

    def fromDynamicValue[Name <: String, A: Schema](dv: DynamicValue)(using
      schema: Schema[AttributeEntry[Name, A]]
    ): Option[AttributeEntry[Name, A]] =
      dv.toTypedValue[AttributeEntry[Name, A]].toOption

  end AttributeEntry

  /**
   * Build information for provenance tracking
   */
  case class BuildInfo(
    version:      String,
    commit:       String,
    timestamp:    Instant,
    scalaVersion: String = scala.util.Properties.versionString,
    javaVersion:  String = java.lang.System.getProperty("java.version")
  )

  object BuildInfo:
    // Simple schema for BuildInfo - ZIO Schema will derive it automatically
    // Simple manual schema for BuildInfo
    given Schema[BuildInfo] = DeriveSchema.gen[BuildInfo]

    /**
     * Create BuildInfo from the sbt-buildinfo plugin
     */
    def fromSbtBuildInfo: BuildInfo =
      try {
        val sbtBuildInfo = Class.forName("torrent.build.BuildInfo")
        val version      = Option(sbtBuildInfo.getField("version").get("").asInstanceOf[String]).getOrElse("")
        val commit       = Option(sbtBuildInfo.getField("gitCommit").get("").asInstanceOf[String]).getOrElse("")
        val buildTimeStr = Option(sbtBuildInfo.getField("buildTime").get("").asInstanceOf[String]).getOrElse("")
        val timestamp    = java.time.Instant.parse(buildTimeStr)
        val scalaVersion = Option(sbtBuildInfo.getField("scalaVersion").get("").asInstanceOf[String]).getOrElse("")

        BuildInfo(
          version = version,
          commit = commit,
          timestamp = timestamp,
          scalaVersion = scalaVersion,
          javaVersion = java.lang.System.getProperty("java.version")
        )
      } catch {
        case _: Exception =>
          // Fallback if build info is not available
          BuildInfo(
            version = "unknown",
            commit = "unknown",
            timestamp = Instant.now(),
            scalaVersion = scala.util.Properties.versionString,
            javaVersion = java.lang.System.getProperty("java.version")
          )
      }

    /**
     * Current build info instance
     */
    lazy val current: BuildInfo = fromSbtBuildInfo

  import Attribute.codecs.given

  /**
   * Type-safe heterogeneous map with schema migration and audit support
   */
  case class AttributeMap(
    underlying: ListMap[Attribute[String, ?], DynamicValue]
  ) derives Schema:

    /**
     * Get a typed value by attribute with automatic schema migration
     */
    def get[A](attr: Attribute[String, A]): Option[A] =
      underlying.get(attr).flatMap(attr.extract(_).toOption)

    /**
     * Get with schema migration from a different type
     */
    def getMigrated[A, B](attr: Attribute[?, A]): Option[A] =
      underlying.toSeq.findLast(a => a._1 == attr).flatMap(v => attr.extract(v._1, v._2).toOption)

    /**
     * Get a typed value or fail with an error
     */
    def getOrFail[A](attr: Attribute[?, A]): IO[String, A] =
      ZIO.fromOption(get(attr)).orElseFail(s"Attribute '${attr.name}' not found")

    /**
     * Set a typed value
     */
    def set[A](attr: Attribute[?, A], value: A): AttributeMap =
      copy(underlying = underlying.updated(attr, attr.toDynamic(value)))

    /**
     * Update a typed value if it exists
     */
    def update[A](attr: Attribute[?, A])(f: A => A): AttributeMap =
      get(attr) match
        case Some(value) => set(attr, f(value))
        case None        => this

    /**
     * Remove an attribute
     */
    def remove[A](attr: Attribute[?, A]): AttributeMap =
      copy(underlying = underlying.removed(attr))

    /**
     * Check if an attribute exists
     */
    def contains[A](attr: Attribute[?, A]): Boolean =
      underlying.contains(attr)

    /**
     * Get all attribute names
     */
    def keys: Set[String] = underlying.keySet.map(_.name)

    /**
     * Check if the map is empty
     */
    def isEmpty: Boolean = underlying.isEmpty

    /**
     * Get the size of the map
     */
    def size: Int = underlying.size

    /**
     * Merge with another attribute map
     */
    def ++(other: AttributeMap): AttributeMap =
      copy(underlying = underlying ++ other.underlying)

    /**
     * Convert to a regular Map[String, DynamicValue]
     */
    def toDynamicMap: Map[String, DynamicValue] = underlying.map(a => a._1.name -> a._2)

    given JsonEncoder[DynamicValue] = zio.schema.codec.JsonCodec.jsonEncoder(DynamicValue.schema)

    /**
     * Convert to a Map[String, String] for display purposes
     */
    def toStringMap: Map[String, String] =
      underlying.map { case (k, v) => k.name -> v.toJsonPretty }

  object AttributeMap:

    // Provide explicit JSON codecs for AttributeMap that avoid DynamicValue JSON issues
    given JsonEncoder[AttributeMap] = zio.schema.codec.JsonCodec.jsonEncoder(Schema[AttributeMap])
    given JsonDecoder[AttributeMap] = zio.schema.codec.JsonCodec.jsonDecoder(Schema[AttributeMap])

    // Simple schema for AttributeMap
    given Schema[AttributeMap] =
      Schema[ListMap[String, DynamicValue]].transform(
        AttributeMap.fromDynamicMap,
        _.underlying.map(a => a._1.name -> a._2)
      )

    /**
     * Empty attribute map
     */
    val empty: AttributeMap = AttributeMap(ListMap.empty)

    /**
     * Create from a Map[String, DynamicValue]
     */
    def fromDynamicMap(map: Map[String, DynamicValue]): AttributeMap =
      AttributeMap(
        ListMap.from(
          map.flatMap(a => Attribute.fromName(a._1).map(_ -> a._2))
        )
      )

    /**
     * Builder for type-safe construction
     */
    def builder: AttributeMapBuilder[Nothing] = new AttributeMapBuilder(ListMap.empty)

  /**
   * Builder for type-safe attribute map construction
   */
  class AttributeMapBuilder[+A <: String](
    private val underlying: ListMap[Attribute[String, ?], DynamicValue]
  ):

    def add[K <: String, A](attr: Attribute[K, A], value: A): AttributeMapBuilder[K & A] =
      new AttributeMapBuilder(underlying.updated(attr, attr.toDynamic(value)))

    def build: AttributeMap = AttributeMap(underlying)

  /**
   * Extension methods for working with attributes
   */
  extension [Name <: String, A](attr: Attribute[Name, A])
    /**
     * Create a key-value pair for map construction
     */
    def :=(value: A): (Attribute[?, A], A) = attr -> value

    /**
     * Extract value from DynamicValue with ZIO error handling
     */
    def extractZIO(value: DynamicValue): IO[String, A] =
      ZIO.fromEither(attr.extract(value))

    /**
     * Create an auditable entry
     */
    def entry(value: A, buildInfo: Option[BuildInfo] = None, source: Option[String] = None): AttributeEntry[Name, A] =
      AttributeEntry(attr, value, Instant.now(), buildInfo, source)

  /**
   * Hint for providing context during binary operations
   */
  case class Hint(attributes: Attribute.AttributeMap):

    /**
     * Get a typed attribute value
     */
    def get[A](attr: Attribute[?, A]): Option[A] =
      attributes.get(attr)

    /**
     * Add an attribute with audit trail
     */
    def withAttribute[A](attr: Attribute[?, A], value: A): Hint =
      copy(attributes = attributes.set(attr, value))

    /**
     * Add an attribute without audit trail
     */
    def withAttributeQuiet[A](attr: Attribute[?, A], value: A): Hint =
      copy(attributes = attributes.set(attr, value))

  object Hint:

    /**
     * Empty hint
     */
    val empty: Hint = Hint(Attribute.AttributeMap.empty)

    /**
     * Create hint with filename
     */
    def filename(name: String): Hint =
      empty.withAttributeQuiet(Attribute.FileName, name)

    /**
     * Create hint with content type
     */
    def contentType(mediaType: String): Hint =
      empty.withAttributeQuiet(Attribute.ContentType, mediaType)

    /**
     * Create hint with size
     */
    def size(bytes: Long): Hint =
      empty.withAttributeQuiet(Attribute.Size, bytes)

end Attribute

@main def testBinaryAttributes =
  // val hint = Hint.empty
  println(Schema[torrent.BinaryAttributes].serializable.ast)
  println(zio.schema.meta.MetaSchema.fromSchema(Attribute.given_Schema_Attribute["hello", String]))
  println(Attribute.Checksum.typeId.fullyQualified)
