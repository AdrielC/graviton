# Logging

Graviton integrates ZIO Logging for structured output. Each major operation
emits an `info` log when starting and finishing and an `error` log if the
operation fails. A correlation ID is attached to all log entries so actions can
be traced across different layers of the system. Wrap any existing
`BinaryStore` with `LoggingBinaryStore.layer` to opt-in.

```scala mdoc:silent
import graviton.BinaryStore
import graviton.impl.InMemoryBinaryStore
import graviton.logging.LoggingBinaryStore
import zio.ZLayer
import scala.annotation.nowarn

@nowarn("msg=unused value")
val loggingStore: ZLayer[Any, Nothing, BinaryStore] =
  ZLayer.fromZIO(InMemoryBinaryStore.make()) >>> LoggingBinaryStore.layer
```

The layer generates a UUID when a request enters the store and carries it
through subsequent operations using a `FiberRef`. If your service already
provides correlation IDs (for example from an HTTP header) call
`ZIO.logAnnotate("correlation-id", value)` before invoking the store to reuse
the upstream identifier.

## Configuration

Logging uses the standard ZIO Logging layers. The default runtime prints to the
console and honors the `ZIO_LOG_LEVEL` environment variable. To route logs
through SLF4J and populate MDC entries with the correlation ID:

```scala
import zio.Runtime
import zio.logging.backend.SLF4J

val runtime = Runtime.removeDefaultLoggers >>> SLF4J.slf4j
```

SLF4J bridges automatically copy log annotations to MDC, so Kibana or CloudWatch
queries can filter on `correlation-id`. Any other backend provided by ZIO
Logging can be supplied in the same way.

## JSON console format

The built-in console logger can be configured to emit JSON for ingestion into
structured pipelines such as Loki:

```scala mdoc:silent
import zio.Runtime
import zio.logging.consoleJsonLogger
import scala.annotation.nowarn

@nowarn("msg=unused value")
val jsonLogger = Runtime.removeDefaultLoggers >>> consoleJsonLogger()
```

Extend the resulting `LogFormat` with additional annotations (such as request
IDs, tenant IDs, or ingest job names) before wiring it into your runtime.

## Testing

`ZTestLogger` captures log entries for assertions in unit tests:

```scala
import zio.test.ZTestLogger

val effect =
  for
    _    <- myStore.exists(BinaryId("1"))
    logs <- ZTestLogger.logOutput
  yield assertTrue(logs.head.annotations.contains("correlation-id"))

effect.provideLayer(ZTestLogger.default)
```
