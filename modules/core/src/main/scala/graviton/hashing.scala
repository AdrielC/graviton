package graviton

import zio.*
import zio.stream.*
import java.security.MessageDigest

object Hashing:

  def computeSHA256(stream: Bytes): UIO[Chunk[Byte]] =
    for
      md <- ZIO.succeed(MessageDigest.getInstance("SHA-256"))
      _  <- stream.runForeach(b => ZIO.succeed(md.update(b))).orDie
    yield Chunk.fromArray(md.digest())
