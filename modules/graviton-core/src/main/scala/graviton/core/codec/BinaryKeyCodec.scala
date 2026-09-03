package graviton.core
package codec

import graviton.core.bytes.{Digest, HashAlgo, hex}
import graviton.core.keys.{BinaryKey, KeyBits, ViewTransform}
import graviton.core.types.{ViewArgKey, ViewArgValue, ViewName, ViewScope}
import scodec.*
import scodec.codecs.*

import scala.collection.immutable.ListMap

object BinaryKeyCodec:

  private[graviton] val MaxViewArgumentEntries = 128

  private val MaxViewNameCodeUnits     = 128
  private val MaxViewArgKeyCodeUnits   = 128
  private val MaxViewArgValueCodeUnits = 1024
  private val MaxViewScopeCodeUnits    = 128
  private val MaxUint16                = 65535

  private val hashAlgoCodec: Codec[HashAlgo] =
    mappedEnum(
      uint8,
      HashAlgo.Sha256 -> 0,
      HashAlgo.Sha1   -> 1,
      HashAlgo.Blake3 -> 2,
    )

  private val digestCodec: Codec[(HashAlgo, String)] =
    hashAlgoCodec.flatZip(_ => variableSizeBytes(uint16, utf8))

  private val keyBitsCodec: Codec[KeyBits] =
    (digestCodec :: uint64).exmap(
      { case ((algo, value), size) =>
        val digestEither =
          scodec.bits.ByteVector
            .fromHex(value)
            .toRight(s"Invalid hex digest '$value'")
            .map(bytes => zio.Chunk.fromIterable(bytes.toIterable))
            .flatMap(Digest.fromChunk)
        val keyBits      = digestEither.flatMap(d => KeyBits.fromLong(algo, d, size.toLong))
        Attempt.fromEither(keyBits.left.map(Err(_)))
      },
      keyBits => Attempt.successful(((keyBits.algo, keyBits.digest.hex.value), BigInt(keyBits.size))),
    )

  private val attributesCodec: Codec[ListMap[String, String]] =
    listOfN(
      boundedUint16("viewArgumentCount", MaxViewArgumentEntries),
      (variableSizeBytes(uint16, utf8) :: variableSizeBytes(uint16, utf8)),
    ).exmap(
      pairs =>
        val builder                   = ListMap.newBuilder[String, String]
        val seen                      = scala.collection.mutable.HashSet.empty[String]
        var duplicate: Option[String] = None

        pairs.foreach { case (key, value) =>
          if !seen.add(key) then duplicate = Some(key)
          builder += key -> value
        }

        duplicate match
          case Some(key) => Attempt.failure(Err(s"Duplicate key '$key' in attributes"))
          case None      => Attempt.successful(builder.result())
      ,
      map => Attempt.successful(map.toList.sortBy(_._1)),
    )

  private val byteFlag: Codec[Boolean] =
    uint8.exmap(
      {
        case 0 => Attempt.successful(false)
        case 1 => Attempt.successful(true)
        case n => Attempt.failure(Err(s"Invalid optional flag $n"))
      },
      flag => Attempt.successful(if flag then 1 else 0),
    )

  private val viewCodec: Codec[ViewTransform] =
    ((variableSizeBytes(uint16, utf8) :: attributesCodec) ::
      optional(byteFlag, variableSizeBytes(uint16, utf8)))
      .exmap(
        { case ((name, args), scope) =>
          Attempt.fromEither(
            ViewTransform
              .from(name, args, scope)
              .left
              .map(Err(_))
          )
        },
        view =>
          Attempt.successful(
            (
              (view.name.value, ListMap.from(view.args.toList.map { case (k, v) => (k.value, v.value) })),
              view.scope.map(_.value),
            )
          ),
      )

  private val underlyingCodec: Codec[BinaryKey] =
    discriminated[BinaryKey]
      .by(uint8)
      .typecase(
        0,
        keyBitsCodec.exmap(
          bits => Attempt.fromEither(BinaryKey.blob(bits).left.map(Err(_))),
          key => Attempt.successful(key.bits),
        ),
      )
      .typecase(
        1,
        keyBitsCodec.exmap(
          bits => Attempt.fromEither(BinaryKey.block(bits).left.map(Err(_))),
          key => Attempt.successful(key.bits),
        ),
      )
      .typecase(
        2,
        keyBitsCodec.exmap(
          bits => Attempt.fromEither(BinaryKey.chunk(bits).left.map(Err(_))),
          key => Attempt.successful(key.bits),
        ),
      )
      .typecase(
        3,
        keyBitsCodec.exmap(
          bits => Attempt.fromEither(BinaryKey.manifest(bits).left.map(Err(_))),
          key => Attempt.successful(key.bits),
        ),
      )
      .typecase(
        4,
        (keyBitsCodec :: viewCodec).exmap(
          { case (bits, view) =>
            Attempt.fromEither(
              BinaryKey
                .manifest(bits)
                .flatMap(manifest => BinaryKey.view(manifest, view))
                .left
                .map(Err(_))
            )
          },
          key => Attempt.successful((key.base.bits, key.transform)),
        ),
      )

  val codec: Codec[BinaryKey] =
    underlyingCodec.exmap(
      key => Attempt.successful(key),
      key => Attempt.fromEither(validateForEncoding(key).map(_ => key).left.map(Err(_))),
    )

  /**
   * Computes the exact number of bits emitted by [[codec]] without encoding a
   * key or materializing its wire buffers. View canonicality re-derives the
   * identity from transform input bounded by the validated view limits.
   * Measurement stops as soon as the caller's enclosing wire budget is
   * exceeded.
   */
  private[graviton] def encodedSizeBits(key: BinaryKey, maxBits: Long): Either[String, Long] =
    if maxBits < 0L then Left(s"binary key bit budget cannot be negative: $maxBits")
    else
      for
        _    <- validateForEncoding(key)
        bits <- measureKey(key, maxBits)
      yield bits

  private[graviton] def validateForEncoding(key: BinaryKey): Either[String, Unit] =
    if key == null then Left("binary key cannot be null")
    else
      key match
        case value: BinaryKey.Blob     =>
          validateKeyBits(value.bits, "blob key").flatMap(_ => ensure(value.bits.size > 0L, "blob key size must be positive"))
        case value: BinaryKey.Block    =>
          validateKeyBits(value.bits, "block key").flatMap(_ => ensure(value.bits.size > 0L, "block key size must be positive"))
        case value: BinaryKey.Chunk    => validateKeyBits(value.bits, "chunk key")
        case value: BinaryKey.Manifest => validateKeyBits(value.bits, "manifest key")
        case value: BinaryKey.View     =>
          for
            _         <- validateKeyBits(value.bits, "view key")
            _         <- ensure(value.base != null, "view base key cannot be null")
            manifest  <- value.base match
                           case base: BinaryKey.Manifest => Right(base)
                           case _                        => Left("view base key must be a manifest key")
            _         <- validateKeyBits(manifest.bits, "view base key")
            _         <- validateView(value.transform)
            canonical <- BinaryKey.view(manifest, value.transform).left.map(message => s"invalid view key: $message")
            _         <- ensure(value.bits == canonical.bits, "view key bits do not match the canonical derived view key")
          yield ()

  private def measureKey(key: BinaryKey, maxBits: Long): Either[String, Long] =
    val discriminatorBits = 8L
    key match
      case value: BinaryKey.View =>
        for
          withDiscriminator <- addWithin(0L, discriminatorBits, maxBits, "binary key discriminator")
          withBase          <- addWithin(
                                 withDiscriminator,
                                 keyBitsSize(value.base.bits),
                                 maxBits,
                                 "view base key",
                               )
          viewBits          <- measureView(value.transform, maxBits - withBase)
          total             <- addWithin(withBase, viewBits, maxBits, "view transform")
        yield total
      case value                 =>
        addWithin(discriminatorBits, keyBitsSize(value.bits), maxBits, "binary key")

  private def keyBitsSize(bits: KeyBits): Long =
    // hash algorithm u8 + digest UTF-8 byte length u16 + lowercase hex + size u64
    8L + 16L + (bits.digest.length.toLong * 2L * 8L) + 64L

  private def validateKeyBits(bits: KeyBits, field: String): Either[String, Unit] =
    if bits == null then Left(s"$field bits cannot be null")
    else if bits.algo == null then Left(s"$field algorithm cannot be null")
    else if bits.digest == null then Left(s"$field digest cannot be null")
    else if bits.digest.length != bits.algo.hashBytes then Left(s"$field digest length does not match ${bits.algo.primaryName}")
    else Right(())

  private def validateView(view: ViewTransform): Either[String, Unit] =
    measureView(view, Long.MaxValue).map(_ => ())

  private def measureView(view: ViewTransform, maxBits: Long): Either[String, Long] =
    if view == null then Left("view transform cannot be null")
    else if view.name.asInstanceOf[AnyRef] eq null then Left("view name cannot be null")
    else if view.args == null then Left("view transform arguments cannot be null")
    else if view.scope == null then Left("view transform scope option cannot be null")
    else
      for
        nameBytes <- validateText(
                       view.name.value,
                       "view name",
                       MaxViewNameCodeUnits,
                       ViewName.either,
                     )
        withName  <- addWithin(0L, 16L + nameBytes.toLong * 8L, maxBits, "view name")
        withArgs  <- measureViewArguments(view, withName, maxBits)
        withScope <- view.scope match
                       case None        => addWithin(withArgs, 8L, maxBits, "view scope")
                       case Some(scope) =>
                         if scope.asInstanceOf[AnyRef] eq null then Left("view scope cannot be null")
                         else
                           for
                             scopeBytes <- validateText(
                                             scope.value,
                                             "view scope",
                                             MaxViewScopeCodeUnits,
                                             ViewScope.either,
                                           )
                             total      <- addWithin(
                                             withArgs,
                                             8L + 16L + scopeBytes.toLong * 8L,
                                             maxBits,
                                             "view scope",
                                           )
                           yield total
      yield withScope

  private def measureViewArguments(
    view: ViewTransform,
    initialBits: Long,
    maxBits: Long,
  ): Either[String, Long] =
    var total    = addWithin(initialBits, 16L, maxBits, "view argument count")
    val iterator = view.args.iterator
    var count    = 0

    while iterator.hasNext && total.isRight do
      count += 1
      if count > MaxViewArgumentEntries then return Left(s"view argument count exceeds $MaxViewArgumentEntries")

      val (key, value) = iterator.next()
      if key.asInstanceOf[AnyRef] eq null then return Left(s"view argument $count key cannot be null")
      if value.asInstanceOf[AnyRef] eq null then return Left(s"view argument $count value cannot be null")

      val measured =
        for
          keyBytes   <- validateText(
                          key.value,
                          s"view argument $count key",
                          MaxViewArgKeyCodeUnits,
                          ViewArgKey.either,
                        )
          valueBytes <- validateText(
                          value.value,
                          s"view argument $count value",
                          MaxViewArgValueCodeUnits,
                          ViewArgValue.either,
                        )
          next       <- total.flatMap(current =>
                          addWithin(
                            current,
                            16L + keyBytes.toLong * 8L + 16L + valueBytes.toLong * 8L,
                            maxBits,
                            s"view argument $count",
                          )
                        )
        yield next

      total = measured

    total

  private def validateText[A](
    value: String,
    field: String,
    maxCodeUnits: Int,
    refine: String => Either[String, A],
  ): Either[String, Int] =
    if value == null then Left(s"$field cannot be null")
    else if value.length > maxCodeUnits then Left(s"$field exceeds $maxCodeUnits UTF-16 code units")
    else
      for
        _      <- refine(value).left.map(message => s"invalid $field: $message")
        length <- utf8LengthAtMost(value, MaxUint16, field)
      yield length

  private def utf8LengthAtMost(value: String, maxBytes: Int, field: String): Either[String, Int] =
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

    ensure(bytes <= maxBytes, s"$field exceeds $maxBytes UTF-8 bytes").map(_ => bytes)

  private def addWithin(current: Long, additional: Long, max: Long, field: String): Either[String, Long] =
    if additional < 0L || current < 0L || current > max || additional > max - current then
      Left(s"$field exceeds the enclosing $max-bit budget")
    else Right(current + additional)

  private def boundedUint16(context: String, max: Int): Codec[Int] =
    uint16.exmap(
      value =>
        if value <= max then Attempt.successful(value)
        else Attempt.failure(Err(s"$context must be at most $max: $value")),
      value => Attempt.successful(value),
    )

  private def ensure(condition: Boolean, message: => String): Either[String, Unit] =
    if condition then Right(()) else Left(message)
