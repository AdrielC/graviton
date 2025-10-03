package graviton.objectstore

import graviton.ranges.ByteRange
import zio.*
import zio.stream.*
import java.time.Instant

final case class ObjectPath private (asString: String) extends AnyVal:
  def /(child: String): ObjectPath =
    ObjectPath(if asString.endsWith("/") then s"$asString$child" else s"$asString/$child")

object ObjectPath:
  def apply(path: String): ObjectPath =
    val normalised = path match
      case ""      => ""
      case current => current.stripPrefix("/")
    new ObjectPath(normalised)

final case class ObjectMetadata(
  size: Long,
  eTag: Option[String],
  lastModified: Option[Instant],
  contentType: Option[String],
  userMetadata: Map[String, String],
)

final case class ListedObject(path: ObjectPath, size: Long, isPrefix: Boolean)

final case class PutMetadata(
  contentType: Option[String] = None,
  userMetadata: Map[String, String] = Map.empty,
)

object PutMetadata:
  val empty: PutMetadata = PutMetadata()

final case class CopyOptions(
  overwrite: Boolean = true,
  metadataOverride: Option[PutMetadata] = None,
)

object CopyOptions:
  val default: CopyOptions = CopyOptions()

sealed trait ObjectStoreError extends Throwable:
  def message: String
  override def getMessage: String = message

object ObjectStoreError:
  final case class Missing(path: ObjectPath) extends ObjectStoreError:
    val message: String = s"object not found at ${path.asString}"

  final case class AccessDenied(message: String) extends ObjectStoreError

  final case class Unexpected(message: String, cause: Option[Throwable] = None) extends ObjectStoreError:
    override def getCause: Throwable | Null = cause.orNull

  def fromThrowable(t: Throwable): ObjectStoreError = Unexpected(t.getMessage, Some(t))

trait ObjectStore:
  def head(path: ObjectPath): IO[ObjectStoreError, Option[ObjectMetadata]]

  def list(prefix: ObjectPath, recursive: Boolean = true): ZStream[Any, ObjectStoreError, ListedObject]

  def get(path: ObjectPath, range: Option[ByteRange] = None): ZStream[Any, ObjectStoreError, Byte]

  def put(path: ObjectPath, data: ZStream[Any, Throwable, Byte], metadata: PutMetadata = PutMetadata.empty): IO[ObjectStoreError, Unit]

  def putMultipart(
    path: ObjectPath,
    parts: ZStream[Any, Throwable, Byte],
    metadata: PutMetadata = PutMetadata.empty,
  ): IO[ObjectStoreError, Unit]

  def delete(path: ObjectPath): IO[ObjectStoreError, Boolean]

  def copy(from: ObjectPath, to: ObjectPath, options: CopyOptions = CopyOptions.default): IO[ObjectStoreError, Unit]
