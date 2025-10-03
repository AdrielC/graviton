package graviton.stacked

import graviton.*
import graviton.GravitonError.BackendUnavailable
import graviton.ranges.ByteRange
import zio.*
import zio.stream.*

final case class StackedBinaryStore(
  primary: BinaryStore,
  replicas: Chunk[BinaryStore],
) extends BinaryStore:
  private val allStores: Chunk[BinaryStore] = primary +: replicas

  def put: ZSink[Any, Throwable, Byte, Nothing, BinaryId] =
    ZSink.collectAll[Byte].mapZIO { data =>
      ZIO
        .foldLeft(allStores)(List.empty[BinaryId]) { (acc, store) =>
          Bytes(ZStream.fromChunk(data))
            .run(store.put)
            .either
            .flatMap {
              case Right(id)                   => ZIO.succeed(id :: acc)
              case Left(_: BackendUnavailable) => ZIO.succeed(acc)
              case Left(e)                     => ZIO.fail(e)
            }
        }
        .flatMap {
          case head :: _ => ZIO.succeed(head)
          case Nil       =>
            ZIO.fail(BackendUnavailable("all replicas failed"))
        }
    }

  def get(
    id: BinaryId,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]] =
    def loop(
      stores: Chunk[BinaryStore],
      lastErr: Option[Throwable],
    ): IO[Throwable, Option[Bytes]] =
      stores.headOption match
        case None        =>
          lastErr match
            case Some(err) => ZIO.fail(err)
            case None      => ZIO.succeed(None)
        case Some(store) =>
          store
            .get(id, range)
            .catchAll {
              case e: BackendUnavailable => loop(stores.drop(1), Some(e))
              case e                     => ZIO.fail(e)
            }
            .flatMap {
              case Some(bytes) => ZIO.succeed(Some(bytes))
              case None        => loop(stores.drop(1), lastErr)
            }
    loop(allStores, None)

  def delete(id: BinaryId): IO[Throwable, Boolean] =
    ZIO
      .foreach(allStores) { store =>
        store.delete(id).map(Right(_)).catchAll {
          case e: BackendUnavailable => ZIO.succeed(Left(e))
          case e                     => ZIO.fail(e)
        }
      }
      .flatMap { results =>
        val successes = results.collect { case Right(b) => b }
        if successes.nonEmpty then ZIO.succeed(successes.exists(identity))
        else ZIO.fail(BackendUnavailable("all replicas failed"))
      }

  def exists(id: BinaryId): IO[Throwable, Boolean] =
    primary.exists(id)
