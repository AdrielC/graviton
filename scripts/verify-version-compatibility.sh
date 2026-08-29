#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PREVIOUS_TAG="$(git -C "${REPO_ROOT}" tag --merged HEAD --list 'v[0-9]*' --sort=-version:refname | head -n 1)"

if [[ -z "${PREVIOUS_TAG}" ]]; then
  echo 'First release: no previous tag exists for compatibility comparison.'
  exit 0
fi

if [[ ! "${PREVIOUS_TAG}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.+-][0-9A-Za-z.-]+)*$ ]]; then
  echo "Latest release tag has an unsupported version format: ${PREVIOUS_TAG}" >&2
  exit 1
fi

COMPAT_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/graviton-compatibility.XXXXXX")"
BASELINE_SOURCE="${COMPAT_ROOT}/baseline"
BASELINE_REPO="${COMPAT_ROOT}/repository"
mkdir -p "${BASELINE_REPO}"
cleanup() { find "${COMPAT_ROOT}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

echo "Building compatibility baseline from ${PREVIOUS_TAG}."
if grep -q 'versionPolicyIntention := Compatibility.None' "${REPO_ROOT}/build.sbt"; then
  echo "Compatibility intention: explicit pre-1.0 minor boundary after ${PREVIOUS_TAG}."
fi
git clone --quiet --no-local "${REPO_ROOT}" "${BASELINE_SOURCE}"
git -C "${BASELINE_SOURCE}" checkout --quiet "${PREVIOUS_TAG}"

PUBLISH_SETTING="set ThisBuild / publishTo := Some(Resolver.file(\"graviton-compatibility-baseline\", file(\"${BASELINE_REPO}\"))(Resolver.mavenStylePatterns))"
SKIP_DOCS_SETTING='set ThisBuild / Compile / packageDoc / publishArtifact := false'
SKIP_SOURCES_SETTING='set ThisBuild / Compile / packageSrc / publishArtifact := false'
(
  cd "${BASELINE_SOURCE}"
  ./sbt -batch \
    "${PUBLISH_SETTING}" \
    "${SKIP_DOCS_SETTING}" \
    "${SKIP_SOURCES_SETTING}" \
    'core/publish' \
    'streams/publish' \
    'sharedProtocolJVM/publish' \
    'sharedProtocolJS/publish' \
    'runtime/publish' \
    'pdf/publish' \
    'proto/publish' \
    'security/publish' \
    'grpc/publish' \
    'http/publish' \
    's3/publish' \
    'pg/publish' \
    'rocks/publish'
)

RESOLVER_SETTING="set ThisBuild / resolvers += \"graviton-compatibility-baseline\" at file(\"${BASELINE_REPO}\").toURI.toString"
cd "${REPO_ROOT}"
./sbt -batch "${RESOLVER_SETTING}" versionPolicyCheck
