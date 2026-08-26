#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_PATH="${1:-}"
BASE_PORT="${GRAVITON_SMOKE_PORT:-18081}"
SECURE_PORT="$((BASE_PORT + 1))"
OPEN_GRPC_PORT="$((BASE_PORT + 1000))"
SECURE_GRPC_PORT="$((BASE_PORT + 1001))"
SMOKE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/graviton-packaged-smoke.XXXXXX")"
OPEN_PID=""
SECURE_PID=""

cleanup() {
  if [[ -n "${OPEN_PID}" ]]; then
    kill "${OPEN_PID}" 2>/dev/null || true
    wait "${OPEN_PID}" 2>/dev/null || true
  fi
  if [[ -n "${SECURE_PID}" ]]; then
    kill "${SECURE_PID}" 2>/dev/null || true
    wait "${SECURE_PID}" 2>/dev/null || true
  fi
  find "${SMOKE_DIR}" -depth -delete
}
trap cleanup EXIT INT TERM

if [[ -z "${JAR_PATH}" ]]; then
  JAR_COUNT="$(find "${ROOT_DIR}/modules/server/graviton-server/target" -type f -name 'graviton-server-*.jar' | wc -l | tr -d ' ')"
  if [[ "${JAR_COUNT}" -ne 1 ]]; then
    echo "expected exactly one packaged server JAR, found ${JAR_COUNT}" >&2
    exit 1
  fi
  JAR_PATH="$(find "${ROOT_DIR}/modules/server/graviton-server/target" -type f -name 'graviton-server-*.jar')"
fi
EXPECTED_VERSION="$(basename "${JAR_PATH}")"
EXPECTED_VERSION="${EXPECTED_VERSION#graviton-server-}"
EXPECTED_VERSION="${EXPECTED_VERSION%.jar}"

for command in curl jq cmp java; do
  command -v "${command}" >/dev/null || {
    echo "required command is missing: ${command}" >&2
    exit 1
  }
done

wait_ready() {
  local port="$1"
  local attempts=0
  until curl --fail --silent "http://127.0.0.1:${port}/api/health/ready" >/dev/null 2>&1; do
    attempts="$((attempts + 1))"
    if [[ "${attempts}" -ge 60 ]]; then
      echo "server on port ${port} did not become ready" >&2
      return 1
    fi
    sleep 0.25
  done
}

run_open_smoke() {
  mkdir -p "${SMOKE_DIR}/open"
  printf '%s' 'packaged-open-server-proof' > "${SMOKE_DIR}/open/input.bin"
  printf '%s' '%PDF-1.7
1 0 obj
<</Type /Catalog>>
endobj
trailer
<</Root 1 0 R>>
startxref
0
%%EOF
' > "${SMOKE_DIR}/open/input.pdf"

  env \
    GRAVITON_HTTP_PORT="${BASE_PORT}" \
    GRAVITON_GRPC_PORT="${OPEN_GRPC_PORT}" \
    GRAVITON_BLOB_BACKEND=fs \
    GRAVITON_FS_ROOT="${SMOKE_DIR}/open/data" \
    GRAVITON_SECURITY_ENABLED=false \
    java -jar "${JAR_PATH}" >"${SMOKE_DIR}/open/server.log" 2>&1 &
  OPEN_PID="$!"
  wait_ready "${BASE_PORT}"

  local health upload blob_id etag conditional_status range_body pdf_upload pdf_blob_id invalid_pdf_status
  health="$(curl --fail --silent --show-error "http://127.0.0.1:${BASE_PORT}/api/health/ready")"
  jq -e --arg expected "${EXPECTED_VERSION}" '.status == "ready" and .version == $expected' <<<"${health}" >/dev/null

  upload="$(curl --fail --silent --show-error -X POST --data-binary @"${SMOKE_DIR}/open/input.bin" "http://127.0.0.1:${BASE_PORT}/api/v1/blobs")"
  blob_id="$(jq -er '.blob.id' <<<"${upload}")"
  curl --fail --silent --show-error "http://127.0.0.1:${BASE_PORT}/api/v1/blobs/${blob_id}" > "${SMOKE_DIR}/open/output.bin"
  cmp "${SMOKE_DIR}/open/input.bin" "${SMOKE_DIR}/open/output.bin"

  range_body="$(curl --fail --silent --show-error -H 'Range: bytes=9-19' "http://127.0.0.1:${BASE_PORT}/api/v1/blobs/${blob_id}")"
  [[ "${range_body}" == "open-server" ]]

  etag="$(curl --fail --silent --show-error -I "http://127.0.0.1:${BASE_PORT}/api/v1/blobs/${blob_id}" | awk 'tolower($1) == "etag:" {gsub("\\r", "", $2); print $2}')"
  [[ -n "${etag}" ]]
  conditional_status="$(curl --silent --output /dev/null --write-out '%{http_code}' -H "If-None-Match: ${etag}" "http://127.0.0.1:${BASE_PORT}/api/v1/blobs/${blob_id}")"
  [[ "${conditional_status}" == "304" ]]

  curl --fail --silent --show-error -X POST "http://127.0.0.1:${BASE_PORT}/api/v1/blobs/${blob_id}/verify" | jq -e '.verified == true' >/dev/null

  pdf_upload="$(curl --fail --silent --show-error -X POST -H 'Content-Type: application/pdf' --data-binary @"${SMOKE_DIR}/open/input.pdf" "http://127.0.0.1:${BASE_PORT}/api/v1/blobs")"
  pdf_blob_id="$(jq -er '.blob.id' <<<"${pdf_upload}")"
  curl --fail --silent --show-error "http://127.0.0.1:${BASE_PORT}/api/v1/blobs/${pdf_blob_id}" > "${SMOKE_DIR}/open/output.pdf"
  cmp "${SMOKE_DIR}/open/input.pdf" "${SMOKE_DIR}/open/output.pdf"
  invalid_pdf_status="$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST -H 'Content-Type: application/pdf' --data-binary @"${SMOKE_DIR}/open/input.bin" "http://127.0.0.1:${BASE_PORT}/api/v1/blobs")"
  [[ "${invalid_pdf_status}" == "400" ]]

  env \
    GRAVITON_GRPC_HOST=127.0.0.1 \
    GRAVITON_GRPC_PORT="${OPEN_GRPC_PORT}" \
    java -cp "${JAR_PATH}" graviton.server.GrpcSmokeProbe

  kill "${OPEN_PID}"
  wait "${OPEN_PID}" || true
  OPEN_PID=""
  echo "open packaged-server smoke passed: blob=${blob_id} pdf=${pdf_blob_id}"
}

