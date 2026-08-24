#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_BASE="${TMPDIR:-/tmp}"
VERIFY_ROOT="${GRAVITON_VERIFY_DIR:-$(mktemp -d "${TMP_BASE%/}/graviton-verify.XXXXXX")}"
DATA_DIR="${VERIFY_ROOT}/store"
INPUT_FILE="${VERIFY_ROOT}/source.txt"
OUTPUT_FILE="${VERIFY_ROOT}/retrieved.txt"

mkdir -p "${DATA_DIR}"
printf '%s\n' \
  'Graviton stores bytes by what they are, not where they came from.' \
  'This file will be chunked, hashed, persisted, reloaded, and verified.' \
  > "${INPUT_FILE}"

INGEST_OUTPUT="$({
  GRAVITON_DATA_DIR="${DATA_DIR}" \
    "${REPO_ROOT}/sbt" --error "cli/run ingest \"${INPUT_FILE}\""
} 2>&1)"
printf '%s\n' "${INGEST_OUTPUT}"

BLOB_ID="$(printf '%s\n' "${INGEST_OUTPUT}" | awk -F 'Blob ID:[[:space:]]*' 'NF > 1 { print $2; exit }')"
if [[ -z "${BLOB_ID}" ]]; then
  printf 'Could not read a Blob ID from the ingest output.\n' >&2
  exit 1
fi

GRAVITON_DATA_DIR="${DATA_DIR}" "${REPO_ROOT}/sbt" --error "cli/run stat ${BLOB_ID}"
GRAVITON_DATA_DIR="${DATA_DIR}" "${REPO_ROOT}/sbt" --error "cli/run get ${BLOB_ID} \"${OUTPUT_FILE}\""
GRAVITON_DATA_DIR="${DATA_DIR}" "${REPO_ROOT}/sbt" --error "cli/run verify ${BLOB_ID}"

cmp "${INPUT_FILE}" "${OUTPUT_FILE}"

printf '\nRound-trip verified byte-for-byte.\n'
printf 'Blob ID: %s\n' "${BLOB_ID}"
printf 'Verification data: %s\n' "${VERIFY_ROOT}"
