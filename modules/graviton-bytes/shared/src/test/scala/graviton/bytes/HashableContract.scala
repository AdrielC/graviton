package graviton.bytes

import scodec.bits.ByteVector
import zio.Chunk
import zio.test.*

object HashableContract:

  private final case class DerivedPair(left: String, right: String) derives Hashable

  private sealed trait DerivedEvent derives Hashable
  private object DerivedEvent:
    final case class Text(value: String) extends DerivedEvent
    final case class Octet(value: Byte)  extends DerivedEvent

  val spec =
    suite("Hashable cross-runtime contract")(
      test("uses canonical UTF-8 bytes") {
        val actual   = Hashable[String].input("Graviton 🌌").materialize
        val expected = ByteVector.fromValidHex("4772617669746f6e20f09f8c8c")

        assertTrue(ByteVector(actual.toArray) == expected)
      },
      test("preserves immutable chunk and byte-vector bytes") {
        val bytes = Chunk[Byte](0, 1, -1, 127, -128)

        assertTrue(
          Hashable[Chunk[Byte]].input(bytes).materialize == bytes,
          Hashable[ByteVector].input(ByteVector(bytes.toArray)).materialize == bytes,
        )
      },
      test("contramap preserves explicit field framing") {
        final case class Framed(header: Chunk[Byte], body: Chunk[Byte])

        val framed  = Hashable.instance[Framed](value => HashInput.segments(value.header, value.body))
        val encoded = framed.input(Framed(Chunk[Byte](1, 2), Chunk[Byte](3, 4)))

        assertTrue(
          encoded.materialize == Chunk[Byte](1, 2, 3, 4),
          encoded.segmentCount == 2,
          encoded.byteLength == 4L,
        )
      },
      test("derives canonical product hashing from Scala 3 mirrors") {
        val instance = Hashable[DerivedPair]
        val first    = instance.input(DerivedPair("left", "right")).materialize
        val second   = instance.input(DerivedPair("left", "right")).materialize

        assertTrue(first == second, first.nonEmpty)
      },
      test("derived fields are length-delimited") {
        val instance = Hashable[DerivedPair]
        val splitA   = instance.input(DerivedPair("ab", "c")).materialize
        val splitB   = instance.input(DerivedPair("a", "bc")).materialize

        assertTrue(splitA != splitB)
      },
      test("derived sums include their alternative discriminator") {
        val instance = Hashable[DerivedEvent]
        val text     = instance.input(DerivedEvent.Text("1")).materialize
        val octet    = instance.input(DerivedEvent.Octet('1'.toByte)).materialize

        assertTrue(text != octet)
      },
    )
