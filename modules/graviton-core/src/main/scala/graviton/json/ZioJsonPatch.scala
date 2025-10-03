package graviton.json

import cats.instances.either.*
import diffson.*
import diffson.jsonmergepatch.*
import diffson.jsonpatch.*
import diffson.jsonpatch.simplediff.remembering.given
import zio.*
import zio.Chunk
import zio.json.ast.Json

object ZioJsonPatch:

  enum PatchError(message: String) extends Exception(message):
    case InvalidPatch(details: String) extends PatchError(details)
    case ApplyFailed(details: String)  extends PatchError(details)

  private def throwableMessage(t: Throwable): String =
    Option(t.getMessage).filter(_.nonEmpty).getOrElse(t.toString)

  given Jsony[Json] with
    override def makeObject(fields: Map[String, Json]): Json =
      Json.Obj(Chunk.fromIterable(fields))

    override def fields(json: Json): Option[Map[String, Json]] =
      json match
        case Json.Obj(values) =>
          val builder = Map.newBuilder[String, Json]
          values.foreach(builder += _)
          Some(builder.result())
        case _                => None

    override def makeArray(values: Vector[Json]): Json =
      Json.Arr(Chunk.fromIterable(values))

    override def array(json: Json): Option[Vector[Json]] =
      json match
        case Json.Arr(values) => Some(values.toVector)
        case _                => None

    override def Null: Json = Json.Null

    override def eqv(x: Json, y: Json): Boolean = x == y

    override def show(value: Json): String = value.toString

  def diff(original: Json, updated: Json): UIO[JsonPatch[Json]] =
    ZIO.succeed(diffson.diff(original, updated))

  def applyPatch(document: Json, patch: JsonPatch[Json]): IO[PatchError, Json] =
    type Attempt[A] = Either[Throwable, A]
    val attempted: Attempt[Json] = patch[Attempt](document)
    ZIO.fromEither(
      attempted.left.map {
        case err: PatchError => err
        case other           => PatchError.ApplyFailed(throwableMessage(other))
      }
    )

  def applyMergePatch(document: Json, merge: JsonMergePatch[Json]): IO[PatchError, Json] =
    type Attempt[A] = Either[Throwable, A]
    val attempted: Attempt[Json] = merge[Attempt](document)
    ZIO.fromEither(
      attempted.left.map {
        case err: PatchError => err
        case other           => PatchError.ApplyFailed(throwableMessage(other))
      }
    )
