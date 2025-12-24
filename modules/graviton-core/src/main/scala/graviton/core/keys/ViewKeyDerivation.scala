package graviton.core.keys

import graviton.core.bytes.{Digest, HashAlgo}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Byte-level canonical view key derivation.
 *
 * v1 rule: view key MUST depend only on deterministic inputs:
 * - base key bits (algo + digest bytes + size)
 * - transform canonical bytes (name/scope/args)
 *
 * It MUST NOT depend on:
 * - wall clock time
 * - random seeds
 * - machine ids / hostnames
 * - tenant/system ids (Quasar concerns)
 */
object ViewKeyDerivation:

  private val Prefix = "graviton:view:v1".getBytes(StandardCharsets.UTF_8)

  def derive(base: BinaryKey, transform: ViewTransform): Either[String, KeyBits] =
    val baseBits = base.bits

    val algoBytes = baseBits.algo.primaryName.getBytes(StandardCharsets.UTF_8)
    val sizeBytes = ByteBuffer.allocate(8).putLong(baseBits.size).array()
    val digest    = baseBits.digest.bytes

    val payload =
      Prefix ++
        ByteBuffer.allocate(4).putInt(algoBytes.length).array() ++ algoBytes ++
        ByteBuffer.allocate(4).putInt(digest.length).array() ++ digest ++
        sizeBytes ++
        transform.canonicalBytes

    for
      // v1: always SHA-256 for view keys (stable and ubiquitous).
      viewDigest <- Digest.make(HashAlgo.Sha256)(payload)
      // We do not know the materialized view size at this point; keep it explicit.
      bits       <- KeyBits.create(HashAlgo.Sha256, viewDigest, 0L)
    yield bits
