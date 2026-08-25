# ADR 0002: Compatibility and Releases

## Status

Accepted for 0.1.

## Decision

Graviton uses semantic version tags and treats the 0.x line as evolving but governed. Public Scala modules are checked against the previous release with `sbt-version-policy`. The canonical HTTP surface is `/api/v1`; legacy `/api/blobs` aliases return deprecation and successor headers.

Content-key rendering is stable. Committed framed manifests and PostgreSQL schema changes require backward readers or an explicit migration path. A migration ledger records the checksum of applied database DDL and fails on drift.

A release tag must pass tests, packaged-server smoke proof, and external-consumer proof before publishing artifacts, checksums, SBOM, attestations, container images, or release notes.

## Consequences

- code compatibility, storage compatibility, and HTTP compatibility are separate gates
- 0.x minor releases may break APIs only with release notes and migration guidance
- Maven Central availability cannot be claimed until signing and Sonatype credentials are configured and a publication succeeds
