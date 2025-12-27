# TODO

- Keep docs builds green:
  - `./sbt docs/mdoc checkDocSnippets`
  - `cd docs && npm run docs:build`

- Flesh out end-user usage guides:
  - CLI + server workflows (curl examples, troubleshooting)
  - Backend configuration deep dives (filesystem + S3/MinIO + Postgres)
  - Replication model (Stores, Sectors, Replicas) + diagrams

- Harden API reference:
  - Explicit stability levels (demo-only vs stable)
  - Error model and status codes for HTTP routes
  - End-to-end examples that round-trip bytes + metadata

- Performance + ops:
  - JVM tuning notes + backpressure guidance for large streams
  - Load testing recipes and metrics interpretation

- Release readiness:
  - v0.1.0 roadmap (APIs, packaging, versioning guarantees)
  - CI workflows and publishing (Maven Central)

