package graviton.protocol.http

import graviton.runtime.stores.BlobStore
import zio.http.{Handler, Request, Response}

final case class HttpApi(blobStore: BlobStore):
  val app: Handler[Any, Nothing, Request, Response] = Handler.fromFunction(_ => Response.text("ok"))
