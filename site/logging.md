# Logging

Graviton integrates ZIO Logging for structured output. Each major operation
emits an `info` log when starting and finishing and an `error` log if the
operation fails. A correlation ID is attached to all log entries so actions can
be traced across different layers of the system.

## Configuration

Logging uses the standard ZIO Logging layers. The default runtime prints to the
console and honors the `ZIO_LOG_LEVEL` environment variable. To route logs
through SLF4J:

```scala
import zio.Runtime
import zio.logging.backend.SLF4J

val runtime = Runtime.removeDefaultLoggers >>> SLF4J.slf4j
```

Any other backend provided by ZIO Logging can be supplied in the same way.

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
