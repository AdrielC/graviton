package graviton.runtime.model

import graviton.core.attributes.BinaryAttributes
import graviton.core.locator.BlobLocator
import graviton.core.scan.*
import graviton.runtime.policy.{BlobLayout, StorePolicy}
import zio.stream.*

final case class BlobWritePlan(
  locatorHint: Option[BlobLocator] = None,
  attributes: BinaryAttributes = BinaryAttributes.empty,
  layout: BlobLayout = BlobLayout.Monolithic,
  policy: Option[StorePolicy] = None,
  program: IngestProgram = IngestProgram.Default,
)

sealed trait IngestProgram derives CanEqual
object IngestProgram:
  case object Default extends IngestProgram

  final case class UsePipeline(pipeline: ZPipeline[Any, Throwable, Byte, Byte]) extends IngestProgram

  final case class UseScan[Out](label: String, build: () => FreeScan[Prim, Byte, Out]) extends IngestProgram
