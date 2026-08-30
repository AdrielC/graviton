#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

projects=(
  core
  streams
  sharedProtocolJVM
  sharedProtocolJS
  runtime
  backendLaws
  pdf
  shardcakeIntegration
  proto
  security
  grpc
  http
  s3
  pg
  rocks
)

commands=()
for project in "${projects[@]}"; do
  commands+=("${project}/packageBin")
done
./sbt -batch "${commands[@]}"

directories=(
  "modules/graviton-core"
  "modules/graviton-streams"
  "modules/protocol/graviton-shared/jvm"
  "modules/protocol/graviton-shared/js"
  "modules/graviton-runtime"
  "modules/graviton-backend-laws"
  "modules/graviton-pdf"
  "modules/integration/graviton-shardcake"
  "modules/protocol/graviton-proto"
  "modules/security/graviton-security"
  "modules/protocol/graviton-grpc"
  "modules/protocol/graviton-http"
  "modules/backend/graviton-s3"
  "modules/backend/graviton-pg"
  "modules/backend/graviton-rocks"
)

failed=0
for index in "${!projects[@]}"; do
  project="${projects[${index}]}"
  directory="${directories[${index}]}"
  jar_path="$(find "${directory}/target" -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | tail -n 1)"
  if [[ -z "${jar_path}" ]]; then
    echo "${project}: package JAR missing" >&2
    failed=1
    continue
  fi

  payload_count="$(jar tf "${jar_path}" | awk '/\.(class|sjsir)$/ { count += 1 } END { print count + 0 }')"
  if [[ "${payload_count}" -eq 0 ]]; then
    echo "${project}: package contains no JVM classes or Scala.js IR (${jar_path})" >&2
    failed=1
    continue
  fi

  suspicious="$(unzip -p "${jar_path}" | strings | grep -E -i 'UnsupportedOperationException|not implemented|scaffold only|placeholder backend' || true)"
  if [[ -n "${suspicious}" ]]; then
    echo "${project}: package contains unsupported or placeholder markers" >&2
    printf '%s\n' "${suspicious}" >&2
    failed=1
  else
    echo "${project}: ${payload_count} executable definitions, no unsupported-operation markers"
  fi
done

if [[ "${failed}" -ne 0 ]]; then
  exit 1
fi

echo "Published artifact audit passed."
