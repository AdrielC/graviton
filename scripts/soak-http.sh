#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "usage: $0 <base-url> [iterations=100] [payload-bytes=1048576]" >&2
  exit 2
fi

BASE_URL="${1%/}"
ITERATIONS="${2:-100}"
PAYLOAD_BYTES="${3:-1048576}"
[[ "${ITERATIONS}" =~ ^[1-9][0-9]*$ ]] || { echo "iterations must be positive" >&2; exit 2; }
[[ "${PAYLOAD_BYTES}" =~ ^[1-9][0-9]*$ ]] || { echo "payload-bytes must be positive" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
AUTH_ARGS=()
if [[ -n "${GRAVITON_BEARER_TOKEN:-}" ]]; then
  AUTH_ARGS=(-H "Authorization: Bearer ${GRAVITON_BEARER_TOKEN}")
fi

PAYLOAD="$(mktemp "${TMPDIR:-/tmp}/graviton-soak.XXXXXX")"
cleanup() { find "${PAYLOAD}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
dd if=/dev/zero of="${PAYLOAD}" bs="${PAYLOAD_BYTES}" count=1 2>/dev/null

START="$(date +%s)"
FAILURES=0
for ((iteration = 1; iteration <= ITERATIONS; iteration++)); do
  if ! UPLOAD="$(curl --fail --silent --show-error "${AUTH_ARGS[@]}" -X POST --data-binary "@${PAYLOAD}" "${BASE_URL}/api/v1/blobs")"; then
    FAILURES=$((FAILURES + 1))
    continue
  fi
  BLOB_ID="$(jq -r '.blob.id' <<<"${UPLOAD}")"
  if ! curl --fail --silent --show-error "${AUTH_ARGS[@]}" "${BASE_URL}/api/v1/blobs/${BLOB_ID}" | cmp --silent - "${PAYLOAD}"; then
    FAILURES=$((FAILURES + 1))
  fi
done
END="$(date +%s)"

jq -n \
  --arg schema "graviton-soak-v1" \
  --argjson iterations "${ITERATIONS}" \
  --argjson payloadBytes "${PAYLOAD_BYTES}" \
  --argjson failures "${FAILURES}" \
  --argjson durationSeconds "$((END - START))" \
  '{schema: $schema, iterations: $iterations, payloadBytes: $payloadBytes, failures: $failures, durationSeconds: $durationSeconds}'

[[ "${FAILURES}" -eq 0 ]]
