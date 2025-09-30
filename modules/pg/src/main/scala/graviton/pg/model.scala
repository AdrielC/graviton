package graviton.pg

import zio.*
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import com.augustnagro.magnum.*
import com.augustnagro.magnum.pg.json.*
import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.schema.Schema
import zio.schema.DynamicValue

trait RefinedTypeExt[A, C] extends RefinedType[A, C]:

  given (using d: DbCodec[A]): DbCodec[T] = 
    d.biMap(applyUnsafe(_), _.value)

  given (using s: Schema[A]): Schema[T]   = s
    .annotate(rtc.message)
    .transformOrFail(either(_), v => Right(v.value))

end RefinedTypeExt

trait RefinedSubTypeExt[A, C] extends RefinedSubtype[A, C]:

  given (using d: DbCodec[A]): DbCodec[T] = d.biMap(applyUnsafe(_), _.value)
  given (using s: Schema[A]): Schema[T]   = s
    .annotate(rtc.message)
    .transformOrFail(either(_), v => Right(v.value))

end RefinedSubTypeExt

opaque type JsonObject =
  DynamicValue.Record | DynamicValue.Dictionary | DynamicValue.DynamicAst | DynamicValue.Tuple | DynamicValue.Enumeration

// given DbCodec[JsonObject] = new JsonBDbCodec[JsonObject]:
//   import zio.schema.codec.BinaryCodecs
//   import zio.constraintless.TypeList.{::, End}

//   val enumSchema: Schema.EnumN[DynamicValue, CaseSet] =
//     DynamicValue.schema.asInstanceOf[Schema.EnumN[DynamicValue, CaseSet]]

//   val cases = enumSchema.cases.toMap

//   given Schema[DynamicValue.Record] =
//     DynamicValue.schema match
//       case s @ Schema.EnumN(_, cases, _) =>
//         cases.toMap.get(Name) match
//           casesMap.get(Name) match
//             case Some(schema) => schema
//             case None => throw new IllegalArgumentException(s"Schema for $Name not found")

//         given Schema[DynamicValue.Record] =

//     Schema.CaseClass2[TypeId, Chunk[(String, DynamicValue)], DynamicValue.Record](
//       TypeId.parse("zio.schema.DynamicValue.Record"),
//       Schema.Field("id", Schema[TypeId], get0 = record => record.id, set0 = (record, id) => record.copy(id = id)),
//       Schema
//         .Field(
//           "values",
//           Schema.defer(Schema.chunk(using Schema.tuple2(using Schema.primitive[String], DynamicValue.schema))),
//             get0 = record => Chunk.fromIterable(record.values),
//             set0 = (record, values) => record.copy(values = values.foldRight(ListMap.empty[String, DynamicValue])((a, b) => b + a))
//           ),
//         (id, chunk) => DynamicValue.Record(id, ListMap.from(chunk))
//       )

//   val codecs = BinaryCodecs.make[
//     DynamicValue.Record :: DynamicValue.Dictionary :: DynamicValue.DynamicAst :: DynamicValue.Tuple :: DynamicValue.Enumeration :: End
//   ]

//   def encode(a: JsonObject): String = a.toJson
//   def decode(json: String): JsonObject =
//     json
//       .fromJson[JsonObject]
//       .fold(err => throw IllegalArgumentException(err), identity)
// end given

type ExactLength[N <: Int] = Length[StrictEqual[N]]

// ---- Refined byte types

type HashBytes  = Chunk[Byte] :| (MinLength[16] & MaxLength[64])
type SmallBytes = Chunk[Byte] :| MaxLength[1048576]

type StoreKey = StoreKey.T
object StoreKey extends RefinedSubTypeExt[Chunk[Byte], ExactLength[32]]

type PosLong    = Long :| Positive
type NonNegLong = Long :| Not[Negative]

object Algo:
  enum Id derives CanEqual, DbCodec:
    case Blake3, Sha256, Sha1, Md5

@Table(PostgresDbType, SqlNameMapper.CamelToUpperSnakeCase)
enum StoreStatus derives CanEqual, DbCodec:
  case Active
  case Paused
  case Retired

