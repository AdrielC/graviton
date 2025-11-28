package graviton.runtime.dashboard

import graviton.shared.ApiModels.*
import graviton.shared.dashboard.DashboardSamples
import zio.*
import zio.stream.*

import java.time.Instant
import java.time.format.DateTimeFormatter

trait DatalakeDashboardService {
  def snapshot: UIO[DatalakeDashboard]
  def metaschema: UIO[DatalakeMetaschema]
  def updates: ZStream[Any, Nothing, DatalakeDashboard]
  def publish(update: DatalakeDashboard): UIO[Unit]
}

object DatalakeDashboardService {

  val live: ZLayer[Clock & Random, Nothing, DatalakeDashboardService] =
    ZLayer.scoped {
      for {
        hub    <- Hub.unbounded[DatalakeDashboard]
        state  <- Ref.make(DashboardSamples.snapshot)
        _      <- hub.publish(DashboardSamples.snapshot)
        service = new Live(state, hub, DashboardSamples.metaschema)
        _      <- DashboardSeeder.run(service).forkScoped
      } yield service
    }

  private final class Live(
    state: Ref[DatalakeDashboard],
    hub: Hub[DatalakeDashboard],
    metaschema0: DatalakeMetaschema,
  ) extends DatalakeDashboardService {

    def snapshot: UIO[DatalakeDashboard] = state.get

    def metaschema: UIO[DatalakeMetaschema] = ZIO.succeed(metaschema0)

    def updates: ZStream[Any, Nothing, DatalakeDashboard] = ZStream.fromHub(hub)

    def publish(update: DatalakeDashboard): UIO[Unit] =
      state.set(update) *> hub.publish(update).unit
  }
}

private object DashboardSeeder {
  private val formatter = DateTimeFormatter.ISO_INSTANT

  def run(service: DatalakeDashboardService): URIO[Clock & Random, Unit] =
    ZStream
      .repeatZIO {
        for {
          base   <- service.snapshot
          now    <- Clock.instant
          jitter <- Random.nextIntBetween(50, 7500)
        } yield synthesize(base, now, jitter)
      }
      .schedule(Schedule.spaced(30.seconds))
      .mapZIO(service.publish)
      .runDrain

  private def synthesize(base: DatalakeDashboard, now: Instant, jitter: Int): DatalakeDashboard = {
    val isoNow   = formatter.format(now)
    val newEntry = DatalakeChangeEntry(
      date = isoNow,
      area = "Telemetry",
      update = s"Ingested $jitter new objects via streaming dashboard",
      impact = "Streaming pipeline verified end-to-end.",
      source = "DatalakeDashboardSeeder",
    )

    base.copy(
      lastUpdated = isoNow,
      branch = s"${base.branch.split('@').headOption.getOrElse(base.branch)}@$isoNow",
      changeStream = (newEntry :: base.changeStream).take(20),
    )
  }
}
