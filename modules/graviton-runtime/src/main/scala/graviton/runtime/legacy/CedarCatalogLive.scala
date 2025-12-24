package graviton.runtime.legacy

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

final class CedarCatalogLive(
  repos: CedarRepos,
  metadataDirName: String = "metadata",
  enableIndexFallback: Boolean = true,
  indexMaxDepth: Int = 6,
  metaIndex: Ref.Synchronized[Option[Map[String, Path]]],
) extends CedarCatalog:

  private val xmlHashPatterns: List[java.util.regex.Pattern] =
    List(
      java.util.regex.Pattern.compile("(?is)<binaryHash>\\s*([0-9a-fA-F]{32,128})\\s*</binaryHash>"),
      java.util.regex.Pattern.compile("(?is)<binary_hash>\\s*([0-9a-fA-F]{32,128})\\s*</binary_hash>"),
      java.util.regex.Pattern.compile("(?is)<contentHash>\\s*([0-9a-fA-F]{32,128})\\s*</contentHash>"),
      java.util.regex.Pattern.compile("(?is)<hash>\\s*([0-9a-fA-F]{32,128})\\s*</hash>"),
      java.util.regex.Pattern.compile("(?is)\\bbinaryHash\\s*=\\s*\"([0-9a-fA-F]{32,128})\""),
    )

  private val xmlMimePatterns: List[java.util.regex.Pattern] =
    List(
      java.util.regex.Pattern.compile("(?is)<mime>\\s*([^<\\s]+)\\s*</mime>"),
      java.util.regex.Pattern.compile("(?is)<mimeType>\\s*([^<\\s]+)\\s*</mimeType>"),
      java.util.regex.Pattern.compile("(?is)<contentType>\\s*([^<\\s]+)\\s*</contentType>"),
      java.util.regex.Pattern.compile("(?is)\\b(mimeType|contentType)\\s*=\\s*\"([^\"\\s]+)\""),
    )

  private val xmlLengthPatterns: List[java.util.regex.Pattern] =
    List(
      java.util.regex.Pattern.compile("(?is)<length>\\s*(\\d{1,20})\\s*</length>"),
      java.util.regex.Pattern.compile("(?is)<byteLength>\\s*(\\d{1,20})\\s*</byteLength>"),
      java.util.regex.Pattern.compile("(?is)<size>\\s*(\\d{1,20})\\s*</size>"),
      java.util.regex.Pattern.compile("(?is)\\b(byteLength|length|size)\\s*=\\s*\"(\\d{1,20})\""),
    )

  private def findFirstGroup(patterns: List[java.util.regex.Pattern], text: String): Option[String] =
    patterns.foldLeft(Option.empty[String]) { (acc, p) =>
      acc.orElse {
        val m = p.matcher(text)
        if m.find() then
          val group =
            if p.pattern().contains("mimeType|contentType") || p.pattern().contains("(byteLength|length|size)") then 2 else 1
          Option(m.group(group))
        else None
      }
    }

  private def candidateMetadataPaths(root: Path, docId: String): List[Path] =
    val dir = root.resolve(metadataDirName)
    List(
      dir.resolve(s"$docId.xml"),
      dir.resolve(s"$docId.XML"),
      dir.resolve(docId),
      dir.resolve(docId.take(2)).resolve(s"$docId.xml"),
      dir.resolve(docId.take(2)).resolve(s"$docId.XML"),
    )

  private def firstExistingPath(paths: List[Path]): IO[Throwable, Option[Path]] =
    paths match
      case Nil          => ZIO.succeed(None)
      case head :: tail =>
        ZIO.attempt(Files.exists(head)).flatMap(exists => if exists then ZIO.succeed(Some(head)) else firstExistingPath(tail))

  private def buildIndex(metaRoot: Path, maxDepth: Int): IO[Throwable, Map[String, Path]] =
    ZIO.attemptBlocking {
      val walk = Files.walk(metaRoot, maxDepth)
      try {
        walk
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .flatMap { p =>
            val fileName = p.getFileName.toString
            val base     =
              fileName.indexOf('.') match
                case -1 => fileName
                case n  => fileName.take(n)
            if base.nonEmpty then Some(base -> p) else None
          }
          .toMap
      } finally walk.close()
    }

  private def metadataPathFor(id: LegacyId, repo: CedarRepo): IO[CedarLegacyError.CatalogError, (Path, List[Path])] =
    val tried = candidateMetadataPaths(repo.root, id.docId)
    for
      fast <- firstExistingPath(tried).mapError(th => CedarLegacyError.CatalogError.MetadataUnreadable(id, repo.root, th))
      path <- fast match
                case Some(p) => ZIO.succeed(p)
                case None    =>
                  if !enableIndexFallback then ZIO.fail(CedarLegacyError.CatalogError.MetadataNotFound(id, tried))
                  else
                    val metaRoot = repo.root.resolve(metadataDirName)
                    metaIndex
                      .modifyZIO {
                        case Some(idx) => ZIO.succeed(idx -> Some(idx))
                        case None      => buildIndex(metaRoot, indexMaxDepth).map(built => built -> Some(built))
                      }
                      .mapError(th => CedarLegacyError.CatalogError.MetadataUnreadable(id, metaRoot, th))
                      .flatMap { idx =>
                        idx.get(id.docId) match
                          case Some(p) => ZIO.succeed(p)
                          case None    => ZIO.fail(CedarLegacyError.CatalogError.MetadataNotFound(id, tried))
                      }
    yield (path, tried)

  override def resolve(id: LegacyId): IO[CedarLegacyError.CatalogError, LegacyDescriptor] =
    for
      repoCfg   <- ZIO
                     .fromOption(repos.byName.get(id.repo))
                     .mapError(_ => CedarLegacyError.CatalogError.RepoNotConfigured(id.repo))
      (path, _) <- metadataPathFor(id, repoCfg)
      xml       <- ZIO
                     .attemptBlocking(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                     .mapError(th => CedarLegacyError.CatalogError.MetadataUnreadable(id, path, th))
      hash0     <- ZIO
                     .fromOption(findFirstGroup(xmlHashPatterns, xml))
                     .mapError(_ => CedarLegacyError.CatalogError.MetadataInvalid(id, path, "missing binary hash"))
      hash       = hash0.trim.toLowerCase
      mime       = findFirstGroup(xmlMimePatterns, xml).map(_.trim).filter(_.nonEmpty).getOrElse("application/octet-stream")
      length     = findFirstGroup(xmlLengthPatterns, xml).flatMap(s => scala.util.Try(s.trim.toLong).toOption)
    yield LegacyDescriptor(id, hash, mime, length)

object CedarCatalogLive:
  def make(
    repos: CedarRepos,
    metadataDirName: String = "metadata",
    enableIndexFallback: Boolean = true,
    indexMaxDepth: Int = 6,
  ): UIO[CedarCatalogLive] =
    Ref.Synchronized
      .make(Option.empty[Map[String, Path]])
      .map { ref =>
        new CedarCatalogLive(
          repos = repos,
          metadataDirName = metadataDirName,
          enableIndexFallback = enableIndexFallback,
          indexMaxDepth = indexMaxDepth,
          metaIndex = ref,
        )
      }
