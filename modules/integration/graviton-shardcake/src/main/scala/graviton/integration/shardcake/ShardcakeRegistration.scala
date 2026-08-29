package graviton.integration.shardcake

import zio.*

private[shardcake] object ShardcakeRegistration:

  private case object RegistrationNotConfirmed extends RuntimeException("Shardcake manager did not persist the node registration")

  def scoped(
    register: Task[Unit],
    isRegistered: Task[Boolean],
    unregister: UIO[Unit],
    retryInterval: Duration,
    timeout: Duration,
  ): ZIO[Scope, Throwable, Unit] =
    for
      startedAt <- Clock.nanoTime
      acquire    = register *> isRegistered.flatMap {
                     case true  => ZIO.unit
                     case false => ZIO.fail(RegistrationNotConfirmed)
                   }
      _         <- ZIO.acquireRelease(retry(acquire, retryInterval, timeout, startedAt).interruptible)(_ => unregister)
    yield ()

  private def retry(
    register: Task[Unit],
    retryInterval: Duration,
    timeout: Duration,
    startedAt: Long,
  ): Task[Unit] =
    register.catchAll { error =>
      Clock.nanoTime.flatMap { now =>
        val elapsed = Duration.fromNanos(now - startedAt)
        if elapsed >= timeout then ZIO.fail(error)
        else
          val remainingNanos = timeout.toNanos - elapsed.toNanos
          val delay          = Duration.fromNanos(math.min(retryInterval.toNanos, remainingNanos))
          val detail         = Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
          ZIO.logWarning(s"Shardcake registration failed; retrying in ${delay.render}: $detail") *>
            ZIO.sleep(delay) *>
            retry(register, retryInterval, timeout, startedAt)
      }
    }
