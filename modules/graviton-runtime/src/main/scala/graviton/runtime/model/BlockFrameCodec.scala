package graviton.runtime.model

import graviton.core.codec.BinaryKeyCodec
import graviton.core.keys.BinaryKey
import scodec.*
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs.*
import zio.Chunk

object BlockFrameCodec:

  private val frameTypeCodec: Codec[FrameType] =
    mappedEnum(uint8, FrameType.Block -> 0, FrameType.Manifest -> 1, FrameType.Attribute -> 2, FrameType.Index -> 3)

  private val frameAlgorithmCodec: Codec[FrameAlgorithm] =
    mappedEnum(
      uint8,
      FrameAlgorithm.Plain                   -> 0,
      FrameAlgorithm.Compressed              -> 1,
      FrameAlgorithm.Encrypted               -> 2,
      FrameAlgorithm.CompressedThenEncrypted -> 3,
    )

  private val optionalString: Codec[Option[String]] =
    optional(bool, variableSizeBytes(uint16, utf8))

  private val optionalBytes: Codec[Option[Chunk[Byte]]] =
    optional(bool, variableSizeBytes(uint16, bytes)).xmap(
      _.map(bv => Chunk.fromArray(bv.toArray)),
      _.map(chunk => ByteVector(chunk.toArray)),
    )

  private val payloadBytes: Codec[Chunk[Byte]] =
    variableSizeBytesLong(nonNegativeLong("payloadLength"), bytes).xmap(
      bv => Chunk.fromArray(bv.toArray),
      chunk => ByteVector(chunk.toArray),
    )

  private val frameHeaderCodec: Codec[FrameHeader] = new Codec[FrameHeader]:
    override def sizeBound: SizeBound = SizeBound.unknown

    override def encode(value: FrameHeader): Attempt[BitVector] =
      for
        versionBits <- uint8.encode(value.version & 0xff)
        typeBits    <- frameTypeCodec.encode(value.frameType)
        algoBits    <- frameAlgorithmCodec.encode(value.algorithm)
        payloadBits <- nonNegativeLong("payloadLength").encode(value.payloadLength)
        aadBits     <- nonNegativeInt("aadLength").encode(value.aadLength)
        keyBits     <- optionalString.encode(value.keyId)
        nonceBits   <- optionalBytes.encode(value.nonce)
      yield versionBits ++ typeBits ++ algoBits ++ payloadBits ++ aadBits ++ keyBits ++ nonceBits

    override def decode(bits: BitVector): Attempt[DecodeResult[FrameHeader]] =
      for
        versionRes <- uint8.decode(bits)
        typeRes    <- frameTypeCodec.decode(versionRes.remainder)
        algoRes    <- frameAlgorithmCodec.decode(typeRes.remainder)
        payloadRes <- nonNegativeLong("payloadLength").decode(algoRes.remainder)
        aadRes     <- nonNegativeInt("aadLength").decode(payloadRes.remainder)
        keyRes     <- optionalString.decode(aadRes.remainder)
        nonceRes   <- optionalBytes.decode(keyRes.remainder)
        header      = FrameHeader(
                        version = versionRes.value.toByte,
                        frameType = typeRes.value,
                        algorithm = algoRes.value,
                        payloadLength = payloadRes.value,
                        aadLength = aadRes.value,
                        keyId = keyRes.value,
                        nonce = nonceRes.value,
                      )
      yield DecodeResult(header, nonceRes.remainder)

  private val frameAadEntryCodec: Codec[FrameAadEntry] = new Codec[FrameAadEntry]:
    override def sizeBound: SizeBound = SizeBound.unknown

    override def encode(value: FrameAadEntry): Attempt[BitVector] =
      for
        keyBits   <- variableSizeBytes(uint16, utf8).encode(value.key)
        valueBits <- variableSizeBytes(uint16, utf8).encode(value.value)
      yield keyBits ++ valueBits

    override def decode(bits: BitVector): Attempt[DecodeResult[FrameAadEntry]] =
      for
        keyRes   <- variableSizeBytes(uint16, utf8).decode(bits)
        valueRes <- variableSizeBytes(uint16, utf8).decode(keyRes.remainder)
      yield DecodeResult(FrameAadEntry(keyRes.value, valueRes.value), valueRes.remainder)

  private val optionalBinaryKey: Codec[Option[BinaryKey]] =
    optional(bool, BinaryKeyCodec.codec)

  private val optionalLong: Codec[Option[Long]] =
    optional(bool, int64)

  private val extrasCodec: Codec[List[FrameAadEntry]] =
    listOfN(uint16, frameAadEntryCodec)

  private val frameAadCodec: Codec[FrameAad] = new Codec[FrameAad]:
    override def sizeBound: SizeBound = SizeBound.unknown

    override def encode(value: FrameAad): Attempt[BitVector] =
      for
        orgBits    <- optionalString.encode(value.orgId)
        blobBits   <- optionalBinaryKey.encode(value.blobKey)
        blockBits  <- optionalLong.encode(value.blockIndex)
        policyBits <- optionalString.encode(value.policyTag)
        extraBits  <- extrasCodec.encode(value.extra.toList)
      yield orgBits ++ blobBits ++ blockBits ++ policyBits ++ extraBits

    override def decode(bits: BitVector): Attempt[DecodeResult[FrameAad]] =
      for
        orgRes    <- optionalString.decode(bits)
        blobRes   <- optionalBinaryKey.decode(orgRes.remainder)
        blockRes  <- optionalLong.decode(blobRes.remainder)
        policyRes <- optionalString.decode(blockRes.remainder)
        extraRes  <- extrasCodec.decode(policyRes.remainder)
        aad        = FrameAad(orgRes.value, blobRes.value, blockRes.value, policyRes.value, Chunk.fromIterable(extraRes.value))
      yield DecodeResult(aad, extraRes.remainder)

  private val tagCodec: Codec[Option[Chunk[Byte]]] = optionalBytes

  val codec: Codec[BlockFrame] = new Codec[BlockFrame]:
    override def sizeBound: SizeBound = SizeBound.unknown

    override def encode(value: BlockFrame): Attempt[BitVector] =
      for
        _           <- ensureFrameConsistency(value)
        headerBits  <- frameHeaderCodec.encode(value.header)
        aadBits     <- frameAadCodec.encode(value.aad)
        payloadBits <- payloadBytes.encode(value.ciphertext)
        tagBits     <- tagCodec.encode(value.tag)
      yield headerBits ++ aadBits ++ payloadBits ++ tagBits

    override def decode(bits: BitVector): Attempt[DecodeResult[BlockFrame]] =
      for
        headerRes  <- frameHeaderCodec.decode(bits)
        aadRes     <- frameAadCodec.decode(headerRes.remainder)
        payloadRes <- payloadBytes.decode(aadRes.remainder)
        tagRes     <- tagCodec.decode(payloadRes.remainder)
        frame      <- buildFrame(headerRes.value, aadRes.value, payloadRes.value, tagRes.value)
      yield DecodeResult(frame, tagRes.remainder)

  def renderAadBytes(aad: FrameAad): Either[String, Chunk[Byte]] =
    encodeAad(aad).toEither.left.map(_.message)

  private def encodeAad(aad: FrameAad): Attempt[Chunk[Byte]] =
    frameAadCodec.encode(aad).map(bits => Chunk.fromArray(bits.toByteArray))

  private def buildFrame(
    header: FrameHeader,
    aad: FrameAad,
    ciphertext: Chunk[Byte],
    tag: Option[Chunk[Byte]],
  ): Attempt[BlockFrame] =
    for
      encodedAad <- encodeAad(aad)
      _          <- ensure(
                      header.aadLength == encodedAad.length,
                      s"AAD length mismatch (expected ${header.aadLength}, computed ${encodedAad.length})",
                    )
      _          <- ensure(
                      header.payloadLength == ciphertext.length.toLong,
                      s"Payload length mismatch (expected ${header.payloadLength}, computed ${ciphertext.length})",
                    )
    yield BlockFrame(header, aad, encodedAad, ciphertext, tag)

  private def ensureFrameConsistency(frame: BlockFrame): Attempt[Unit] =
    for
      encodedAad <- encodeAad(frame.aad)
      _          <- ensure(frame.aadBytes == encodedAad, "Frame AAD bytes diverge from encoded AAD structure")
      _          <- ensure(
                      frame.header.aadLength == encodedAad.length,
                      s"AAD length mismatch (header=${frame.header.aadLength}, actual=${encodedAad.length})",
                    )
      _          <- ensure(
                      frame.header.payloadLength == frame.ciphertext.length.toLong,
                      s"Payload length mismatch (header=${frame.header.payloadLength}, actual=${frame.ciphertext.length})",
                    )
    yield ()

  private def ensure(condition: => Boolean, message: => String): Attempt[Unit] =
    if condition then Attempt.successful(()) else Attempt.failure(Err(message))

  private def nonNegativeInt(context: String): Codec[Int] =
    int32.exmap(
      value =>
        if value >= 0 then Attempt.successful(value)
        else Attempt.failure(Err(s"$context cannot be negative: $value")),
      value => Attempt.successful(value),
    )

  private def nonNegativeLong(context: String): Codec[Long] =
    int64.exmap(
      value =>
        if value >= 0 then Attempt.successful(value)
        else Attempt.failure(Err(s"$context cannot be negative: $value")),
      value => Attempt.successful(value),
    )
