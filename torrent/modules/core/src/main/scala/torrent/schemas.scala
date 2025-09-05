package torrent

import scala.collection.immutable.ListMap
import scala.util.Try

import io.github.iltotore.iron.{ :|, RefinedType, RuntimeConstraint, refineEither }

import zio.*
import zio.schema.*
import zio.schema.annotation.description
import zio.schema.meta.Migration.LabelTransformation

object schemas:
  given Schema[LabelTransformation] = schemaZNothing.asInstanceOf[Schema[LabelTransformation]]

  given schemaListMap[K: Schema, A: Schema]: Schema[ListMap[K, A]] =
    Schema[Chunk[(K, A)]].transform(ListMap.from, Chunk.fromIterable)

  // NOTE: Schema.Fail would be more correct but that makes it unserializable currently
  given schemaZNothing: Schema[ZNothing] =
    Schema[Unit].transformOrFail[ZNothing](_ => Left("nothing"), (_: ZNothing) => Left("nothing"))

  given schemaDuration: Schema[Duration] = Schema.Primitive(StandardType.DurationType)

  given schemaUri: Schema[java.net.URI] = Schema[String].transformOrFail(
    s => Try(new java.net.URI(s)).toEither.left.map(_.getMessage),
    uri => Right(uri.toString)
  )

  trait RefinedTypeExt[A, C](using schema: Schema[A], rt: RuntimeConstraint[A, C]) extends RefinedType[A, C]:

    given Schema[T] =
      schema
        .transformOrFail(a => either(a), Right(_))
        .annotate(description(rt.message))

    val binaryCodec: zio.schema.codec.BinaryCodec[T] = zio.schema.codec.JsonCodec.schemaBasedBinaryCodec[T]
    given zio.json.JsonEncoder[T]                    = zio.schema.codec.JsonCodec.jsonEncoder(given_Schema_T)
    given zio.json.JsonDecoder[T]                    = zio.schema.codec.JsonCodec.jsonDecoder(given_Schema_T)

    // given Ordering[T] = given_Schema_T.ordering

  given [A: {Schema, ([a] =>> RuntimeConstraint[a, C])}, C] => Schema[A :| C] =
    val rt = summon[RuntimeConstraint[A, C]]
    Schema[A]
      .transformOrFail(a => Either.cond(rt.test(a), a.refineEither[C], rt.message).flatten, Right(_))
      .annotate(description(rt.message))

  given schemaThrowable: Schema[Throwable] =
    Schema.CaseClass4(
      TypeId.parse("java.lang.Throwable"),
      field01 = Schema.Field(
        "cause",
        Schema.defer(Schema[Option[Throwable]]),
        get0 = throwable => Option(throwable.getCause).filter(_ != throwable),
        set0 = (_: Throwable, _: Option[Throwable]) => ???
      ),
      field02 = Schema.Field(
        "message",
        Schema[Option[String]],
        get0 = throwable => Option(throwable.getMessage),
        set0 = (_: Throwable, _: Option[String]) => ???
      ),
      field03 = Schema.Field(
        "stackTrace",
        Schema[Chunk[String]],
        get0 = throwable => Chunk.fromArray(throwable.getStackTrace.map(_.toString)),
        set0 = (_: Throwable, _: Chunk[String]) => ???
      ),
      field04 = Schema.Field(
        "suppressed",
        Schema.defer(Schema[Chunk[Throwable]]),
        get0 = throwable => Chunk.fromArray(throwable.getSuppressed),
        set0 = (_: Throwable, _: Chunk[Throwable]) => ???
      ),
      construct0 = (
        cause:      Option[Throwable],
        message:    Option[String],
        _:          Chunk[String],
        suppressed: Chunk[Throwable]
      ) => {
        val throwable = new Throwable(message.orNull, cause.orNull)
        // For simplicity, we'll create empty stack trace elements
        throwable.setStackTrace(Array.empty[StackTraceElement])
        suppressed.foreach(throwable.addSuppressed)
        throwable
      }
    )

export schemas.given
