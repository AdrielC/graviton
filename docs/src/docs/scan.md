# Scan utilities

`Scan` provides composable, fusion-friendly stream transformations.  Stateless
scans fuse at compile time, while stateful ones keep tuple-based state that grows
only when necessary.

```scala

Scan.stateful(0) { (s: Int, i: Int) =>
  val sum = s + i
  (sum, Chunk.single(sum))
}(s => Chunk.single(s))
.runAll(List(1, 2, 3))

```


Use `.toPipeline` or `.toSink` to integrate with `ZStream`, and build larger
scans via `andThen`, `zip`, and `runZPure` for pure folds.
