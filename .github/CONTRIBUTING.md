# Contributing

The canonical contribution guide is [`CONTRIBUTING.md`](../CONTRIBUTING.md).

Before opening a pull request, run the validation appropriate to the change. At minimum:

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll test
./scripts/check-byte-streaming-hygiene.sh
./scripts/check-product-boundary.sh
python3 scripts/check-doc-truth.py
git diff --check
```

Public API, artifact, server, storage, or release changes also require compatibility, external-consumer, packaged-process, and backend-specific proof described in the canonical guide. Documentation must distinguish the latest release from code that exists only on `main`.
