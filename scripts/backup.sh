#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <output-directory>" >&2
  exit 2
fi

DATA_DIR="${GRAVITON_DATA_DIR:-${GRAVITON_FS_ROOT:-.graviton}}"
OUTPUT_DIR="$1"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "${OUTPUT_DIR}"

if [[ ! -d "${DATA_DIR}" ]]; then
  echo "Graviton data directory does not exist: ${DATA_DIR}" >&2
  exit 1
fi
if find "${DATA_DIR}" -type l -print -quit | grep -q .; then
  echo "backup rejected: data directory contains symbolic links" >&2
  exit 1
fi
if find "${DATA_DIR}" -name '*.tmp' -print -quit | grep -q .; then
  echo "backup rejected: data directory contains incomplete temporary files" >&2
  exit 1
fi

ARCHIVE="${OUTPUT_DIR}/graviton-fs-${STAMP}.tar.gz"
tar -C "${DATA_DIR}" --no-same-owner --no-same-permissions -czf "${ARCHIVE}" .

if command -v sha256sum >/dev/null; then
  sha256sum "${ARCHIVE}" > "${ARCHIVE}.sha256"
else
  shasum -a 256 "${ARCHIVE}" > "${ARCHIVE}.sha256"
fi

if [[ -n "${GRAVITON_DATABASE_URL:-}" ]]; then
  command -v pg_dump >/dev/null || { echo "pg_dump is required for Postgres backup" >&2; exit 2; }
  PGDATABASE="${GRAVITON_DATABASE_URL}" pg_dump --format=custom --no-owner --no-privileges \
    --file="${OUTPUT_DIR}/graviton-pg-${STAMP}.dump"
fi

echo "Created ${ARCHIVE} and checksum"
