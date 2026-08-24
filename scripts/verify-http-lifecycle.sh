#!/usr/bin/env bash

set -euo pipefail

API_ROOT="${GRAVITON_API_URL:-http://localhost:8081}"
TMP_BASE="${TMPDIR:-/tmp}"
VERIFY_ROOT="$(mktemp -d "${TMP_BASE%/}/graviton-http-verify.XXXXXX")"
INPUT_FILE="${VERIFY_ROOT}/source.bin"
OUTPUT_FILE="${VERIFY_ROOT}/retrieved.bin"
BLOB_ID=""

cleanup() {
  if [[ -n "${BLOB_ID}" ]]; then
    curl -fsS -X DELETE "${API_ROOT}/api/blobs/${BLOB_ID}" >/dev/null 2>&1 || true
  fi
  rm -rf "${VERIFY_ROOT}"
}
trap cleanup EXIT

for command_name in curl jq cmp; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    printf 'Required command is not installed: %s\n' "${command_name}" >&2
    exit 1
  fi
done

printf 'graviton-http-verification\n%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${INPUT_FILE}"

curl -fsS "${API_ROOT}/api/health" | jq -e '.status == "ok" or .status == "healthy"' >/dev/null

UPLOAD_JSON="$(
  curl -fsS \
    -H 'Content-Type: application/octet-stream' \
    -X POST \
    --data-binary "@${INPUT_FILE}" \
    "${API_ROOT}/api/blobs"
)"
BLOB_ID="$(jq -er '.blob.id' <<<"${UPLOAD_JSON}")"

jq -e \
  --arg blob_id "${BLOB_ID}" \
  '.blob.id == $blob_id and .blob.size > 0 and .blob.blockCount > 0 and .freshBlocks > 0' \
  <<<"${UPLOAD_JSON}" >/dev/null

curl -fsS "${API_ROOT}/api/blobs" \
  | jq -e --arg blob_id "${BLOB_ID}" '.blobs | any(.id == $blob_id)' >/dev/null

curl -fsS "${API_ROOT}/api/blobs/${BLOB_ID}/metadata" \
  | jq -e \
      --arg blob_id "${BLOB_ID}" \
      '.summary.id == $blob_id and (.blocks | length) == .summary.blockCount' >/dev/null

curl -fsS -X POST "${API_ROOT}/api/blobs/${BLOB_ID}/verify" \
  | jq -e \
      --arg blob_id "${BLOB_ID}" \
      '.id == $blob_id and .verified == true and .bytesChecked > 0' >/dev/null

curl -fsS "${API_ROOT}/api/blobs/${BLOB_ID}" --output "${OUTPUT_FILE}"
cmp "${INPUT_FILE}" "${OUTPUT_FILE}"

curl -fsS -X DELETE "${API_ROOT}/api/blobs/${BLOB_ID}" >/dev/null

STATUS_CODE="$(
  curl -sS -o /dev/null -w '%{http_code}' \
    "${API_ROOT}/api/blobs/${BLOB_ID}/metadata"
)"
if [[ "${STATUS_CODE}" != "404" ]]; then
  printf 'Expected deleted manifest to return 404, got %s.\n' "${STATUS_CODE}" >&2
  exit 1
fi

printf 'Live HTTP lifecycle verified.\n'
printf 'Blob ID: %s\n' "${BLOB_ID}"
BLOB_ID=""
