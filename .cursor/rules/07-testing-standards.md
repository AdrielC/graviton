# Testing Standards

## Framework

- **zio-test** is the sole test framework. Test suites extend `ZIOSpecDefault`.
- Test framework is registered in build: `new TestFramework("zio.test.sbt.ZTestFramework")`.
- Tests run forked with `-Xmx2G -Xms512M -XX:+UseG1GC` (see `BuildHelper.testSettings`).

## Running Tests

```bash
# All tests (no TestContainers — CI default):
TESTCONTAINERS=0 ./sbt scalafmtAll test

# Docs validation:
./sbt docs/mdoc checkDocSnippets

# Single module:
TESTCONTAINERS=0 ./sbt "core/test"
TESTCONTAINERS=0 ./sbt "streams/test"
TESTCONTAINERS=0 ./sbt "runtime/test"
```

## Test Organization

```
modules/<mod>/src/test/scala/graviton/<pkg>/
  FooSpec.scala        — unit / property tests
  FooIntegrationSpec.scala — integration tests (may need external services)
```

## Patterns

### Spec Structure

```scala
object FooSpec extends ZIOSpecDefault:
  def spec = suite("Foo")(
    test("should do X") {
      for
        result <- Foo.doX(input)
      yield assertTrue(result == expected)
    },
    suite("edge cases")(
      test("empty input") { ... },
      test("max size") { ... },
    ),
  )
```

### Property-Based Testing

Use `zio.test.Gen` and `check` for property-based tests:

```scala
test("roundtrip") {
  check(Gen.chunkOf(Gen.byte)) { bytes =>
    val encoded = encode(bytes)
    val decoded = decode(encoded)
    assertTrue(decoded == Right(bytes))
  }
}
```

### Testing Refined Types

Test boundary values explicitly:

```scala
test("BlockSize rejects 0") {
  assertTrue(BlockSize.either(0).isLeft)
}
test("BlockSize accepts 1") {
  assertTrue(BlockSize.either(1).isRight)
}
test("BlockSize accepts max") {
  assertTrue(BlockSize.either(16 * 1024 * 1024).isRight)
}
test("BlockSize rejects max+1") {
  assertTrue(BlockSize.either(16 * 1024 * 1024 + 1).isLeft)
}
```

### Testing Streaming Pipelines

```scala
test("chunker produces blocks within bounds") {
  val bytes = Chunk.fromArray(Array.fill(10000)(42.toByte))
  for
    blocks <- ZStream.fromChunk(bytes)
                .via(chunker.pipeline.mapError(Chunker.toThrowable))
                .runCollect
  yield assertTrue(blocks.forall(b => b.length >= 1 && b.length <= Block.maxBytes))
}
```

### Testing Scans

```scala
test("counter scan counts elements") {
  val (_, result) = (FS.counter[Int]).runChunk(List(1, 2, 3))
  assertTrue(result == Chunk(1L, 2L, 3L))
}
```

## Test Naming

- `FooSpec` for unit tests of `Foo`.
- `FooIntegrationSpec` for tests needing external services.
- `FooPerfSpec` for performance benchmarks (not run in CI by default).

## What To Test

- **Core domain**: All refined type constructors (boundary values, invalid inputs).
- **Manifests**: Encode → decode roundtrip, validation edge cases.
- **Chunkers**: Fixed/CDC/delimiter with various input sizes, empty input, max-size input.
- **Scans**: Composition laws, flush behavior, empty input.
- **Stores**: Roundtrip (put → get → compare), duplicate detection, missing key behavior.
- **Codecs**: scodec roundtrip for all frame types.
