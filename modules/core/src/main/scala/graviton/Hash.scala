package graviton

import zio.schema.{DeriveSchema, Schema}
import scodec.bits.ByteVector
import graviton.domain.HashBytes
import zio.prelude.{ForEach, given}
import zio.prelude.{NonEmptySortedMap, NonEmptySortedSet}
import graviton.core.{toNonEmptyChunk, toNonEmptySortedMap}

import zio.schema.derived

import zio.schema.*

import graviton.domain.HexString
import zio.prelude.Covariant
import zio.prelude.IdentityBoth

final case class Hash[F[+_]](bytes: F[HashBytes]):
  def hex(using ForEach[F]): F[HexString] = bytes.map(bytes => 
    HexString.applyUnsafe(ByteVector(bytes.toArray).toHex))

object Hash:
  extension (hash: Hash.MultiHash)
    def algos: NonEmptySortedSet[HashAlgorithm] = hash.bytes.keySet
    def map[B](f: (HashAlgorithm, HashBytes) => B): NonEmptySortedMap[HashAlgorithm, B] = (hash.bytes.toNonEmptyChunk.map {
      case (algo, bytes) => algo -> f(algo, bytes)
    }).toNonEmptySortedMap

  extension (hash: Hash.SingleHash)
    def algo: HashAlgorithm = hash.bytes.algo


  final case class HashResult[+A](algo: HashAlgorithm, bytes: A) derives Schema
  object HashResult:
    given ForEach[HashResult] = new:
      override def forEach[G[+_]: {IdentityBoth, Covariant}, A, B](fa: HashResult[A])(f: A => G[B]): G[HashResult[B]] = 
        f(fa.bytes).map(HashResult(fa.algo, _))
    

  type SingleHash = Hash[HashResult]

  object SingleHash:
    def apply(algo: HashAlgorithm, bytes: HashBytes): SingleHash =
      Hash[HashResult](HashResult(algo, bytes))

  transparent inline given Schema[SingleHash] = 
    DeriveSchema.gen[SingleHash]

  type MultiHashMap[+A] = NonEmptySortedMap[HashAlgorithm, ? <: A]
  type MultiHash = Hash[MultiHashMap]

  given [A] => (S: Schema[A]) => Schema[MultiHashMap[A]] =
    Schema.nonEmptyChunk[(HashAlgorithm, A)].transform(
      list => NonEmptySortedMap.fromNonEmptyChunk(list),
      nem => nem.toNonEmptyChunk,
    )

  given [K, A] => (S: Schema[K], A: Schema[A]) => Schema[NonEmptySortedMap[K, A]] =
    Schema.nonEmptyChunk[(K, A)].transform(
      list => NonEmptySortedMap.fromNonEmptyChunk(list)(using S.ordering),
      nem => nem.toNonEmptyChunk,
    )

  object MultiHash:
    def apply(bytes: NonEmptySortedMap[HashAlgorithm, HashBytes]): MultiHash =
      Hash[MultiHashMap](bytes)

  transparent inline given Schema[MultiHash] = 
    DeriveSchema.gen[MultiHash]
  

