#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <graviton-fs-*.tar.gz>" >&2
  exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCHIVE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
CHECKSUM_FILE="${ARCHIVE}.sha256"
[[ -f "${ARCHIVE}" ]] || { echo "archive not found: ${ARCHIVE}" >&2; exit 1; }
[[ -f "${CHECKSUM_FILE}" ]] || { echo "checksum not found: ${CHECKSUM_FILE}" >&2; exit 1; }

if command -v sha256sum >/dev/null; then
  (cd "$(dirname "${ARCHIVE}")" && sha256sum -c "$(basename "${CHECKSUM_FILE}")")
else
  (cd "$(dirname "${ARCHIVE}")" && shasum -a 256 -c "$(basename "${CHECKSUM_FILE}")")
fi

RESTORE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/graviton-restore.XXXXXX")"
cleanup() { find "${RESTORE_DIR}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

while IFS= read -r member; do
  normalized="${member#./}"
  case "${normalized}" in
    /*|../*|*/../*|*/..)
      echo "restore rejected: archive path escapes the restore root: ${member}" >&2
      exit 1
      ;;
  esac
done < <(tar -tzf "${ARCHIVE}")

tar -C "${RESTORE_DIR}" --no-same-owner --no-same-permissions -xzf "${ARCHIVE}"

if find "${RESTORE_DIR}" -type l -print -quit | grep -q .; then
  echo "restore rejected: archive contains symbolic links" >&2
  exit 1
fi
if find "${RESTORE_DIR}" \( -type b -o -type c -o -type p -o -type s \) -print -quit | grep -q .; then
  echo "restore rejected: archive contains a special file" >&2
  exit 1
fi
if find "${RESTORE_DIR}" -name '*.tmp' -print -quit | grep -q .; then
  echo "restore rejected: archive contains incomplete temporary files" >&2
  exit 1
fi

GRAVITON_DATA_DIR="${RESTORE_DIR}" "${REPO_ROOT}/sbt" --error "cli/run list" | while IFS=$'\t' read -r blob_id _; do
  [[ -z "${blob_id}" ]] || GRAVITON_DATA_DIR="${RESTORE_DIR}" "${REPO_ROOT}/sbt" --error "cli/run verify ${blob_id}"
done

echo "Restore drill passed in an isolated temporary directory"
