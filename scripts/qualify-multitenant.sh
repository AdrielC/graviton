#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
EVIDENCE_DIR="${1:-${TMPDIR:-/tmp}/graviton-multitenant-qualification}"
LOG_FILE="${EVIDENCE_DIR}/contract-tests.log"

mkdir -p "${EVIDENCE_DIR}"
cd "${REPO_ROOT}"

started_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
GRAVITON_IT=1 ./sbt -batch -no-colors \
  'backendLaws/testOnly graviton.backend.laws.TenantStorageLawsSpec' \
  'runtime/testOnly graviton.runtime.constraints.ThrottleSpec graviton.runtime.upload.ResumableUploadServiceSpec graviton.runtime.tenant.TenantPolicySpec graviton.runtime.tenant.TenantStorageSpec' \
  'pg/testOnly graviton.backend.pg.PgDataSourceSpec' \
  'security/testOnly graviton.security.AuditSinkSpec graviton.security.RateLimiterSpec graviton.security.SecurityConfigSpec' \
  'shardcakeIntegration/testOnly graviton.integration.shardcake.ShardcakeIntegrationSpec' \
  'http/testOnly graviton.protocol.http.TenantHttpApiSpec graviton.protocol.http.HttpApiSpec graviton.protocol.http.HttpSecurityPolicySpec' \
  'grpc/testOnly graviton.protocol.grpc.GravitonGrpcIntegrationSpec' \
  'server/testOnly graviton.server.ConfigurationValidationSpec graviton.server.DefaultStorageSpec graviton.server.EmbeddedPgFsCasRoundTripSpec' \
  2>&1 | tee "${LOG_FILE}" >&2
completed_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"

printf '{"schema":"graviton-multitenant-qualification-v1","commit":"%s","status":"passed","startedAt":"%s","completedAt":"%s","log":"%s"}\n' \
  "$(git rev-parse HEAD)" \
  "${started_at}" \
  "${completed_at}" \
  "$(basename "${LOG_FILE}")"
