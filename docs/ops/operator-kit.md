# Operator Kit

The Operator Kit is a stable, agent-agnostic boundary around local and production qualification. It never prints secret values. `plan` is read-only. `restore-verify` extracts only into an isolated temporary directory. AWS apply remains a separate, explicit action after the Terraform plan and cost are reviewed.

```bash
./scripts/graviton-operator doctor
./scripts/graviton-operator doctor --endpoint http://127.0.0.1:8081
./scripts/graviton-operator plan --profile local
./scripts/graviton-operator plan --profile aws
./scripts/graviton-operator qualify --endpoint http://127.0.0.1:8081 --evidence ./qualification-evidence
./scripts/graviton-operator restore-verify
./scripts/graviton-operator restore-verify --archive /path/to/graviton-fs-....tar.gz
./scripts/graviton-operator quarantine-inventory --bucket graviton-blocks --prefix cas/blocks --output ./quarantine.jsonl
./scripts/graviton-operator quarantine-restore --bucket graviton-blocks --prefix cas/blocks --token 'exact-token'
./scripts/graviton-operator quarantine-restore --bucket graviton-blocks --prefix cas/blocks --token 'exact-token' --apply
```

`qualify` refuses a dirty source tree, checks both the portable observability bundle and production qualification contract, retains the exact qualification matrix, captures readiness and Prometheus output, and performs a real upload, inventory, paged manifest read, verification, download, byte comparison, and deletion. Evidence includes the exact Git commit and a required clean-tree assertion.

For deterministic payloads, use `scripts/generate-qualification-corpus.py`. It generates base, sparse-edit, and repeated-block files without embedding customer data.

`quarantine-inventory` is read-only and fetches at most one 1,000-object S3 page at a time. `quarantine-restore` prints a plan unless `--apply` is present. Apply verifies the source, copies one exact receipt to its canonical active key, verifies the content length, and only then removes the quarantine source. It rejects wildcards, traversal, and tokens outside the configured prefix.

The published contracts live in `docs/api/openapi-v1.json`, `docs/api/blob-metadata-v1.schema.json`, and `docs/api/qualification-evidence-v1.schema.json`. The portable SLO, alerts, dashboard, and CloudWatch collector configuration live under `deploy/observability-v1`. The honest acceptance matrix and its schema live under `deploy/qualification-v1`.
