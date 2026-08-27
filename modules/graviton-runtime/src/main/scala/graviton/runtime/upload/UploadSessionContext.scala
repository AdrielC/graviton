package graviton.runtime.upload

import zio.*

/** Fiber-local upload identity established only inside a validated request. */
trait UploadSessionContext:
  def current: IO[UploadSessionContext.Error, UploadSessionKey]

  def locally[R, E, A](key: UploadSessionKey)(effect: ZIO[R, E, A]): ZIO[R, E, A]

object UploadSessionContext:
  enum Error extends Throwable:
    case MissingSession

    override def getMessage: String = this match
      case MissingSession => "No upload session is active in this fiber"

  val current: ZIO[UploadSessionContext, Error, UploadSessionKey] =
    ZIO.serviceWithZIO[UploadSessionContext](_.current)

  def locally[R, E, A](key: UploadSessionKey)(effect: ZIO[R, E, A]): ZIO[R & UploadSessionContext, E, A] =
    ZIO.serviceWithZIO[UploadSessionContext](_.locally(key)(effect))

  val live: ULayer[UploadSessionContext] =
    ZLayer.scoped {
      FiberRef
        .make[Option[UploadSessionKey]](
          initial = None,
          fork = identity,
          join = (parent, _) => parent,
        )
        .map { ref =>
          new UploadSessionContext:
            override def current: IO[Error, UploadSessionKey] =
              ref.get.someOrFail(Error.MissingSession)

            override def locally[R, E, A](key: UploadSessionKey)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
              ref.locally(Some(key))(effect)
        }
    }
