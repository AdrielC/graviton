#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONSUMER_DIR="${REPO_ROOT}/tests/consumer"
PROOF_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/graviton-consumer-proof.XXXXXX")"
LOCAL_REPO="${PROOF_ROOT}/repository"
CONSUMER_CACHE="${PROOF_ROOT}/coursier-cache"
CONSUMER_IVY="${PROOF_ROOT}/ivy"
mkdir -p "${LOCAL_REPO}" "${CONSUMER_CACHE}" "${CONSUMER_IVY}"
cleanup() { find "${PROOF_ROOT}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

cd "${REPO_ROOT}"
GRAVITON_VERSION="$(./sbt -batch -error 'print version' | tail -n 1 | tr -d '[:space:]')"

PUBLISH_SETTING="set ThisBuild / publishTo := Some(Resolver.file(\"graviton-consumer-proof\", file(\"${LOCAL_REPO}\"))(Resolver.mavenStylePatterns))"
./sbt -batch \
  "${PUBLISH_SETTING}" \
  'core/publish' \
  'streams/publish' \
  'sharedProtocolJVM/publish' \
  'runtime/publish'

cd "${CONSUMER_DIR}"
COURSIER_CACHE="${CONSUMER_CACHE}" ../../sbt -batch \
  -Dsbt.ivy.home="${CONSUMER_IVY}" \
  -Dgraviton.version="${GRAVITON_VERSION}" \
  -Dgraviton.repository="${LOCAL_REPO}" \
  clean run
