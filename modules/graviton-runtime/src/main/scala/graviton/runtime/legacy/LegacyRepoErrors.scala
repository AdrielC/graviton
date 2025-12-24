package graviton.runtime.legacy

import java.nio.file.Path

sealed trait LegacyRepoError extends Exception

object LegacyRepoError:

  sealed trait CatalogError extends LegacyRepoError
  object CatalogError:
    final case class RepoNotConfigured(repo: String) extends Exception(s"Legacy repo not configured: $repo") with CatalogError

    final case class MetadataNotFound(id: LegacyId, tried: List[Path])
        extends Exception(s"Legacy metadata not found for ${id.repo}/${id.docId}")
        with CatalogError

    final case class MetadataUnreadable(id: LegacyId, path: Path, cause: Throwable)
        extends Exception(s"Legacy metadata unreadable for ${id.repo}/${id.docId} at $path", cause)
        with CatalogError

    final case class MetadataInvalid(id: LegacyId, path: Path, reason: String)
        extends Exception(s"Legacy metadata invalid for ${id.repo}/${id.docId} at $path: $reason")
        with CatalogError

  sealed trait FsError extends LegacyRepoError
  object FsError:
    final case class InvalidBinaryHash(repo: String, hash: String, reason: String)
        extends Exception(s"Invalid legacy binary hash for repo=$repo: $hash ($reason)")
        with FsError

    final case class BinaryNotFound(repo: String, hash: String, tried: List[Path])
        extends Exception(s"Legacy binary not found for repo=$repo hash=$hash")
        with FsError

    final case class BinaryUnreadable(repo: String, hash: String, path: Path, cause: Throwable)
        extends Exception(s"Legacy binary unreadable for repo=$repo hash=$hash at $path", cause)
        with FsError
