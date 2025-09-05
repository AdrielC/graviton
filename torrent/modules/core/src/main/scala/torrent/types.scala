package torrent

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

object types:

  type HexString = DescribedAs[Match["^[0-9a-f]+$"], "must be a lowercase hex string"]

  type DigestLength[N <: Int] = Length[N]

  opaque type Digest[N <: Int] = String :| HexString & DigestLength[N]

  // Example: 64-character SHA-256 (in hex)
  type Sha256Digest = Digest[64]

  type Sha512Digest = Digest[128]
  // Example: 128-character BLAKE3 (in hex)
  type Blake3Digest = Digest[128]
