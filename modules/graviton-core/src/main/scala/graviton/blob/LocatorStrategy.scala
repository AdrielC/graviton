package graviton.blob

import graviton.blob.Types.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.Constraint
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

/**
 * Strategy responsible for deriving deterministic [[BlobLocator]] values
 * from content identities.
 */
trait LocatorStrategy:
  def iso: LocatorStrategy.Iso

  final def locatorFor(key: BinaryKey): BlobLocator = iso.forward(key)

  final def keyFor(locator: BlobLocator): Either[String, BinaryKey] = iso.reverse(locator)

object LocatorStrategy:

  inline private def refineOrThrow[A, C](value: A)(using Constraint[A, C]): A :| C =
    value.refineEither[C].fold(err => throw new IllegalArgumentException(err), identity)

  private val DefaultFanOutSegments: NonNegativeInt     = refineOrThrow[Int, numeric.GreaterEqual[0]](2)
  private val DefaultSegmentLength: PositiveInt         = refineOrThrow[Int, numeric.Greater[0]](2)
  private val DefaultChunkDirectoryName: PathSegment    =
    refineOrThrow[String, Match["[^/]+"] & MinLength[1]]("chunks")
  private val DefaultChunkIndexWidth: PositiveInt       = refineOrThrow[Int, numeric.Greater[0]](8)
  private val DefaultManifestName: FileSegment          =
    refineOrThrow[String, Match["[^/]+"] & MinLength[1]]("manifest.json")
  private val DefaultMonolithicObjectName: FileSegment  =
    refineOrThrow[String, Match["[^/]+"] & MinLength[1]]("object.bin")
  private val DefaultPatchLogDirectoryName: PathSegment =
    refineOrThrow[String, Match["[^/]+"] & MinLength[1]]("manifest.patches")

  /**
   * Witness that a strategy can translate between [[BinaryKey]] values and
   * [[BlobLocator]] instances without losing information.
   */
  final case class Iso(
    forward: BinaryKey => BlobLocator,
    reverse: BlobLocator => Either[String, BinaryKey],
  )

  /**
   * Locator strategy that shards blob paths using fixed-length hash
   * prefixes. By default it emits paths of the form:
   *
   * {{code}}
   * <prefix>/<algo>/<ab>/<cd>/<algo>-<hash>-<size>/
   * {{/code}}
   */
  final case class ShardedByHash(
    scheme: LocatorScheme,
    bucket: String,
    prefix: Option[String] = None,
    fanOutSegments: NonNegativeInt = DefaultFanOutSegments,
    segmentLength: PositiveInt = DefaultSegmentLength,
    includeTerminalSlash: Boolean = true,
    chunkDirectoryName: PathSegment = DefaultChunkDirectoryName,
    chunkIndexWidth: PositiveInt = DefaultChunkIndexWidth,
    manifestName: FileSegment = DefaultManifestName,
    monolithicObjectName: FileSegment = DefaultMonolithicObjectName,
    patchLogDirectoryName: PathSegment = DefaultPatchLogDirectoryName,
  ) extends LocatorStrategy:

    private val base64MimeEncoder = Base64.getUrlEncoder.withoutPadding()
    private val base64MimeDecoder = Base64.getUrlDecoder

    private val configuredPrefixSegments =
      prefix
        .filter(_.nonEmpty)
        .map(trimSlashes)
        .toList
        .flatMap(_.split('/').iterator.filter(_.nonEmpty).toList)

    override val iso: Iso =
      Iso(
        forward = key => BlobLocator(scheme, bucket, locatorPath(key)),
        reverse = locator => keyFrom(locator),
      )

    def locatorPath(key: BinaryKey): String =
      val base = basePath(key)
      if includeTerminalSlash then ensureTrailingSlash(base) else base

    def rootDirectoryPath(key: BinaryKey): String =
      ensureTrailingSlash(basePath(key))

    def manifestPath(key: BinaryKey): String =
      joinFile(rootDirectoryPath(key), manifestName)

    def monolithicObjectPath(key: BinaryKey): String =
      joinFile(rootDirectoryPath(key), monolithicObjectName)

    def patchLogDirectoryPath(key: BinaryKey): String =
      joinDirectory(rootDirectoryPath(key), patchLogDirectoryName)

    def chunkDirectoryPath(key: BinaryKey): String =
      joinDirectory(rootDirectoryPath(key), chunkDirectoryName)

    def chunkPath(key: BinaryKey, index: ChunkIndex): String =
      val chunkFile = formatChunkIndex(index)
      joinFile(chunkDirectoryPath(key), chunkFile)

    private def trimSlashes(value: String): String =
      value.split('/').iterator.filter(_.nonEmpty).mkString("/")

    private def basePath(key: BinaryKey): String =
      val sanitizedPrefix = prefix.filter(_.nonEmpty).map(trimSlashes)
      val shardSegments   = shardPieces(key.hash, fanOutSegments, segmentLength)
      val terminal        = terminalSegment(key)
      val components      =
        sanitizedPrefix.toList ++ List(key.algo) ++ shardSegments ++ List(terminal)
      components.filter(_.nonEmpty).mkString("/")

    private def ensureTrailingSlash(path: String): String =
      if path.endsWith("/") then path else s"$path/"

    private def joinDirectory(root: String, child: String): String =
      val trimmed = trimSlashes(child)
      ensureTrailingSlash(s"${ensureTrailingSlash(root)}$trimmed")

    private def joinFile(root: String, child: String): String =
      val trimmed = trimSlashes(child)
      s"${ensureTrailingSlash(root)}$trimmed"

    private def shardPieces(hash: String, segments: Int, length: Int): List[String] =
      List.tabulate(segments)(identity).flatMap { idx =>
        val start = idx * length
        if start >= hash.length then None
        else
          val end   = math.min(start + length, hash.length)
          val slice = hash.substring(start, end)
          Option.when(slice.nonEmpty)(slice)
      }

    private def formatChunkIndex(index: ChunkIndex): String =
      val pattern = s"%0${chunkIndexWidth}d"
      pattern.format(index: Long)

    private def terminalSegment(key: BinaryKey): String =
      val base = s"${key.algo}-${key.hash}-${key.size}"
      key.mime match
        case Some(mimeValue) =>
          val encoded = encodeMime(mimeValue)
          s"$base~$encoded"
        case None            => base

    private def encodeMime(mime: Mime): String =
      base64MimeEncoder.encodeToString(mime.getBytes(StandardCharsets.UTF_8)).replace("=", "")

    private def decodeMime(value: String): Either[String, Mime] =
      val padded =
        value.length % 4 match
          case 2 => s"$value=="
          case 3 => s"$value="
          case 0 => value
          case _ => s"$value==="

      Try(String(base64MimeDecoder.decode(padded), StandardCharsets.UTF_8)).toEither.left
        .map(_.getMessage)
        .flatMap(_.refineEither[Match["[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+(;.*)?"]])

    private def componentsOf(path: String): List[String] =
      trimSlashes(path).split('/').iterator.filter(_.nonEmpty).toList

    private def dropConfiguredPrefix(components: List[String]): Either[String, List[String]] =
      configuredPrefixSegments.foldLeft[Either[String, List[String]]](Right(components)) { case (acc, segment) =>
        acc.flatMap {
          case head :: tail if head == segment => Right(tail)
          case _                               => Left(s"Locator path missing expected prefix segment '$segment'")
        }
      }

    def keyFromLocatorPath(path: String): Either[String, BinaryKey] =
      keyFromSegments(componentsOf(path))

    def keyFromRootDirectoryPath(path: String): Either[String, BinaryKey] =
      keyFromLocatorPath(path)

    def keyFromManifestPath(path: String): Either[String, BinaryKey] =
      for
        segments <- ensureTerminalSegment(path, manifestName)
        key      <- keyFromSegments(segments)
      yield key

    def keyFromMonolithicObjectPath(path: String): Either[String, BinaryKey] =
      for
        segments <- ensureTerminalSegment(path, monolithicObjectName)
        key      <- keyFromSegments(segments)
      yield key

    def keyFromPatchLogDirectoryPath(path: String): Either[String, BinaryKey] =
      for
        segments <- ensureTerminalSegment(path, patchLogDirectoryName)
        key      <- keyFromSegments(segments)
      yield key

    def keyFromChunkDirectoryPath(path: String): Either[String, BinaryKey] =
      for
        segments <- ensureTerminalSegment(path, chunkDirectoryName)
        key      <- keyFromSegments(segments)
      yield key

    def keyAndChunkIndexFromPath(path: String): Either[String, (BinaryKey, ChunkIndex)] =
      val segments = componentsOf(path)
      for
        chunkFile      <- segments.lastOption.toRight("Chunk path missing chunk file")
        beforeFile      = segments.dropRight(1)
        chunkDirectory <- beforeFile.lastOption.toRight("Chunk path missing chunk directory")
        _              <- Either.cond(
                            chunkDirectory == chunkDirectoryName,
                            (),
                            s"Chunk path expected directory '$chunkDirectoryName' but found '$chunkDirectory'",
                          )
        locatorSegments = beforeFile.dropRight(1)
        index          <-
          chunkFile
            .refineEither[Match["[0-9]+"]]
            .flatMap(value => value.toLongOption.toRight(s"Chunk file '$chunkFile' was not a valid Long"))
            .flatMap(_.refineEither[numeric.Greater[-1]])
        key            <- keyFromSegments(locatorSegments)
      yield key -> index

    private def keyFrom(locator: BlobLocator): Either[String, BinaryKey] =
      for
        _   <- Either.cond(locator.scheme == scheme, (), s"Unexpected locator scheme '${locator.scheme}'")
        _   <- Either.cond(locator.bucket == bucket, (), s"Unexpected locator bucket '${locator.bucket}'")
        key <- keyFromSegments(componentsOf(locator.path))
      yield key

    private def keyFromSegments(segments: List[String]): Either[String, BinaryKey] =
      for
        remaining <- dropConfiguredPrefix(segments)
        algoPart  <- remaining.headOption.toRight("Locator path did not contain an algorithm segment")
        tail       = remaining.drop(1)
        terminal  <- tail.lastOption.toRight("Locator path missing terminal segment")
        shards     = tail.dropRight(1)
        identity  <- parseTerminal(terminal)
        _         <- Either.cond(
                       identity.algo == algoPart,
                       (),
                       s"Terminal segment algorithm '${identity.algo}' mismatched '$algoPart'",
                     )
        _         <- Either.cond(
                       shards == shardPieces(identity.hash, fanOutSegments, segmentLength),
                       (),
                       "Locator shards did not match hash fan-out",
                     )
        key       <- BinaryKey.make(identity.algo, identity.hash, identity.size, identity.mime)
      yield key

    private def ensureTerminalSegment(path: String, expected: String): Either[String, List[String]] =
      val segments = componentsOf(path)
      for
        last <- segments.lastOption.toRight(s"Path '$path' did not contain any segments")
        _    <- Either.cond(
                  last == expected,
                  (),
                  s"Path '$path' did not end with expected segment '$expected'",
                )
      yield segments.dropRight(1)

    private final case class ParsedTerminal(algo: Algo, hash: HexLower, size: Size, mime: Option[Mime])

    private def parseTerminal(segment: String): Either[String, ParsedTerminal] =
      val split                       = segment.split("~", 2).toList
      val (identityPart, mimeEncoded) =
        split match
          case head :: encoded :: Nil => head -> Some(encoded)
          case head :: encoded :: _   => head -> Some(encoded)
          case head :: Nil            => head -> None
          case Nil                    => ""   -> None

      val lastDash = identityPart.lastIndexOf('-')
      if lastDash <= 0 || lastDash == identityPart.length - 1 then
        Left(s"Terminal segment '$segment' did not contain a valid size component")
      else
        val sizePart   = identityPart.substring(lastDash + 1)
        val beforeSize = identityPart.substring(0, lastDash)
        val hashDash   = beforeSize.lastIndexOf('-')
        if hashDash <= 0 || hashDash == beforeSize.length - 1 then
          Left(s"Terminal segment '$segment' did not contain a valid hash component")
        else
          val algoPart = beforeSize.substring(0, hashDash)
          val hashPart = beforeSize.substring(hashDash + 1)
          for
            algo <- algoPart.refineEither[Match["(sha-256|blake3|md5)"]]
            hash <- hashPart.refineEither[Match["[0-9a-f]+"] & MinLength[2]]
            size <-
              sizePart.toLongOption
                .toRight(s"Terminal segment size '$sizePart' was not a valid Long")
                .flatMap(_.refineEither[numeric.Greater[-1]])
            mime <- mimeEncoded
                      .map(decodeMime)
                      .map(_.map(Some(_)))
                      .getOrElse(Right(None))
          yield ParsedTerminal(algo, hash, size, mime)

  val default: LocatorStrategy =
    ShardedByHash(scheme = "file", bucket = "", prefix = None)
