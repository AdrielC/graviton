# Engineering Backlog

## Release candidate complete

- [x] Verify locally published artifacts from a clean external consumer project
- [x] Add source and binary compatibility policy tooling
- [x] Version the HTTP routes and preserve deprecated aliases
- [x] Implement OIDC/JWKS verification, capability enforcement, audit recording, CORS, TLS policy, and request controls
- [x] Add range reads, conditional requests, cursor pagination, and stable error envelopes
- [x] Add backend readiness checks and packaged-server smoke proof
- [x] Add transactional PostgreSQL replica-index persistence
- [x] Add write-quorum replication, validating fallback reads, and repair
- [x] Add conservative garbage collection with dry-run and quarantine
- [x] Add migration, backup, restore-drill, measurement, and soak scripts
- [x] Add release artifacts, checksums, SBOM, attestations, container publishing, and GitHub release automation
- [x] Add pinned CI, dependency review, dependency submission, CodeQL workflow analysis, and Dependabot configuration

## Must remain green

- [x] `TESTCONTAINERS=0 ./sbt scalafmtCheckAll test`
- [x] `GRAVITON_IT=1 ./sbt "server/testOnly graviton.server.EmbeddedPgFsCasRoundTripSpec"`
- [x] `./scripts/verify-external-consumer.sh`
- [x] `./sbt server/assembly && ./scripts/smoke-packaged-server.sh`
- [x] `./sbt docs/mdoc checkDocSnippets buildDocsAssets`
- [x] `npm run docs:build --prefix docs`

## Next implementation work

- [ ] Wire a runnable authenticated gRPC server with HTTP lifecycle parity
- [ ] Complete the RocksDB CAS block backend
- [ ] Add resumable and multipart upload acceptance tests
- [ ] Add scheduled replica scrubbing and repair orchestration
- [ ] Add S3 quarantine inventory and restore commands for operators
- [ ] Add long-duration crash, outage, and rolling-upgrade acceptance suites
- [ ] Publish retained benchmark samples only after representative environment qualification

## External repository setup

- [ ] Configure Sonatype credentials and PGP signing secrets before claiming Maven Central availability
- [ ] Configure the actual OIDC issuer, audience, and JWKS URI in deployment secrets
- [ ] Protect `main` with the final CI job names after the release-candidate PR merges
- [ ] Enable dependency graph, vulnerability alerts, automated security updates, and code scanning in repository settings