@Table(PostgresDbType, SqlNameMapper.CamelToUpperSnakeCase)
enum LocationStatus derives CanEqual, DbCodec:
  case Active
  case Stale
  case Missing
  case Deprecated
  case Error

final case class BlockKey(algoId: Short, hash: HashBytes) derives DbCodec

final case class BlobKey(
  algoId: Short,
  hash: HashBytes,
  size: PosLong,
  mediaTypeHint: Option[String],
) derives DbCodec

final case class BlobStoreRow(
  key: StoreKey,
  implId: String,
  buildFp: Chunk[Byte],
  dvSchemaUrn: String,
  dvCanonical: Chunk[Byte],
  dvJsonPreview: Option[Json],
  status: StoreStatus,
  version: Long,
) derives DbCodec

// ---- DbCodec instances for refined types

given DbCodec[Chunk[Byte]] =
  DbCodec[Array[Byte]].biMap(Chunk.fromArray, _.toArray)

inline given [T, C](using DbCodec[T], Constraint[T, C]): DbCodec[T :| C] =
  summon[DbCodec[T]].biMap(_.refineUnsafe[C], identity)

given DbCodec[java.util.UUID] =
  DbCodec[String].biMap(java.util.UUID.fromString, _.toString)

given DbCodec[PgRange[Long]] =
  DbCodec[String].biMap(
    s =>
      val stripped = s.stripPrefix("[").stripSuffix(")")
      val parts    = stripped.split(",", 2)
      val lower    = Option(parts.headOption.getOrElse(""))
        .filter(_.nonEmpty)
        .map(_.toLong)
      val upper    =
        if parts.length > 1 then Option(parts(1)).filter(_.nonEmpty).map(_.toLong) else None
      PgRange(lower, upper)
    ,
    r =>
      val lower = r.lower.map(_.toString).getOrElse("")
      val upper = r.upper.map(_.toString).getOrElse("")
      s"[$lower,$upper)",
  )

given JsonBDbCodec[Json] with
  def encode(a: Json): String    =
    a.toJson
  def decode(json: String): Json =
    json
      .fromJson[Json]
      .fold(err => throw IllegalArgumentException(err), identity)

object DynamicValueSchema:
  import zio.schema.meta.SchemaInstances
  import zio.constraintless.TypeList
  import TypeList.{::, End}
  import zio.schema.TypeId

  type ObjectTypes =
    DynamicValue.Record :: DynamicValue.Dictionary :: DynamicValue.DynamicAst :: DynamicValue.Tuple :: DynamicValue.Enumeration :: End

  def getBuiltInTypeId[BuiltIn <: TypeList](
    instances: SchemaInstances[BuiltIn],
    schema: Schema[?],
  ): Option[TypeId] =
    if (instances.all.contains(schema)) {
      schema match {
        case record: Schema.Record[_] => Some(record.id)
        case e: Schema.Enum[_]        => Some(e.id)
        case dyn: Schema.Dynamic      => Some(dyn.id)
        case _                        => None
      }
    } else None

  // private val builtInInstances = SchemaInstances.make[ObjectTypes]

  // def restrictCases(schema: Schema[DynamicValue], allowed: Set[String]): Schema[DynamicValue] =
  //   def labelOf(dv: DynamicValue): String =
  //     dv match
  //     case DynamicValue.Record      => "Record"
  //     case DynamicValue.Dictionary  => "Dictionary"
  //     case d: DynamicValue.DynamicAst  => d.ast.toSchema.migrate()
  //     case DynamicValue.Tuple(left, right)       => "Tuple"
  //     case DynamicValue.Enumeration(name, value) => name

  //   schema.transformOrFail(
  //     dv => if allowed.contains(labelOf(dv)) then Right(dv) else Left(s"Disallowed DynamicValue case: ${labelOf(dv)}"),
  //     dv => if allowed.contains(labelOf(dv)) then Right(dv) else Left(s"Disallowed DynamicValue case: ${labelOf(dv)}")
  //   )
