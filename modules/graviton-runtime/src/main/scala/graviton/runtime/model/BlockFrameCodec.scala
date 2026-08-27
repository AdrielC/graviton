package graviton.runtime.model

import graviton.core.codec.BinaryKeyCodec
import graviton.core.keys.BinaryKey
import graviton.core.types.MaxBlockBytes
import scodec.*
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs.*
import zio.Chunk

private[model] object BlockFrameWireBounds:
  val MaxPayloadBytes   = MaxBlockBytes
  val MaxAadStringBytes = 4096
  val MaxAadEntries     = 128
  val MaxAadBytes       = 1024 * 1024
  val MaxAuxBytes       = 1024
  val StreamChunkBytes  = 64 * 1024

object BlockFrameCodec:
  import BlockFrameWireBounds.*

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
    optional(bool, variableSizeBytes(boundedUint16("stringLength", MaxAadStringBytes), utf8))

  private val optionalBytes: Codec[Option[Chunk[Byte]]] =
    optional(bool, variableSizeBytes(boundedUint16("auxiliaryBytesLength", MaxAuxBytes), bytes)).xmap(
      _.map(bv => Chunk.fromArray(bv.toArray)),
      _.map(chunk => ByteVector.view(chunk.toArray)),
    )

  private val payloadBytes: Codec[Chunk[Byte]] =
    variableSizeBytesLong(boundedLong("payloadLength", MaxPayloadBytes.toLong), bytes).xmap(
      bv => Chunk.fromArray(bv.toArray),
      chunk => ByteVector.view(chunk.toArray),
    )

  private val frameHeaderCodec: Codec[FrameHeader] = new Codec[FrameHeader]:
    override def sizeBound: SizeBound = SizeBound.unknown

    override def encode(value: FrameHeader): Attempt[BitVector] =
      for
        versionBits <- uint8.encode(value.version & 0xff)
        typeBits    <- frameTypeCodec.encode(value.frameType)
        algoBits    <- frameAlgorithmCodec.encode(value.algorithm)
        payloadBits <- boundedLong("payloadLength", MaxPayloadBytes.toLong).encode(value.payloadLength)
        aadBits     <- boundedInt("aadLength", MaxAadBytes).encode(value.aadLength)
        keyBits     <- optionalString.encode(value.keyId)
        nonceBits   <- optionalBytes.encode(value.nonce)
      yield versionBits ++ typeBits ++ algoBits ++ payloadBits ++ aadBits ++ keyBits ++ nonceBits

    override def decode(bits: BitVector): Attempt[DecodeResult[FrameHeader]] =
      for
        versionRes <- uint8.decode(bits)
        _          <- ensureSupportedFrameVersion(versionRes.value)
        typeRes    <- frameTypeCodec.decode(versionRes.remainder)
        algoRes    <- frameAlgorithmCodec.decode(typeRes.remainder)
        payloadRes <- boundedLong("payloadLength", MaxPayloadBytes.toLong).decode(algoRes.remainder)
        aadRes     <- boundedInt("aadLength", MaxAadBytes).decode(payloadRes.remainder)
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
        keyBits   <- variableSizeBytes(boundedUint16("aadKeyLength", MaxAadStringBytes), utf8).encode(value.key)
        valueBits <- variableSizeBytes(boundedUint16("aadValueLength", MaxAadStringBytes), utf8).encode(value.value)
      yield keyBits ++ valueBits

    override def decode(bits: BitVector): Attempt[DecodeResult[FrameAadEntry]] =
      for
        keyRes   <- variableSizeBytes(boundedUint16("aadKeyLength", MaxAadStringBytes), utf8).decode(bits)
        valueRes <- variableSizeBytes(boundedUint16("aadValueLength", MaxAadStringBytes), utf8).decode(keyRes.remainder)
      yield DecodeResult(FrameAadEntry(keyRes.value, valueRes.value), valueRes.remainder)

  private val optionalBinaryKey: Codec[Option[BinaryKey]] =
    optional(bool, BinaryKeyCodec.codec)

  private val optionalLong: Codec[Option[Long]] =
    optional(bool, int64).exmap(validateOptionalBlockIndex, validateOptionalBlockIndex)

  private val extrasCodec: Codec[List[FrameAadEntry]] =
    listOfN(boundedUint16("aadEntryCount", MaxAadEntries), frameAadEntryCodec)

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
        _           <- Attempt.fromEither(validateForEncoding(value).left.map(Err(_)))
        _           <- ensureFrameConsistency(value)
        headerBits  <- frameHeaderCodec.encode(value.header)
        aadBits     <- frameAadCodec.encode(value.aad)
        payloadBits <- payloadBytes.encode(value.ciphertext)
        tagBits     <- tagCodec.encode(value.tag)
      yield headerBits ++ aadBits ++ payloadBits ++ tagBits

    override def decode(bits: BitVector): Attempt[DecodeResult[BlockFrame]] =
      for
        headerRes  <- frameHeaderCodec.decode(bits)
        aadRes     <- decodeAad(headerRes.value.aadLength, headerRes.remainder)
        payloadRes <- payloadBytes.decode(aadRes.remainder)
        tagRes     <- tagCodec.decode(payloadRes.remainder)
        frame      <- buildFrame(headerRes.value, aadRes.value, payloadRes.value, tagRes.value)
      yield DecodeResult(frame, tagRes.remainder)

  def renderAadBytes(aad: FrameAad): Either[String, Chunk[Byte]] =
    encodeAad(aad).toEither.left.map(_.message)

  private def encodeAad(aad: FrameAad): Attempt[Chunk[Byte]] =
    for
      measured <- Attempt.fromEither(measureAad(aad).left.map(Err(_)))
      bits     <- frameAadCodec.encode(aad)
      _        <- ensure(
                    bits.size == measured.bits,
                    s"AAD size preflight diverged from codec (expected ${measured.bits} bits, encoded ${bits.size})",
                  )
    yield Chunk.fromArray(bits.toByteArray)

  /**
   * The v1 wire packs AAD and payload at bit granularity while storing the
   * rounded-up AAD byte length. Decode inside that declared byte window, then
   * put its at-most-seven payload prefix bits back before the outer remainder.
   * Once the full window is present, an inner insufficient-bits result is a
   * malformed AAD, not a request to buffer beyond the advertised bound.
   */
  private def decodeAad(aadLength: Int, bits: BitVector): Attempt[DecodeResult[FrameAad]] =
    val windowBits = aadLength.toLong * 8L
    if bits.size < windowBits then Attempt.failure(Err.insufficientBits(windowBits, bits.size))
    else
      val window = bits.take(windowBits)
      frameAadCodec.decode(window) match
        case Attempt.Failure(error)     =>
          Attempt.failure(
            Err(s"AAD cannot be decoded within its declared $aadLength-byte window: ${error.messageWithContext}")
          )
        case Attempt.Successful(result) =>
          if result.remainder.size > 7L then
            Attempt.failure(
              Err(
                s"AAD consumed too little of its declared $aadLength-byte window " +
                  s"(${result.remainder.size} bits remain)"
              )
            )
          else
            Attempt.successful(
              DecodeResult(
                result.value,
                result.remainder ++ bits.drop(windowBits),
              )
            )

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

  private final case class AadWireSize(bits: Long, bytes: Int)

  private val MaxAadBits = MaxAadBytes.toLong * 8L

  /** Canonical allocation-free validation used by every public encode path. */
  private[model] def validateForEncoding(frame: BlockFrame): Either[String, Unit] =
    if frame == null then Left("frame cannot be null")
    else if frame.header == null then Left("frame header cannot be null")
    else if frame.aad == null then Left("frame AAD cannot be null")
    else
      for
        _       <- ensureEither(
                     (frame.header.version & 0xff) == (BlockFramer.FrameVersion & 0xff),
                     unsupportedFrameVersionMessage(frame.header.version & 0xff),
                   )
        _       <- ensureEither(frame.header.frameType != null, "frame type cannot be null")
        _       <- ensureEither(frame.header.algorithm != null, "frame algorithm cannot be null")
        _       <- validateChunk(frame.ciphertext, "ciphertext", MaxPayloadBytes)
        _       <- validateChunk(frame.aadBytes, "AAD bytes", MaxAadBytes)
        _       <- validateLength(frame.header.payloadLength, "payload length", MaxPayloadBytes.toLong)
        _       <- validateLength(frame.header.aadLength.toLong, "AAD length", MaxAadBytes.toLong)
        _       <- ensureEither(
                     frame.header.payloadLength == frame.ciphertext.length.toLong,
                     s"payload length mismatch (header=${frame.header.payloadLength}, actual=${frame.ciphertext.length})",
                   )
        _       <- validateOptionalText(frame.header.keyId, "key id")
        _       <- validateOptionalChunk(frame.header.nonce, "nonce", MaxAuxBytes)
        _       <- validateOptionalChunk(frame.tag, "tag", MaxAuxBytes)
        aadSize <- measureAad(frame.aad)
        _       <- ensureEither(
                     frame.header.aadLength == aadSize.bytes,
                     s"AAD length mismatch (header=${frame.header.aadLength}, encoded=${aadSize.bytes})",
                   )
        _       <- ensureEither(
                     frame.aadBytes.length == aadSize.bytes,
                     s"AAD byte length mismatch (provided=${frame.aadBytes.length}, encoded=${aadSize.bytes})",
                   )
      yield ()

  private[model] def validateAadForEncoding(aad: FrameAad): Either[String, Unit] =
    measureAad(aad).map(_ => ())

  private def measureAad(aad: FrameAad): Either[String, AadWireSize] =
    if aad == null then Left("frame AAD cannot be null")
    else if aad.extra == null then Left("AAD extras cannot be null")
    else if aad.extra.length > MaxAadEntries then Left(s"AAD entry count exceeds $MaxAadEntries")
    else if aad.blobKey == null then Left("AAD blob key option cannot be null")
    else if aad.blockIndex == null then Left("AAD block index option cannot be null")
    else
      for
        _              <- ensureEither(
                            aad.blockIndex.forall(_ >= 0L),
                            s"AAD block index cannot be negative: ${aad.blockIndex.getOrElse(0L)}",
                          )
        withOrg        <- addOptionalString(0L, aad.orgId, "AAD organization id")
        withBlobFlag   <- addAadBits(withOrg, 1L, "AAD blob key flag")
        withBlob       <- aad.blobKey match
                            case None      => Right(withBlobFlag)
                            case Some(key) =>
                              if key == null then Left("AAD blob key cannot be null")
                              else
                                BinaryKeyCodec
                                  .encodedSizeBits(key, MaxAadBits - withBlobFlag)
                                  .left
                                  .map(message => s"invalid AAD blob key: $message")
                                  .flatMap(size => addAadBits(withBlobFlag, size, "AAD blob key"))
        withBlockIndex <- addAadBits(withBlob, if aad.blockIndex.isDefined then 65L else 1L, "AAD block index")
        withPolicy     <- addOptionalString(withBlockIndex, aad.policyTag, "AAD policy tag")
        withEntryCount <- addAadBits(withPolicy, 16L, "AAD entry count")
        total          <- measureAadEntries(aad.extra, withEntryCount)
        byteLength      = ((total + 7L) / 8L).toInt
        _              <- ensureEither(byteLength <= MaxAadBytes, s"encoded AAD exceeds $MaxAadBytes bytes")
      yield AadWireSize(total, byteLength)

  private def measureAadEntries(entries: Chunk[FrameAadEntry], initialBits: Long): Either[String, Long] =
    var total = Right(initialBits): Either[String, Long]
    var index = 0
    while index < entries.length && total.isRight do
      val entry = entries(index)
      if entry == null then return Left(s"AAD entry $index cannot be null")

      total = for
        current    <- total
        keyBytes   <- utf8LengthAtMost(entry.key, MaxAadStringBytes, s"AAD entry $index key")
        withKey    <- addAadBits(current, 16L + keyBytes.toLong * 8L, s"AAD entry $index key")
        valueBytes <- utf8LengthAtMost(entry.value, MaxAadStringBytes, s"AAD entry $index value")
        withValue  <- addAadBits(withKey, 16L + valueBytes.toLong * 8L, s"AAD entry $index value")
      yield withValue

      index += 1

    total

  private def addOptionalString(
    current: Long,
    value: Option[String],
    field: String,
  ): Either[String, Long] =
    if value == null then Left(s"$field option cannot be null")
    else
      value match
        case None       => addAadBits(current, 1L, s"$field flag")
        case Some(text) =>
          for
            length <- utf8LengthAtMost(text, MaxAadStringBytes, field)
            total  <- addAadBits(current, 1L + 16L + length.toLong * 8L, field)
          yield total

  private def addAadBits(current: Long, additional: Long, field: String): Either[String, Long] =
    if additional < 0L || current < 0L || current > MaxAadBits || additional > MaxAadBits - current then
      Left(s"$field makes encoded AAD exceed $MaxAadBytes bytes")
    else Right(current + additional)

  private def validateOptionalText(value: Option[String], field: String): Either[String, Unit] =
    if value == null then Left(s"$field option cannot be null")
    else value.fold[Either[String, Unit]](Right(()))(text => utf8LengthAtMost(text, MaxAadStringBytes, field).map(_ => ()))

  private def utf8LengthAtMost(value: String, maxBytes: Int, field: String): Either[String, Int] =
    if value == null then Left(s"$field cannot be null")
    else
      var index = 0
      var bytes = 0
      while index < value.length && bytes <= maxBytes do
        val char = value.charAt(index)
        if Character.isHighSurrogate(char) then
          if index + 1 >= value.length || !Character.isLowSurrogate(value.charAt(index + 1)) then
            return Left(s"$field contains an unpaired high surrogate")
          bytes += 4
          index += 2
        else if Character.isLowSurrogate(char) then return Left(s"$field contains an unpaired low surrogate")
        else
          bytes += (if char <= 0x7f then 1 else if char <= 0x7ff then 2 else 3)
          index += 1

      ensureEither(bytes <= maxBytes, s"$field exceeds $maxBytes UTF-8 bytes").map(_ => bytes)

  private def validateOptionalChunk(
    value: Option[Chunk[Byte]],
    field: String,
    maxBytes: Int,
  ): Either[String, Unit] =
    if value == null then Left(s"$field option cannot be null")
    else value.fold[Either[String, Unit]](Right(()))(validateChunk(_, field, maxBytes))

  private def validateChunk(value: Chunk[Byte], field: String, maxBytes: Int): Either[String, Unit] =
    if value == null then Left(s"$field cannot be null")
    else ensureEither(value.length <= maxBytes, s"$field exceeds $maxBytes bytes")

  private def validateLength(value: Long, field: String, max: Long): Either[String, Unit] =
    ensureEither(value >= 0L && value <= max, s"$field must be between 0 and $max: $value")

  private def ensureEither(condition: Boolean, message: => String): Either[String, Unit] =
    if condition then Right(()) else Left(message)

  private def validateOptionalBlockIndex(value: Option[Long]): Attempt[Option[Long]] =
    value match
      case Some(index) if index < 0L => Attempt.failure(Err(s"AAD block index cannot be negative: $index"))
      case _                         => Attempt.successful(value)

  private def ensureSupportedFrameVersion(version: Int): Attempt[Unit] =
    ensure(
      version == (BlockFramer.FrameVersion & 0xff),
      unsupportedFrameVersionMessage(version),
    )

  private def unsupportedFrameVersionMessage(version: Int): String =
    s"Unsupported block frame version $version (expected ${BlockFramer.FrameVersion & 0xff})"

  private def ensure(condition: => Boolean, message: => String): Attempt[Unit] =
    if condition then Attempt.successful(()) else Attempt.failure(Err(message))

  private def boundedInt(context: String, max: Int): Codec[Int] =
    int32.exmap(
      value =>
        if value >= 0 && value <= max then Attempt.successful(value)
        else Attempt.failure(Err(s"$context must be between 0 and $max: $value")),
      value => Attempt.successful(value),
    )

  private def boundedLong(context: String, max: Long): Codec[Long] =
    int64.exmap(
      value =>
        if value >= 0L && value <= max then Attempt.successful(value)
        else Attempt.failure(Err(s"$context must be between 0 and $max: $value")),
      value => Attempt.successful(value),
    )

  private def boundedUint16(context: String, max: Int): Codec[Int] =
    uint16.exmap(
      value =>
        if value <= max then Attempt.successful(value)
        else Attempt.failure(Err(s"$context must be at most $max: $value")),
      value => Attempt.successful(value),
    )
