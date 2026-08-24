# Engineering Backlog

## Release blockers

- [ ] Verify artifact publication from a clean consumer project
- [ ] Freeze the initial public runtime API and compatibility policy
- [ ] Version the HTTP routes and document stable error codes
- [ ] Complete production authentication wiring and threat-model review
- [ ] Document framed-manifest and database migration guarantees

## Durability

- [ ] Wire the durable RocksDB key-value adapter as a CAS block backend
- [ ] Add unreachable-block garbage collection with a dry-run mode
- [ ] Add replica repair and reconciliation jobs
- [ ] Add crash-recovery tests for filesystem, PostgreSQL, and S3 paths

## Protocols

- [ ] Reach HTTP feature parity in the runnable gRPC server
- [ ] Add range reads, conditional requests, and idempotency keys
- [ ] Add multipart and resumable upload acceptance tests

## Evidence and delivery

- [ ] Publish a reproducible benchmark harness and results
- [ ] Add a clean external artifact-consumer check to CI
- [ ] Keep `TESTCONTAINERS=0 ./sbt scalafmtAll test` green
- [ ] Keep `./sbt docs/mdoc checkDocSnippets` green
- [ ] Keep `npm run docs:build --prefix docs` green
