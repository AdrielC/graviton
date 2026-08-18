package graviton.runtime.legacy

import zio.*
import zio.stream.*

import java.nio.file.Files

final class LegacyFsLive(
  repos: LegacyRepos,
  settings: LegacyFsPathResolver.Settings = LegacyFsPathResolver.Settings(),
  index: LegacyFsPathResolver.LiveIndex,
) extends LegacyFs:

  override def open(repo: String, binaryHash: String): ZStream[Any, LegacyRepoError.FsError, Byte] =
    ZStream.unwrapScoped {
      for
        repoCfg  <- ZIO
                      .fromOption(repos.byName.get(repo))
                      .mapError(_ => LegacyRepoError.FsError.BinaryNotFound(repo, binaryHash, tried = Nil))
        resolved <- LegacyFsPathResolver.resolveBinaryPath(repoCfg, binaryHash, settings, index)
        is       <- ZIO
                      .fromAutoCloseable(ZIO.attemptBlocking(Files.newInputStream(resolved.path)))
                      .mapError(th => LegacyRepoError.FsError.BinaryUnreadable(repoCfg.name, binaryHash, resolved.path, th))
      yield ZStream
        .fromInputStream(is, chunkSize = 64 * 1024)
        .mapError(th => LegacyRepoError.FsError.BinaryUnreadable(repoCfg.name, binaryHash, resolved.path, th))
    }

object LegacyFsLive:
  def make(
    repos: LegacyRepos,
    settings: LegacyFsPathResolver.Settings = LegacyFsPathResolver.Settings(),
  ): UIO[LegacyFsLive] =
    LegacyFsPathResolver.LiveIndex.make(settings).map(idx => new LegacyFsLive(repos, settings, idx))
