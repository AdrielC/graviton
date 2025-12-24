# Initial Publishing Readiness

This checklist captures the remaining work required before publishing Graviton for the first time. It focuses on code completeness, documentation, release engineering, and validation.

## Code and API Readiness
- Finalize the `BlockStore` and `BlobStore` APIs so their semantics are stable for a 0.1 release. This should include the refined type work called out in the roadmap and the split between immutable and mutable object stores.
- Stabilize the filesystem and S3 backend implementations to ensure they conform to the finalized APIs and to the refined type expectations in the 0.1 plan.
- Ensure the CLI is feature-complete for ingest and retrieval flows and covered by end-to-end tests. Wire it through to the backends so it exercises the same code paths as the servers.
- Add configuration-driven integration tests (e.g., TestContainers-based) that validate the storage backends and protocol fronts together.
- Keep schema changes in lockstep with regenerated bindings in `modules/pg/src/main/resources/generated/` when the DDL evolves.

## Documentation and Guides
- Audit for legacy terminology and keep docs consistently Graviton-centric.
- Keep the streaming docs cohesive: blocks/blobs/manifests/attributes + chunking, cross-linked from Getting Started.
- Flesh out CLI usage pages that cover installation, command options, sample inputs/outputs, and end-to-end flows.
- Keep API + ops pages aligned with the actual state of protocol handlers and backend implementations (avoid aspirational “full-featured” claims).

## Testing and Quality Gates
- Keep running `TESTCONTAINERS=0 ./sbt scalafmtAll test` as the pre-merge validation set.
- Expand coverage with integration tests that exercise filesystem, S3, PostgreSQL, and RocksDB paths through the protocol surfaces.
- Maintain no-throw guarantees across boundaries—errors should surface as `Either`/`ZIO` results to match the runtime contracts.

## Release Engineering
- Set up CI to run the validation suite, build and publish docs, and push artifacts to Maven Central. Ensure docs builds keep the nav links healthy.
- Verify versioning and coordinates in `version.sbt` and `build.sbt` align with the initial publish target.
- Prepare a concise release note that links to the documentation site and highlights the supported backends, APIs (HTTP/gRPC), and CLI capabilities.
