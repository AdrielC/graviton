package graviton.runtime.legacy

import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.nio.file.{Files, Path}

object LegacyFsPathResolverSpec extends ZIOSpecDefault:

  private def tempDir(prefix: String): Task[Path] =
    ZIO.attemptBlocking(Files.createTempDirectory(prefix))

  override def spec: Spec[TestEnvironment, Any] =
    suite("LegacyFsPathResolver")(
      test("normalizeHash rejects non-hex and traversal-ish inputs") {
        for
          bad1 <- LegacyFsPathResolver.normalizeHash("repo", "../etc/passwd").either
          bad2 <- LegacyFsPathResolver.normalizeHash("repo", "aa/../bb").either
          bad3 <- LegacyFsPathResolver.normalizeHash("repo", "not-a-hash").either
        yield assert(bad1)(isLeft(anything)) &&
          assert(bad2)(isLeft(anything)) &&
          assert(bad3)(isLeft(anything))
      },
      test("resolveBinaryPath rejects symlink escapes") {
        val h = "a" * 40
        for
          root  <- tempDir("legacy-root-")
          repo   = LegacyRepo("r", root)
          bin    = root.resolve("binaries")
          _     <- ZIO.attemptBlocking(Files.createDirectories(bin))
          // Create a symlink inside binaries/ pointing outside the repo root.
          target = Path.of("/etc/hosts")
          link   = bin.resolve(h)
          _     <- ZIO.attemptBlocking {
                     if Files.exists(link) then Files.delete(link)
                     Files.createSymbolicLink(link, target)
                   }
          idx   <- LegacyFsPathResolver.LiveIndex.make(LegacyFsPathResolver.Settings(enableIndexFallback = false))
          res   <- LegacyFsPathResolver
                     .resolveBinaryPath(repo, h, LegacyFsPathResolver.Settings(enableIndexFallback = false), idx)
                     .either
        yield assert(res)(isLeft(isSubtype[LegacyRepoError.FsError.BinaryUnreadable](anything)))
      },
    )