run_secure_smoke() {
  mkdir -p "${SMOKE_DIR}/secure"
  printf '%s' 'packaged-secure-server-proof' > "${SMOKE_DIR}/secure/input.bin"

  env \
    GRAVITON_HTTP_PORT="${SECURE_PORT}" \
    GRAVITON_GRPC_PORT="${SECURE_GRPC_PORT}" \
    GRAVITON_BLOB_BACKEND=fs \
    GRAVITON_FS_ROOT="${SMOKE_DIR}/secure/data" \
    GRAVITON_SECURITY_ENABLED=true \
    GRAVITON_SECURITY_OIDC_ISSUER=https://issuer.smoke.invalid \
    GRAVITON_SECURITY_OIDC_AUDIENCE=graviton-smoke \
    GRAVITON_SECURITY_DEV_SHARED_SECRET=packaged-smoke-secret-with-32-bytes \
    GRAVITON_SECURITY_REQUIRE_TLS=false \
    GRAVITON_SECURITY_CORS_ALLOWED_ORIGINS=https://console.smoke.invalid \
    java -jar "${JAR_PATH}" >"${SMOKE_DIR}/secure/server.log" 2>&1 &
  SECURE_PID="$!"
  wait_ready "${SECURE_PORT}"

  local unauthorized unauthorized_headers preflight_status preflight_headers token read_only_token forbidden upload blob_id
  unauthorized_headers="${SMOKE_DIR}/secure/unauthorized.headers"
  unauthorized="$(curl --silent --output /dev/null --dump-header "${unauthorized_headers}" --write-out '%{http_code}' \
    -H 'Origin: https://console.smoke.invalid' \
    "http://127.0.0.1:${SECURE_PORT}/api/v1/blobs")"
  [[ "${unauthorized}" == "401" ]]
  grep -qi '^access-control-allow-origin: https://console.smoke.invalid' "${unauthorized_headers}"

  preflight_headers="${SMOKE_DIR}/secure/preflight.headers"
  preflight_status="$(curl --silent --output /dev/null --dump-header "${preflight_headers}" --write-out '%{http_code}' \
    -X OPTIONS \
    -H 'Origin: https://console.smoke.invalid' \
    -H 'Access-Control-Request-Method: POST' \
    -H 'Access-Control-Request-Headers: authorization, content-type' \
    "http://127.0.0.1:${SECURE_PORT}/api/v1/blobs")"
  [[ "${preflight_status}" == "204" ]]
  grep -qi '^access-control-allow-origin: https://console.smoke.invalid' "${preflight_headers}"
  grep -qi '^access-control-allow-headers: .*authorization' "${preflight_headers}"

  token="$(curl --fail --silent --show-error -X POST -H 'Content-Type: application/json' -d '{}' "http://127.0.0.1:${SECURE_PORT}/dev/token" | jq -er '.access_token')"
  upload="$(curl --fail --silent --show-error -X POST -H "Authorization: Bearer ${token}" --data-binary @"${SMOKE_DIR}/secure/input.bin" "http://127.0.0.1:${SECURE_PORT}/api/v1/blobs")"
  blob_id="$(jq -er '.blob.id' <<<"${upload}")"
  curl --fail --silent --show-error -H "Authorization: Bearer ${token}" "http://127.0.0.1:${SECURE_PORT}/api/v1/blobs/${blob_id}" > "${SMOKE_DIR}/secure/output.bin"
  cmp "${SMOKE_DIR}/secure/input.bin" "${SMOKE_DIR}/secure/output.bin"
  curl --fail --silent --show-error -X POST -H "Authorization: Bearer ${token}" "http://127.0.0.1:${SECURE_PORT}/api/v1/blobs/${blob_id}/verify" | jq -e '.verified == true' >/dev/null

  read_only_token="$(curl --fail --silent --show-error -X POST -H 'Content-Type: application/json' -d '{"caps":1}' "http://127.0.0.1:${SECURE_PORT}/dev/token" | jq -er '.access_token')"
  forbidden="$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST -H "Authorization: Bearer ${read_only_token}" --data-binary @"${SMOKE_DIR}/secure/input.bin" "http://127.0.0.1:${SECURE_PORT}/api/v1/blobs")"
  [[ "${forbidden}" == "403" ]]

  env \
    GRAVITON_GRPC_HOST=127.0.0.1 \
    GRAVITON_GRPC_PORT="${SECURE_GRPC_PORT}" \
    GRAVITON_GRPC_BEARER_TOKEN="${token}" \
    java -cp "${JAR_PATH}" graviton.server.GrpcSmokeProbe

  kill "${SECURE_PID}"
  wait "${SECURE_PID}" || true
  SECURE_PID=""
  echo "secure packaged-server smoke passed: ${blob_id}"
}

run_open_smoke
run_secure_smoke
