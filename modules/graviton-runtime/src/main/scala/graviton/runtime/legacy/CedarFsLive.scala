package graviton.runtime.legacy

import zio.*
import zio.stream.*

import java.nio.file.Files

final class CedarFsLive(
  repos: CedarRepos,
  settings: CedarFsPathResolver.Settings = CedarFsPathResolver.Settings(),
  index: CedarFsPathResolver.LiveIndex,
) extends CedarFs:

  override def open(repo: String, binaryHash: String): ZStream[Any, CedarLegacyError.FsError, Byte] =
    ZStream.unwrapScoped {
      for
        repoCfg  <- ZIO
                      .fromOption(repos.byName.get(repo))
                      .mapError(_ => CedarLegacyError.FsError.BinaryNotFound(repo, binaryHash, tried = Nil))
        resolved <- CedarFsPathResolver.resolveBinaryPath(repoCfg, binaryHash, settings, index)
        is       <- ZIO
                      .fromAutoCloseable(ZIO.attemptBlocking(Files.newInputStream(resolved.path)))
                      .mapError(th => CedarLegacyError.FsError.BinaryUnreadable(repoCfg.name, binaryHash, resolved.path, th))
      yield ZStream
        .fromInputStream(is, chunkSize = 64 * 1024)
        .mapError(th => CedarLegacyError.FsError.BinaryUnreadable(repoCfg.name, binaryHash, resolved.path, th))
    }

object CedarFsLive:
  def make(
    repos: CedarRepos,
    settings: CedarFsPathResolver.Settings = CedarFsPathResolver.Settings(),
  ): UIO[CedarFsLive] =
    CedarFsPathResolver.LiveIndex.make(settings).map(idx => new CedarFsLive(repos, settings, idx))
