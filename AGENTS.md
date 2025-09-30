# AGENTS

This file captures future work items discussed with the AI.

## Suggested Next Steps

### Documentation
1. Replace the lingering "Torrent" reference in the design goals with a Graviton-centric explanation of the prototype lessons.
2. Write a dedicated binary-streaming guide and link it from the "Getting Started" section.
3. Expand Prometheus metrics docs with a full example showing `MetricsBinaryStore`, Prometheus publisher/updater layers, and a sample scrape configuration.
4. Flesh out logging guidance by documenting correlation-ID propagation and customizable backends such as SLF4J.
5. Add a content-detection page for the Apache Tika module and link it under modules.
6. Provide configuration guides for filesystem and S3 backends (credentials, paths, buckets, environment variables).
7. Replace the CLI/HTTP examples with full usage pages that include installation, options, and response formats.
8. Port performance/optimization notes and API reference material from Torrent; ensure new pages appear in the navigation index.
9. Introduce a CONTRIBUTING guide describing coding style, test commands, and doc-building requirements.
10. After each doc change, run `./sbt docs/mdoc test` and update any broken navigation links as noted in the TODO list.

### Implementation / Testing
1. Finalize BlockStore and BlobStore APIs and stabilize S3/filesystem implementations.
2. Build out a CLI and an HTTP gateway with end-to-end tests.
3. Add configuration-driven integration tests (including TestContainers) for all storage backends.
4. Set up CI workflows that run tests, publish docs, and push artifacts to Maven Central.
5. Fold in the Cedar 2.0 storage refactor: adopt Iron refined types for block sizes and indices, split `BinaryAttributes` into advertised/confirmed maps keyed by `BinaryAttributeKey`, and ensure `BinaryAttributes.validate` gates ingest.
6. Introduce a `Chunker` abstraction that exposes a name and `ZPipeline[Any, Throwable, Byte, Block]`, configurable through a `FiberRef`.
7. Unify storage behind the new `BinaryStore` sinks: keep `insert`/`insertWith` as the single-sink operations that return a `BinaryKey` plus any leftover bytes, layer an `insertFile` helper that replays leftovers until the stream is exhausted, and support both block-deduplicated and direct file ingestion modes (the latter renamed simply "FileStore").
8. Track ingestion context via `FiberRef`s for the active chunker, current attributes, and preferred store mode.
9. Add attribute retrieval APIs and persist advertised/confirmed metadata alongside manifests.
10. Build ZIO HTTP endpoints that use Iron validation and `BinaryAttributeKey` metadata, returning streaming downloads.
11. Before opening a PR, run `./sbt scalafmtAll` to enforce the shared formatting rules.

## Upcoming Work Focus

### Documentation backlog
- Translate the binary streaming and chunking guides from Torrent into Graviton’s docs and create a getting-started guide covering core concepts.
- Update navigation links, remove residual Torrent references, and expand Prometheus metrics and structured logging docs, ensuring `./sbt docs/mdoc test` passes after each change.
- Port API reference, performance, and core concept docs (content-addressable storage, binary streaming), and add a contributing guide.

### Roadmap items leading to v0.1.0
- Finalize BlockStore and BlobStore APIs, provide filesystem and S3 implementations with configuration docs, and ship a CLI and HTTP gateway.
- Publish basic metrics and structured logging, deliver getting-started and backend configuration guides, and ensure integration tests cover core storage paths, with CI workflows for artifact publishing.

### Additional enhancements
- Document CLI/HTTP usage, configuration examples for filesystem and S3 backends, richer metrics/logging snippets, broader test coverage (including TestContainers), and CI workflows for publishing to Maven Central.

## Workflow Requirements

- Always run `./sbt scalafmtAll && ./sbt test` before committing or opening a pull request.
- Sync with the latest `main` branch (e.g., `git fetch origin main` followed by `git merge origin/main` or an equivalent rebase) before creating commits so conflict resolution happens early.
