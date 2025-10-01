# AGENTS — Refined TODO (Graviton-focused, no BinaryStore)

## Documentation

**Core for v0.1.0**
- [ ] Replace lingering *Torrent* references with Graviton-centric language.
- [ ] Write a **binary-streaming guide** (Blocks, Blobs, Manifests, Attributes) and link it from *Getting Started*.
- [x] Add **filesystem + S3 backend configuration guides** (credentials, paths, env vars, buckets). (done in 8d3e829 2025-10-01)
- [x] Expand Prometheus metrics docs with a worked example (`MetricsBlobStore`, publisher/updater, scrape config). (done in 8d3e829 2025-10-01)
- [x] Document logging: correlation-ID propagation, SLF4J backend setup. (done in 8d3e829 2025-10-01)
- [ ] Flesh out CLI usage pages: install, options, sample commands and responses.
- [ ] Introduce a CONTRIBUTING guide (coding style, test commands, doc build).

**Follow-ups**
- [ ] Add Apache Tika module page under *Modules*.
- [ ] Port performance notes + API reference from Torrent.
- [ ] Ensure `./sbt docs/mdoc test` passes after each doc change; fix nav links.
- [ ] Document replication model (Stores, Sectors, Replicas) with diagrams.
- [ ] Document manifest format and forward-compatibility guarantees.

---

## Implementation / Testing

**Core for v0.1.0**
- [ ] Finalize `BlockStore` and `BlobStore` APIs.
- [ ] Stabilize filesystem and S3 implementations.
- [ ] Ship a CLI with end-to-end ingest + retrieval tests.
- [ ] Add configuration-driven integration tests (TestContainers for backends).
- [ ] Set up CI: run tests, publish docs, push to Maven Central.

**Refactor / Storage API**
- [ ] Adopt Iron refined types for sizes/indices.
- [ ] Split `BinaryAttributes` into advertised/confirmed keyed by `BinaryAttributeKey`; enforce `validate` at ingest.
- [ ] Introduce `Chunker` abstraction (`ZPipeline[Any, Throwable, Byte, Block]`), configurable via `FiberRef`.
- [ ] Provide `insertFile` helper to replay leftovers until stream exhaustion (whole-file ingest mode).
- [ ] Track ingestion context via `FiberRef` (chunker, attributes, store mode).
- [ ] Persist advertised/confirmed attributes with manifests.

---

## Advanced Enhancements (post-v0.1.0)

- [ ] Implement anchored ingest pipeline (transport decode → sniff → anchor tokenize → CDC → compress/encrypt → frame emit → manifest).
- [ ] Build tokenizer + CDC pipeline (DFA/Aho-Corasick, `ZSink.foldWeightedDecompose`, 1 MiB rechunker via `ZPipeline.anchoredCdc`).
- [ ] Define + implement **self-describing frame format** (magic `"QUASAR"`, algo IDs, sizes, nonce, truncated hash, key ID, AAD encoding, strict `Take` semantics).
- [ ] Extend deduplication: store CDC base blocks, keep manifests mutable, prototype rolling-hash index for containment.
- [ ] Add format-aware views (PDF object/page maps, ZIP central directory) layered over manifest offsets.
- [ ] Add operational guardrails: bounded `Queue[Take]`, guaranteed `OutputStream` closure, ingestion bomb protection, enforced pre-commit checks.
- [ ] Add repair jobs for missing or quarantined replicas.
- [ ] Implement cold-storage tiering (configurable store policies for “active” vs “archival”).
- [ ] Add background compaction (repack small blocks, drop deprecated replicas).
- [ ] Expose metrics for deduplication ratio, replication health, and ingest latency.

---

## Workflow Requirements

### PostgreSQL Setup (Automated)

The project includes `./scripts/ensure-postgres.sh` which **automatically handles PostgreSQL** in any environment:

#### Local Development (macOS/Linux with Docker)
```bash
# Automatic setup - tries Docker → Podman → Native
eval "$(./scripts/ensure-postgres.sh)"

# Or let sbt handle it automatically
GRAVITON_BOOTSTRAP_PG=1 ./sbt test
```

#### CI/Agent Environments (Ephemeral)
The script **auto-detects** ephemeral environments and installs postgres when needed:

```bash
# CI platforms (GitHub Actions, GitLab CI, etc.) - CI=true is auto-set
./scripts/ensure-postgres.sh --engine native

# Custom agents - set AGENT=true or ENV=AGENT
AGENT=true ./scripts/ensure-postgres.sh --engine native
ENV=AGENT ./scripts/ensure-postgres.sh --engine native

# Explicit auto-install
AUTO_INSTALL_PG=1 ./scripts/ensure-postgres.sh --engine native
```

**Supported platforms for auto-install:**
- macOS → Homebrew (`brew install postgresql@17`)
- Ubuntu/Debian → apt (`apt-get install postgresql-17`)
- RHEL/CentOS/Fedora → dnf/yum (`dnf install postgresql17-server`)
- Arch Linux → pacman (`pacman -S postgresql`)

**Engine fallback chain:** Docker → Podman → Native (with auto-install in CI/agents)

The script:
1. Detects if postgres is already running (any source)
2. If not, starts via Docker/Podman or native `pg_ctl`
3. Creates databases and applies DDL schema
4. Exports connection variables for sbt/tests

#### sbt Integration
The `setUpPg` task is integrated and uses `ensure-postgres.sh`:

```bash
# Manual postgres setup
./sbt setUpPg

# Auto-run on sbt startup
GRAVITON_BOOTSTRAP_PG=1 ./sbt

# Direct test (postgres auto-started if needed)
./sbt test
```

### Testing & Code Quality

- Always run:
  ```bash
  TESTCONTAINERS=0 ./sbt scalafmtAll test
  ```

### Schema Changes & Code Generation

Schema changes **must** be reflected in generated bindings:

1. **Ensure postgres is running** (automatic via script above)

2. **Apply DDL changes:**
   ```bash
   # The ensure-postgres.sh script auto-applies DDL, or manually:
   psql -h 127.0.0.1 -p 5432 -U postgres -d graviton -f modules/pg/ddl.sql
   ```

3. **Regenerate bindings:**
   ```bash
   # Option A: Use environment variables (set by ensure-postgres.sh)
   eval "$(./scripts/ensure-postgres.sh)"
   ./sbt "dbcodegen/run"
   
   # Option B: Manual connection params
   PG_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/graviton \
   PG_USERNAME=postgres \
   PG_PASSWORD=postgres \
   ./sbt "dbcodegen/run"
   ```

4. **Commit changes:**
   - DDL: `modules/pg/ddl.sql`
   - Generated code: `modules/pg/src/main/scala/graviton/pg/generated/`

### Git Workflow
- Sync with latest main before commits: `git fetch origin main && git merge origin/main` (or rebase)
- Mark completed tasks in this file with commit hash and date
