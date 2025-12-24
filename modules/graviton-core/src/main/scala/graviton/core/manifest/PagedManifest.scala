package graviton.core.manifest

import graviton.core.bytes.{Digest, HashAlgo, Hasher}
import graviton.core.keys.BinaryKey
import graviton.core.ranges.Span

/**
 * A paginated manifest representation: the logical manifest is split into multiple page-manifests,
 * each within `FramedManifest.MaxManifestEntries`. A small root index maps byte spans to page keys.
 *
 * This is the scalable alternative to “one giant manifest object”, especially for huge blobs with
 * small chunk sizes.
 */
final case class ManifestPageRef(
  key: BinaryKey.Manifest,
  span: Span[Long],
  entryCount: Int,
)

final case class ManifestRoot(
  pages: List[ManifestPageRef],
  size: Long,
)

object ManifestRoot:
  def validate(root: ManifestRoot): Either[String, ManifestRoot] =
    validate(root.pages, expectedSize = Some(root.size))

  def fromPages(pages: List[ManifestPageRef]): Either[String, ManifestRoot] =
    validate(pages, expectedSize = None)

  private def validate(pages: List[ManifestPageRef], expectedSize: Option[Long]): Either[String, ManifestRoot] =
    val validated =
      pages.zipWithIndex.foldLeft[Either[String, (List[ManifestPageRef], Option[Long])]](Right((Nil, None))) { case (acc, (page, idx)) =>
        acc.flatMap { case (accumulated, previousEnd) =>
          val start = page.span.startInclusive
          val end   = page.span.endInclusive

          if page.entryCount <= 0 then Left(s"Page $idx must have positive entryCount, got ${page.entryCount}")
          else if start < 0 then Left(s"Page $idx starts before zero: $start")
          else if end < start then Left(s"Page $idx has negative length: start=$start end=$end")
          else if previousEnd.exists(prior => start <= prior) then
            val prior = previousEnd.get
            Left(s"Pages must be strictly increasing and non-overlapping; page $idx starts at $start after $prior")
          else Right((accumulated :+ page, Some(end)))
        }
      }

    validated.flatMap { case (ordered, lastEnd) =>
      val computedSize =
        lastEnd match
          case None      => Right(0L)
          case Some(end) =>
            try Right(math.addExact(end, 1L))
            catch case _: ArithmeticException => Left("Manifest root size overflow")

      computedSize.flatMap { total =>
        expectedSize match
          case Some(size) if size != total =>
            Left(s"Manifest root size $size does not match computed span coverage $total")
          case _                           => Right(ManifestRoot(ordered, total))
      }
    }

/**
 * Convenience “build output” for pagination: the root (index) plus the list of page manifests.
 *
 * Key derivation/persistence is intentionally left to the caller, since it depends on
 * backend/layout policy.
 */
final case class PagedManifest(
  root: ManifestRoot,
  pages: List[Manifest],
)

object PagedManifest:

  /**
   * Split a logical manifest entries list into multiple page-manifests, each capped at
   * `FramedManifest.MaxManifestEntries`, and return a root index referencing those pages.
   *
   * Note: the returned root uses placeholder keys; use `materializeKeys` to compute stable
   * content-addressed manifest-page keys once you choose an algorithm.
   */
  def paginate(entries: List[ManifestEntry]): Either[String, PagedManifest] =
    for
      validated <- Manifest.fromEntries(entries)
      pages     <- paginateValidated(validated)
    yield pages

  /**
   * Compute content-addressed keys for each manifest page by encoding it and hashing the bytes.
   * Returns:
   * - the root index referencing the computed page keys
   * - the keyed pages (same page order as the root)
   */
  def materializeKeys(
    paged: PagedManifest,
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ): Either[String, (ManifestRoot, List[(BinaryKey.Manifest, Manifest)])] =
    val keyedEither =
      paged.pages.zipWithIndex.foldLeft[Either[String, List[(BinaryKey.Manifest, Manifest)]]](Right(Nil)) { case (acc, (page, idx)) =>
        acc.flatMap { out =>
          pageKey(page, algo).map(key => out :+ (key -> page)).left.map(err => s"Failed to derive key for page $idx: $err")
        }
      }

    keyedEither.flatMap { keyed =>
      val refsEither =
        keyed
          .map { case (key, page) =>
            page.entries match
              case Nil          =>
                Left("Manifest page cannot be empty")
              case head :: tail =>
                val last = tail.lastOption.getOrElse(head)
                Right(
                  ManifestPageRef(
                    key = key,
                    span = Span.unsafe(head.span.startInclusive, last.span.endInclusive),
                    entryCount = page.entries.length,
                  )
                )
          }
          .foldLeft[Either[String, List[ManifestPageRef]]](Right(Nil)) { (acc, next) =>
            acc.flatMap(xs => next.map(xs :+ _))
          }

      refsEither.flatMap(refs => ManifestRoot.fromPages(refs).map(root => root.copy(size = paged.root.size)).map(root => (root, keyed)))
    }

  private def paginateValidated(manifest: Manifest): Either[String, PagedManifest] =
    val max = FramedManifest.MaxManifestEntries

    val grouped = manifest.entries.grouped(max).toList

    val pagesEither =
      grouped.zipWithIndex.foldLeft[Either[String, List[Manifest]]](Right(Nil)) { case (acc, (group, idx)) =>
        acc.flatMap { pages =>
          Manifest.fromEntries(group).left.map(err => s"Invalid page $idx: $err").map(page => pages :+ page)
        }
      }

    pagesEither.flatMap { pages =>
      val placeholderDigest =
        Digest.fromBytes(Array.fill(HashAlgo.Sha256.hashBytes)(0.toByte)).left.map(err => s"Failed to construct placeholder digest: $err")

      placeholderDigest.map { d =>
        val placeholderBits = graviton.core.keys.KeyBits(HashAlgo.Sha256, d, 0L)

        val refs =
          pages.map { page =>
            // For an empty overall manifest, there are no pages (so this never sees Nil).
            val head = page.entries.head
            val last = page.entries.last

            ManifestPageRef(
              key = BinaryKey.Manifest(placeholderBits),
              span = Span.unsafe(head.span.startInclusive, last.span.endInclusive),
              entryCount = page.entries.length,
            )
          }

        PagedManifest(root = ManifestRoot(pages = refs, size = manifest.size), pages = pages)
      }
    }

  private def pageKey(page: Manifest, algo: HashAlgo): Either[String, BinaryKey.Manifest] =
    for
      frame  <- FramedManifest.encode(page)
      hasher <- Hasher.hasher(algo, None)
      _       = hasher.update(frame.bytes)
      bits   <- hasher.digestKeyBits
      key    <- BinaryKey.manifest(bits)
    yield key
