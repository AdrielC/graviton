package graviton.core
package codec

import graviton.core.bytes.{Digest, HashAlgo, hex}
import graviton.core.keys.{BinaryKey, KeyBits, ViewTransform}
import scodec.*
import scodec.codecs.*

import scala.collection.immutable.ListMap

object BinaryKeyCodec:

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
            .map(_.toArray)
            .flatMap(Digest.fromBytes)
        val keyBits      = digestEither.flatMap(d => KeyBits.create(algo, d, size.toLong))
        Attempt.fromEither(keyBits.left.map(Err(_)))
      },
      keyBits => Attempt.successful(((keyBits.algo, keyBits.digest.hex), keyBits.size)),
    )

  private val attributesCodec: Codec[ListMap[String, String]] =
    listOfN(
      uint16,
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
      .xmap(
        { case ((name, args), scope) => ViewTransform(name, args, scope) },
        view => ((view.name, view.args), view.scope),
      )

  val codec: Codec[BinaryKey] =
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
