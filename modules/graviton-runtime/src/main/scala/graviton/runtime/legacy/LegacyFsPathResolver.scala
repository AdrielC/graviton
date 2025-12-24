package graviton.runtime.legacy

import zio.*

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

object LegacyFsPathResolver:

  final case class Settings(
    binariesDirName: String = "binaries",
    enableIndexFallback: Boolean = true,
    indexMaxDepth: Int = 8,
    candidateExtensions: List[String] = List("", ".bin", ".dat", ".pdf"),
  )

  // v1 guardrail: accept SHA-1 (40 hex) .. SHA-256 (64 hex) by default.
  // Anything else is rejected early to prevent path traversal and junk inputs.
  private val hexHash = "^[0-9a-f]{40,64}$".r

  def looksLikeHash(raw: String): Boolean =
    raw match
      case null => false
      case s    =>
        val h = s.trim.toLowerCase
        hexHash.matches(h)

  def normalizeHash(repo: String, raw: String): IO[LegacyRepoError.FsError, String] =
    ZIO.succeed(raw).map(_.trim.toLowerCase).flatMap { h =>
      if looksLikeHash(h) then ZIO.succeed(h)
      else ZIO.fail(LegacyRepoError.FsError.InvalidBinaryHash(repo, raw, "expected lowercase hex length 32..128"))
    }

  private def candidates(binariesRoot: Path, h: String, exts: List[String]): List[Path] =
    val base = List(
      binariesRoot.resolve(h),
      binariesRoot.resolve(h.take(2)).resolve(h.drop(2).take(2)).resolve(h),
      binariesRoot.resolve(h.take(2)).resolve(h),
      binariesRoot.resolve(h.take(1)).resolve(h.drop(1).take(2)).resolve(h),
      binariesRoot.resolve(h.take(2)).resolve(h.drop(2).take(2)).resolve(h.drop(4).take(2)).resolve(h),
    )

    base.flatMap { p =>
      exts.map { ext =>
        if ext.isEmpty then p else Path.of(p.toString + ext)
      }
    }

  private def firstExistingPath(paths: List[Path]): IO[Throwable, Option[Path]] =
    paths match
      case Nil          => ZIO.succeed(None)
      case head :: tail =>
        ZIO.attempt(Files.exists(head)).flatMap { exists =>
          if exists then ZIO.succeed(Some(head))
          else firstExistingPath(tail)
        }

  private def buildIndex(binariesRoot: Path, maxDepth: Int): IO[Throwable, Map[String, Path]] =
    ZIO.attemptBlocking {
      val walk = Files.walk(binariesRoot, maxDepth)
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
            val h        = base.trim.toLowerCase
            if looksLikeHash(h) then Some(h -> p) else None
          }
          .toMap
      } finally walk.close()
    }

  final case class LiveIndex(ref: Ref.Synchronized[Option[Map[String, Path]]], settings: Settings):
    def getOrBuild(binariesRoot: Path): IO[Throwable, Map[String, Path]] =
      ref.modifyZIO {
        case Some(index) => ZIO.succeed(index -> Some(index))
        case None        =>
          buildIndex(binariesRoot, settings.indexMaxDepth).map { built =>
            built -> Some(built)
          }
      }

  object LiveIndex:
    def make(settings: Settings): UIO[LiveIndex] =
      Ref.Synchronized.make(Option.empty[Map[String, Path]]).map(ref => LiveIndex(ref, settings))

  final case class Resolution(path: Path, tried: List[Path])

  def resolveBinaryPath(
    repo: LegacyRepo,
    hash: String,
    settings: Settings,
    index: LiveIndex,
  ): IO[LegacyRepoError.FsError, Resolution] =
    for
      h           <- normalizeHash(repo.name, hash)
      binariesRoot = repo.root.resolve(settings.binariesDirName)
      tried0       = candidates(binariesRoot, h, settings.candidateExtensions)
      fast        <- firstExistingPath(tried0)
                       .mapError(th => LegacyRepoError.FsError.BinaryUnreadable(repo.name, h, binariesRoot, th))
      res         <- fast match
                       case Some(p) => ZIO.succeed(Resolution(p, tried0))
                       case None    =>
                         if !settings.enableIndexFallback then ZIO.fail(LegacyRepoError.FsError.BinaryNotFound(repo.name, h, tried0))
                         else
                           index
                             .getOrBuild(binariesRoot)
                             .mapError(th => LegacyRepoError.FsError.BinaryUnreadable(repo.name, h, binariesRoot, th))
                             .flatMap { idx =>
                               idx.get(h) match
                                 case Some(p) => ZIO.succeed(Resolution(p, tried0))
                                 case None    => ZIO.fail(LegacyRepoError.FsError.BinaryNotFound(repo.name, h, tried0))
                             }
      // Final safety: resolve symlinks/real paths and ensure the result stays under binariesRoot.
      _           <- ZIO
                       .attemptBlocking {
                         val rootReal = binariesRoot.toRealPath()
                         val fileReal = res.path.toRealPath()
                         if !fileReal.startsWith(rootReal) then
                           throw new SecurityException(s"resolved path escapes binaries root: $fileReal (root: $rootReal)")
                       }
                       .mapError(th => LegacyRepoError.FsError.BinaryUnreadable(repo.name, h, binariesRoot, th))
    yield res
