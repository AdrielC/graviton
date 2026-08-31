#!/usr/bin/env python3
"""Validate Graviton's versioned, byte-substrate-only PostgreSQL migrations."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "modules/backend/graviton-pg/src/main/resources/db/migration"
EXPECTED_TABLES = {
    "acl_entry",
    "audit_log",
    "blob",
    "blob_block",
    "blob_manifest_page",
    "block",
    "catalog_entry",
    "key_value",
    "object_chunk",
    "object_data",
    "repair_dead_letter",
    "repair_state",
    "replica_index",
    "shardcake_assignment",
    "shardcake_pod",
    "tenant_blob",
    "tenant_blob_block",
    "tenant_block",
    "tenant_domain_repair_epoch",
    "tenant_domain_snapshot",
    "tenant_domain_snapshot_member",
    "tenant_storage_policy",
    "tenant_storage_usage",
    "upload_part",
    "upload_session",
}
FORBIDDEN_IDENTIFIERS = {
    "graviton.block_location",
    "graviton.block_verify_event",
    "graviton.blob_store",
    "graviton.sector",
    "graviton.transform",
    "graviton.view",
    "graviton.view_input",
    "graviton.view_op",
    "graviton.view_materialization",
}
ALLOWED_EXTENSIONS = {"btree_gist", "pgcrypto"}
EXCLUDED_PARTS = {".vitepress", "dist", "node_modules", "public", "target"}


def fail(message: str) -> None:
    raise SystemExit(f"PostgreSQL migration check failed: {message}")


def main() -> None:
    migrations = sorted(MIGRATIONS.glob("V[0-9][0-9][0-9]__*.sql"))
    if not migrations:
        fail("no versioned migrations found")

    versions = [int(path.name[1:4]) for path in migrations]
    if versions != list(range(1, len(versions) + 1)):
        fail(f"migration versions must be contiguous from V001, found {versions}")

    sql = "\n".join(path.read_text(encoding="utf-8") for path in migrations)
    lower = sql.lower()
    for identifier in sorted(FORBIDDEN_IDENTIFIERS):
        if re.search(rf"\b{re.escape(identifier)}\b", lower):
            fail(f"document or unimplemented topology object leaked into migrations: {identifier}")

    extensions = set(
        re.findall(r"create\s+extension\s+if\s+not\s+exists\s+([a-z0-9_]+)", lower)
    )
    unexpected_extensions = extensions - ALLOWED_EXTENSIONS
    if unexpected_extensions:
        fail(f"unused extensions are not allowed: {sorted(unexpected_extensions)}")

    tables = set(
        re.findall(
            r"create\s+table(?:\s+if\s+not\s+exists)?\s+graviton\.([a-z_]+)",
            lower,
        )
    )
    if tables != EXPECTED_TABLES:
        fail(
            "owned table set drifted; "
            f"missing={sorted(EXPECTED_TABLES - tables)}, "
            f"unexpected={sorted(tables - EXPECTED_TABLES)}"
        )

    stale_path = "/".join(
        ("modules", "backend", "graviton-pg", "src", "main", "resources", "ddl.sql")
    )
    for root in (ROOT / ".github", ROOT / "deploy", ROOT / "docs", ROOT / "scripts"):
        for path in root.rglob("*"):
            if (
                path.is_file()
                and not any(part in EXCLUDED_PARTS for part in path.parts)
                and stale_path in path.read_text(encoding="utf-8", errors="ignore")
            ):
                fail(f"{path.relative_to(ROOT)} still references the removed aggregate DDL")

    print(
        f"validated {len(migrations)} Graviton migration(s), "
        f"{len(tables)} owned tables, extensions={sorted(extensions)}"
    )


if __name__ == "__main__":
    main()
