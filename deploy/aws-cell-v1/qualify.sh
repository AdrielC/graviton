#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
: "${GRAVITON_AWS_BASE_URL:?GRAVITON_AWS_BASE_URL is required}"
: "${GRAVITON_BEARER_TOKEN:?GRAVITON_BEARER_TOKEN is required}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }

payload="${1:-}"
cleanup=""
if [[ -z "${payload}" ]]; then
  payload="$(mktemp)"
  cleanup="${payload}"
  dd if=/dev/urandom of="${payload}" bs=1m count=32 status=none
fi
trap '[[ -z "${cleanup}" ]] || rm -f "${cleanup}"' EXIT
[[ -f "${payload}" ]] || { echo "payload not found: ${payload}" >&2; exit 2; }

export GRAVITON_BENCHMARK_BACKEND_DESCRIPTION="aws-cell-v1-s3-rds-shardcake"
first="$("${ROOT}/scripts/benchmark-http.sh" "${GRAVITON_AWS_BASE_URL}" "${payload}")"
second="$("${ROOT}/scripts/benchmark-http.sh" "${GRAVITON_AWS_BASE_URL}" "${payload}")"

jq -e '.verified == true and .freshBlocks > 0' <<<"${first}" >/dev/null
jq -e '.verified == true and .freshBlocks == 0 and .duplicateBlocks > 0' <<<"${second}" >/dev/null
[[ "$(jq -r .blobId <<<"${first}")" == "$(jq -r .blobId <<<"${second}")" ]] || {
  echo "identical payloads returned different content IDs" >&2
  exit 1
}

blob_id="$(jq -r .blobId <<<"${first}")"
range_file="$(mktemp)"
expected_file="$(mktemp)"
trap 'rm -f "${cleanup}" "${range_file}" "${expected_file}"' EXIT
curl --fail --silent --show-error \
  -H "Authorization: Bearer ${GRAVITON_BEARER_TOKEN}" \
  -H "Range: bytes=1048576-2097151" \
  "${GRAVITON_AWS_BASE_URL%/}/api/v1/blobs/${blob_id}" >"${range_file}"
dd if="${payload}" of="${expected_file}" bs=1 skip=1048576 count=1048576 status=none
cmp "${range_file}" "${expected_file}"

jq -n --argjson first "${first}" --argjson second "${second}" '{
  schema: "graviton-aws-cell-qualification-v1",
  first: $first,
  duplicate: $second,
  exactRangeBytes: 1048576,
  passed: true
}'
