#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

failures=""

record_unexpected() {
  local label="$1"
  local pattern="$2"
  local allow_pattern="${3:-^$}"
  local search_root="${4:-modules}"
  local matches
  local unexpected

  matches="$(rg -n "${pattern}" "${search_root}" --glob '*.scala' --glob '!**/src/test/**' --glob '!**/target/**' || true)"
  unexpected="$(printf '%s\n' "${matches}" | rg -v "${allow_pattern}" || true)"
  if [[ -n "${unexpected}" ]]; then
    failures+="${label}"$'\n'"${unexpected}"$'\n'
  fi
}

record_unexpected \
  "HTTP bodies must not use materializing decoders" \
  '\.body\.(asArray|asChunk|asString|materialize|asMultipartForm)([^A-Za-z]|$)'

record_unexpected \
  "Filesystem and buffer APIs must not hide unbounded byte allocation" \
  'Files\.readAllBytes|ByteArrayOutputStream'

record_unexpected \
  "Every production runCollect requires an explicit non-byte or bounded helper classification" \
  '\.runCollect' \
  'graviton-streams/.*/BoundedByteStream\.scala|graviton-content-lab/.*/BoundedBrowserBytes\.scala|graviton-pdf-lab/.*/BoundedPdfOutput\.scala|GarbageCollector\.scala|S3BlobStore\.scala|CasBlobStore\.scala'

record_unexpected \
  "HTTP request payloads must be constructed from streams" \
  'Body\.from(Array|Chunk)'

record_unexpected \
  "Shardcake raw arrays are confined to the upstream Serialization ABI adapter" \
  'Array\[Byte\]|new Array\[Byte\]' \
  'modules/integration/graviton-shardcake/src/main/scala/graviton/integration/shardcake/ZioBlocksShardcakeSerialization\.scala' \
  'modules/integration/graviton-shardcake'

if [[ -n "${failures}" ]]; then
  printf 'Byte-streaming hygiene check failed:\n%s' "${failures}" >&2
  exit 1
fi

printf 'Byte-streaming hygiene check passed.\n'
