#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROOF_DIR="$(mktemp -d "${TMPDIR:-/tmp}/graviton-backup-proof.XXXXXX")"
cleanup() { find "${PROOF_DIR}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

DATA_DIR="${PROOF_DIR}/data"
BACKUP_DIR="${PROOF_DIR}/backups"
PAYLOAD="${PROOF_DIR}/payload.bin"
mkdir -p "${DATA_DIR}" "${BACKUP_DIR}"
printf '%s' 'graviton-backup-restore-byte-proof' > "${PAYLOAD}"

GRAVITON_DATA_DIR="${DATA_DIR}" "${REPO_ROOT}/sbt" --error "cli/run ingest ${PAYLOAD}" >/dev/null
GRAVITON_FS_ROOT="${DATA_DIR}" "${REPO_ROOT}/scripts/backup.sh" "${BACKUP_DIR}" >/dev/null

ARCHIVE="$(find "${BACKUP_DIR}" -type f -name 'graviton-fs-*.tar.gz')"
if [[ -z "${ARCHIVE}" ]]; then
  echo "backup archive was not created" >&2
  exit 1
fi

"${REPO_ROOT}/scripts/restore-drill.sh" "${ARCHIVE}"
echo "backup and restore proof passed"
