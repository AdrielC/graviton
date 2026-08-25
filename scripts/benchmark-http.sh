#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <base-url> <payload-file>" >&2
  exit 2
fi

BASE_URL="${1%/}"
PAYLOAD="$2"
[[ -f "${PAYLOAD}" ]] || { echo "payload not found: ${PAYLOAD}" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
AUTH_ARGS=()
if [[ -n "${GRAVITON_BEARER_TOKEN:-}" ]]; then
  AUTH_ARGS=(-H "Authorization: Bearer ${GRAVITON_BEARER_TOKEN}")
fi

BYTES="$(wc -c < "${PAYLOAD}" | tr -d ' ')"
if command -v sha256sum >/dev/null; then
  PAYLOAD_SHA256="$(sha256sum "${PAYLOAD}" | awk '{print $1}')"
else
  PAYLOAD_SHA256="$(shasum -a 256 "${PAYLOAD}" | awk '{print $1}')"
fi
START_NS="$(python3 -c 'import time; print(time.time_ns())')"
UPLOAD="$(curl --fail --silent --show-error "${AUTH_ARGS[@]}" -X POST --data-binary "@${PAYLOAD}" "${BASE_URL}/api/v1/blobs")"
END_NS="$(python3 -c 'import time; print(time.time_ns())')"
BLOB_ID="$(jq -r '.blob.id' <<<"${UPLOAD}")"
DOWNLOAD_START_NS="$(python3 -c 'import time; print(time.time_ns())')"
curl --fail --silent --show-error "${AUTH_ARGS[@]}" "${BASE_URL}/api/v1/blobs/${BLOB_ID}" | cmp --silent - "${PAYLOAD}"
DOWNLOAD_END_NS="$(python3 -c 'import time; print(time.time_ns())')"
VERIFY_START_NS="$(python3 -c 'import time; print(time.time_ns())')"
VERIFY="$(curl --fail --silent --show-error "${AUTH_ARGS[@]}" -X POST "${BASE_URL}/api/v1/blobs/${BLOB_ID}/verify")"
VERIFY_END_NS="$(python3 -c 'import time; print(time.time_ns())')"
jq -e '.verified == true' <<<"${VERIFY}" >/dev/null

python3 - "${BYTES}" "${START_NS}" "${END_NS}" "${DOWNLOAD_START_NS}" "${DOWNLOAD_END_NS}" "${VERIFY_START_NS}" "${VERIFY_END_NS}" "${BLOB_ID}" "${PAYLOAD_SHA256}" <<'PY'
import json, os, platform, subprocess, sys, time
size, upload_start, upload_end, download_start, download_end, verify_start, verify_end = map(int, sys.argv[1:8])
upload_seconds = (upload_end - upload_start) / 1_000_000_000
download_seconds = (download_end - download_start) / 1_000_000_000
verify_seconds = (verify_end - verify_start) / 1_000_000_000
try:
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], text=True).strip())
except Exception:
    revision = "unknown"
    dirty = None
try:
    java = subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).splitlines()[0]
except Exception:
    java = "unknown"
print(json.dumps({
    "schema": "graviton-benchmark-v1",
    "timestampUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "revision": revision,
    "repositoryDirty": dirty,
    "platform": platform.platform(),
    "python": platform.python_version(),
    "java": java,
    "backendDescription": os.environ.get("GRAVITON_BENCHMARK_BACKEND_DESCRIPTION", "unknown"),
    "bytes": size,
    "payloadSha256": sys.argv[9],
    "uploadSeconds": upload_seconds,
    "uploadMiBPerSecond": (size / 1048576) / upload_seconds if upload_seconds else None,
    "downloadSeconds": download_seconds,
    "downloadMiBPerSecond": (size / 1048576) / download_seconds if download_seconds else None,
    "verifySeconds": verify_seconds,
    "blobId": sys.argv[8],
    "verified": True,
}, sort_keys=True))
PY
