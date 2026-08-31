# Engineering Backlog

This file lists current work only. Completed capability and release state lives in the [implementation status ledger](docs/implementation-status.md); priorities and qualification boundaries live in [ROADMAP.md](ROADMAP.md).

## Before the next release

- [ ] Run `./scripts/verify-version-compatibility.sh` against `v0.7.0`.
- [ ] Run `./scripts/verify-external-consumer.sh` and `./scripts/audit-published-artifacts.sh` from a clean checkout.
- [ ] Run the packaged HTTP and gRPC smoke plus filesystem backup and restore proof.
- [ ] Confirm the release notes say that GVM4 and PostgreSQL V001 are clean-store formats with no legacy reader or backfill path.
- [ ] Tag only the exact commit that passed CI. The release workflow requires Maven Central and PGP credentials and fails closed when they are missing or invalid.

## Code

- [ ] Remove or quarantine unbuilt source trees after preserving any useful experiments.
- [ ] Add a RocksDB block backend only with the published backend laws and real restart, interruption, corruption, and concurrency proof.
- [ ] Keep broad document parsing, extraction, search, and indexing outside the core byte runtime.

## Target acceptance

- [ ] Run every `target-required` gate in `deploy/qualification-v1/matrix.json` against the intended deployment.
- [ ] Retain capacity, soak, failure, and restore evidence for the exact image digest and infrastructure.
- [ ] Validate the real IdP, ingress, TLS, CORS, database, object store, Redis or Valkey, and alert routing.
- [ ] Record explicit capacity and recovery envelopes instead of claiming a universal customer count.
