package graviton.runtime.dashboard

import graviton.shared.ApiModels.*
import graviton.shared.dashboard.DashboardSamples
import graviton.shared.schema.SchemaExplorer
import zio.*
import zio.stream.*

import java.time.format.DateTimeFormatter

trait DatalakeDashboardService {
  def snapshot: UIO[DatalakeDashboard]
  def metaschema: UIO[DatalakeMetaschema]
  def explorer: UIO[SchemaExplorer.Graph]
  def updates: ZStream[Any, Nothing, DatalakeDashboard]
  def publish(update: DatalakeDashboard): UIO[Unit]
}

object DatalakeDashboardService {

  /**
   * Dashboard state with an honest reference snapshot.
   *
   * Runtime updates are emitted only when a caller invokes `publish`; the
   * service never synthesizes throughput, ingest, or health events.
   */
  val live: ZLayer[Clock, Nothing, DatalakeDashboardService] =
    ZLayer.scoped {
      for {
        hub    <- Hub.sliding[DatalakeDashboard](64)
        now    <- Clock.instant
        initial = DashboardSamples.snapshot.copy(
                    lastUpdated = DateTimeFormatter.ISO_INSTANT.format(now),
                    branch = "runtime-reference",
                  )
        state  <- Ref.make(initial)
        service = new Live(state, hub, DashboardSamples.metaschema, DashboardSamples.schemaExplorer)
      } yield service
    }

  private final class Live(
    state: Ref[DatalakeDashboard],
    hub: Hub[DatalakeDashboard],
    metaschema0: DatalakeMetaschema,
    explorer0: SchemaExplorer.Graph,
  ) extends DatalakeDashboardService {

    def snapshot: UIO[DatalakeDashboard] = state.get

    def metaschema: UIO[DatalakeMetaschema] = ZIO.succeed(metaschema0)

    def explorer: UIO[SchemaExplorer.Graph] = ZIO.succeed(explorer0)

    def updates: ZStream[Any, Nothing, DatalakeDashboard] = ZStream.fromHub(hub)

    def publish(update: DatalakeDashboard): UIO[Unit] =
      state.set(update) *> hub.publish(update).unit
  }
}
