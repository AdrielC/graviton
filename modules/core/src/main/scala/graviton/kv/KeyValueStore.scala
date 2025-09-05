package graviton.kv

import zio.*
import zio.stream.ZStream

final case class Timestamp(value: Long) extends AnyVal

trait KeyValueStore:

  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte], timestamp: Timestamp): IO[Throwable, Boolean]

  def getLatest(namespace: String, key: Chunk[Byte], before: Option[Timestamp]): IO[Throwable, Option[Chunk[Byte]]]

  def getLatestTimestamp(namespace: String, key: Chunk[Byte]): IO[Throwable, Option[Timestamp]]

  def getAllTimestamps(namespace: String, key: Chunk[Byte]): ZStream[Any, Throwable, Timestamp]

  def scanAll(namespace: String): ZStream[Any, Throwable, (Chunk[Byte], Chunk[Byte])]

  def scanAllKeys(namespace: String): ZStream[Any, Throwable, Chunk[Byte]]

  def delete(namespace: String, key: Chunk[Byte], marker: Option[Timestamp]): IO[Throwable, Unit]

object KeyValueStore:
  val any: ZLayer[KeyValueStore, Nothing, KeyValueStore] = ZLayer.service[KeyValueStore]

  val inMemory: ZLayer[Any, Nothing, KeyValueStore] =
    ZLayer.scoped {
      for
        namespaces <- Ref.make(Map.empty[String, Map[Chunk[Byte], List[InMemoryKeyValueEntry]]])
        _ <- ZIO.addFinalizer(
               namespaces.get.flatMap { map =>
                 ZIO.logDebug(map.values.map(_.size).sum.toString + " items left in kv store")
               }
             )
      yield InMemoryKeyValueStore(namespaces)
    }

  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte], timestamp: Timestamp): ZIO[KeyValueStore, Throwable, Boolean] =
    ZIO.serviceWithZIO(_.put(namespace, key, value, timestamp))

  def getLatest(namespace: String, key: Chunk[Byte], before: Option[Timestamp]): ZIO[KeyValueStore, Throwable, Option[Chunk[Byte]]] =
    ZIO.serviceWithZIO(_.getLatest(namespace, key, before))

  def getAllTimestamps(namespace: String, key: Chunk[Byte]): ZStream[KeyValueStore, Throwable, Timestamp] =
    ZStream.serviceWithStream(_.getAllTimestamps(namespace, key))

  def scanAll(namespace: String): ZStream[KeyValueStore, Throwable, (Chunk[Byte], Chunk[Byte])] =
    ZStream.serviceWithStream(_.scanAll(namespace))

  def scanAllKeys(namespace: String): ZStream[KeyValueStore, Throwable, Chunk[Byte]] =
    ZStream.serviceWithStream(_.scanAllKeys(namespace))

  def delete(namespace: String, key: Chunk[Byte], marker: Option[Timestamp]): ZIO[KeyValueStore, Throwable, Unit] =
    ZIO.serviceWithZIO(_.delete(namespace, key, marker))

  def getLatestTimestamp(namespace: String, key: Chunk[Byte]): ZIO[KeyValueStore, Throwable, Option[Timestamp]] =
    ZIO.serviceWithZIO(_.getLatestTimestamp(namespace, key))

  private final case class InMemoryKeyValueEntry(data: Chunk[Byte], timestamp: Timestamp):
    override def toString: String = s"<entry (${data.size} bytes) at $timestamp>"

  private final case class InMemoryKeyValueStore(
    namespaces: Ref[Map[String, Map[Chunk[Byte], List[InMemoryKeyValueEntry]]]]
  ) extends KeyValueStore:
    def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte], timestamp: Timestamp): IO[Throwable, Boolean] =
      namespaces.update { ns => add(ns, namespace, key, value, timestamp) }.as(true)

    def getLatest(namespace: String, key: Chunk[Byte], before: Option[Timestamp]): IO[Throwable, Option[Chunk[Byte]]] =
      namespaces.get.map { ns =>
        ns.get(namespace)
          .flatMap(_.get(key))
          .flatMap(
            _.filter(_.timestamp.value <= before.map(_.value).getOrElse(Long.MaxValue)) match
              case Nil      => None
              case filtered => Some(filtered.maxBy(_.timestamp.value).data)
          )
      }

    def getLatestTimestamp(namespace: String, key: Chunk[Byte]): IO[Throwable, Option[Timestamp]] =
      namespaces.get.map { ns =>
        ns.get(namespace)
          .flatMap(_.get(key))
          .flatMap {
            case Nil     => None
            case entries => Some(entries.maxBy(_.timestamp.value).timestamp)
          }
      }

    def getAllTimestamps(namespace: String, key: Chunk[Byte]): ZStream[Any, Throwable, Timestamp] =
      ZStream.fromIterableZIO {
        namespaces.get.map { ns =>
          ns.get(namespace)
            .flatMap(_.get(key))
            .map {
              case Nil     => List.empty[Timestamp]
              case entries => entries.map(_.timestamp)
            }
            .getOrElse(List.empty)
        }
      }

    def scanAll(namespace: String): ZStream[Any, Throwable, (Chunk[Byte], Chunk[Byte])] =
      ZStream.unwrap {
        namespaces.get.map { ns =>
          ns.get(namespace) match
            case Some(value) => ZStream.fromIterable(value).map { case (key, value) => (key, value.maxBy(_.timestamp.value).data) }
            case None        => ZStream.empty
        }
      }

    def scanAllKeys(namespace: String): ZStream[Any, Throwable, Chunk[Byte]] =
      ZStream.unwrap {
        namespaces.get.map { ns =>
          ns.get(namespace) match
            case Some(value) => ZStream.fromIterable(value.keys)
            case None        => ZStream.empty
        }
      }

    def delete(namespace: String, key: Chunk[Byte], marker: Option[Timestamp]): IO[Throwable, Unit] =
      marker match
        case Some(markerTimestamp) =>
          namespaces.update { ns =>
            ns.get(namespace) match
              case Some(data) =>
                val values          = data.getOrElse(key, List.empty)
                val after           = values.takeWhile(_.timestamp.value > markerTimestamp.value)
                val remainingValues = values.take(after.length + 1)
                if remainingValues.isEmpty then ns.updated(namespace, data - key)
                else ns.updated(namespace, data.updated(key, remainingValues))
              case None => ns
          }
        case None =>
          namespaces.update { ns =>
            ns.get(namespace) match
              case Some(data) => ns.updated(namespace, data - key)
              case None       => ns
          }

    private def add(
      ns: Map[String, Map[Chunk[Byte], List[InMemoryKeyValueEntry]]],
      namespace: String,
      key: Chunk[Byte],
      value: Chunk[Byte],
      timestamp: Timestamp
    ): Map[String, Map[Chunk[Byte], List[InMemoryKeyValueEntry]]] =
      ns.get(namespace) match
        case Some(data) =>
          data.get(key) match
            case Some(entries) =>
              ns.updated(namespace, data.updated(key, InMemoryKeyValueEntry(value, timestamp) :: entries))
            case None =>
              ns.updated(namespace, data.updated(key, List(InMemoryKeyValueEntry(value, timestamp))))
        case None =>
          ns + (namespace -> Map(key -> List(InMemoryKeyValueEntry(value, timestamp))))

    override def toString: String = "InMemoryKeyValueStore"

