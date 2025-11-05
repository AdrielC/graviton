package graviton.core.manifest

object FramedManifest:
  final case class Frame(bytes: Array[Byte])

  def encode(manifest: Manifest): Frame =
    val data = manifest.entries.map(_.key.bits.digest.value).mkString("|").getBytes("UTF-8")
    Frame(data)

  def decode(frame: Frame): Manifest =
    Manifest(Nil, frame.bytes.length.toLong)
