# Backup, Restore, and Garbage Collection

Content addressing detects corruption; it does not create a backup. A recoverable Graviton deployment needs both manifest state and every referenced block.

## Filesystem backup

For a single-node filesystem deployment, quiesce writes or take an atomic volume snapshot before archiving. Then run:

```bash
GRAVITON_FS_ROOT=/var/lib/graviton \
  ./scripts/backup.sh /secure/backups/graviton
```

The script creates a timestamped tar archive and SHA-256 checksum. If `GRAVITON_DATABASE_URL` is also set, it creates a PostgreSQL custom-format dump. The script cannot make an uncoordinated live filesystem plus database snapshot transactionally consistent, so use a maintenance window or storage-level snapshot when both are authoritative.

## Restore drill

Run the drill against a copy of every backup class:

```bash
./scripts/restore-drill.sh \
  /secure/backups/graviton/graviton-fs-20260825T000000Z.tar.gz
```

The drill:

1. verifies the archive checksum
2. extracts into an isolated temporary directory
3. rejects symbolic links and incomplete temporary files
4. lists every persisted manifest with a fresh CLI JVM
5. streams and hashes every blob with `verify`
6. removes the temporary restore directory

A passing drill proves the filesystem archive can reconstruct and verify its logical blobs on the current build. It does not prove the backup age meets a business recovery point objective.

## PostgreSQL restore

Restore custom-format dumps with the PostgreSQL tools appropriate for the target version. Restore into an isolated database first, run the schema migration script, start Graviton against the restored database and a copy of the block store, then enumerate and verify every blob before promotion.

```bash
createdb graviton_restore
pg_restore --no-owner --no-privileges --dbname=graviton_restore graviton-pg-*.dump
GRAVITON_DATABASE_URL=postgresql://.../graviton_restore ./scripts/migrate-postgres.sh
```

## S3 backups

`scripts/backup.sh` does not copy an S3 bucket. Use provider-native versioning, replication, object lock, inventory, and recovery controls that fit the deployment. Coordinate the retained object version with the PostgreSQL manifest backup. Test restoring into a new bucket and database rather than overwriting the active deployment.

## Filesystem garbage collection

Logical deletion removes a manifest and leaves shared blocks in place. The filesystem CLI uses a two-pass mark, minimum object age, and reversible quarantine. Each run streams manifest summaries and block inventory once into an exact temporary-disk join. The second mark streams manifests again and checks only the candidate spool before moving a block. Heap use is bounded by the configured digest partition, not by blob or block count.

Preview candidates:

```bash
GRAVITON_DATA_DIR=/var/lib/graviton \
  ./sbt --error "cli/run gc --min-age-hours 168"
```

Quarantine only after reviewing the preview and confirming current backups:

```bash
GRAVITON_DATA_DIR=/var/lib/graviton \
  ./sbt --error "cli/run gc --apply --min-age-hours 168"
```

The library exposes the narrow `GarbageCollection` ZIO service rather than coupling an application to filesystem, PostgreSQL, or S3 classes. Its `sweep` operation delivers each `QuarantinedBlock` to a caller-provided effect as it is moved, and its restore and purge APIs consume receipt streams. The callback is compensation-safe: if recording a receipt fails, Graviton restores that just-quarantined block before returning the failure. The old `GarbageCollector.collect` convenience method is intentionally capped for small compatibility receipts and rejects a repository-scale result before mutation.

The CLI intentionally does not purge immediately. Preserve the tokens it prints during `gc --apply` in an operator change record. Ensure the process has temporary-disk capacity for the reference, inventory, and candidate spools. `GarbageCollectionConfig.workspaceDirectory` can place that workspace on an operator-selected volume; otherwise it uses the process temporary directory and removes its child workspace on completion or interruption. The CLI loads this through ZIO Config as `GRAVITON_GC_WORKSPACE_DIRECTORY`; `GRAVITON_GC_MAX_REFERENCES_PER_PARTITION` controls the exact-mark heap bound (default `8192`). Minimum age and the second mark reduce concurrent-ingest risk, but they are not a substitute for stopping writes or acquiring a backend-wide maintenance lease when an atomic cross-store snapshot is required.

## S3 quarantine

`S3BlockStore` implements inventory, quarantine, restore, and purge through the `BlockMaintenance` API. Quarantine copies an object into the configured `.graviton-quarantine/` prefix before deleting the active key. Restore copies it back before removing the quarantine object. Automatic S3 GC scheduling and an operator CLI are not included in 0.1.

## Acceptance record

Retain at least:

- backup timestamp and immutable location
- application revision and backend configuration
- manifest and block source snapshots
- checksum results
- restore target and elapsed time
- number of blobs and bytes verified
- failures, retries, and remediation
- measured recovery point and recovery time
