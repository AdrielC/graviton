#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CELL_ROOT="${ROOT}/deploy/aws-cell-v1"
TF_ROOT="${CELL_ROOT}/terraform"
TF_VAR_FILE="${GRAVITON_TF_VAR_FILE:-${TF_ROOT}/production.tfvars}"
BACKEND_CONFIG="${GRAVITON_TERRAFORM_BACKEND_CONFIG:-}"

fail() {
  echo "error: $*" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

need_env() {
  [[ -n "${!1:-}" ]] || fail "$1 is required"
}

init_backend() {
  need terraform
  [[ -n "${BACKEND_CONFIG}" ]] || fail "GRAVITON_TERRAFORM_BACKEND_CONFIG must name a reviewed S3 backend config"
  [[ -f "${BACKEND_CONFIG}" ]] || fail "backend config not found: ${BACKEND_CONFIG}"
  terraform -chdir="${TF_ROOT}" init -input=false -backend-config="${BACKEND_CONFIG}"
}

tf_output_raw() {
  terraform -chdir="${TF_ROOT}" output -raw "$1"
}

validate() {
  need terraform
  terraform -chdir="${TF_ROOT}" fmt -check -recursive
  terraform -chdir="${TF_ROOT}" init -backend=false -input=false
  terraform -chdir="${TF_ROOT}" validate
  bash -n "${CELL_ROOT}/operator.sh" "${CELL_ROOT}/bootstrap/bootstrap.sh" "${CELL_ROOT}/qualify.sh"
}

plan() {
  init_backend
  [[ -f "${TF_VAR_FILE}" ]] || fail "Terraform variable file not found: ${TF_VAR_FILE}"
  terraform -chdir="${TF_ROOT}" plan -input=false "-var-file=${TF_VAR_FILE}" -var="bootstrap_complete=false"
}

infra() {
  init_backend
  [[ -f "${TF_VAR_FILE}" ]] || fail "Terraform variable file not found: ${TF_VAR_FILE}"
  terraform -chdir="${TF_ROOT}" apply -input=false "-var-file=${TF_VAR_FILE}" -var="bootstrap_complete=false"
}

ensure_runtime_secret() (
  need aws
  need jq
  need openssl
  need_env GRAVITON_OIDC_ISSUER
  need_env GRAVITON_OIDC_AUDIENCE
  need_env GRAVITON_OIDC_JWKS_URI
  need_env GRAVITON_CORS_ALLOWED_ORIGINS

  local secret_arn current_count secret_dir secret_file
  secret_dir="$(mktemp -d)"
  chmod 0700 "${secret_dir}"
  secret_file="${secret_dir}/runtime.json"
  : >"${secret_file}"
  chmod 0600 "${secret_file}"
  trap 'find "${secret_dir}" -depth -delete 2>/dev/null || true' EXIT
  secret_arn="$(tf_output_raw runtime_secret_arn)"
  current_count="$(aws secretsmanager list-secret-version-ids \
    --secret-id "${secret_arn}" \
    --query "length(Versions[?contains(VersionStages, 'AWSCURRENT')])" \
    --output text)"
  if [[ "${current_count}" != "0" ]]; then
    aws secretsmanager get-secret-value \
      --secret-id "${secret_arn}" \
      --query SecretString \
      --output text >"${secret_file}"
    jq -e '
      .PG_PASSWORD and
      .SHARDCAKE_TOKEN and
      .MANIFEST_HMAC_KEY_BASE64 and
      .REDIS_PASSWORD and
      .OIDC_ISSUER and
      .OIDC_AUDIENCE and
      .OIDC_JWKS_URI and
      .CORS_ALLOWED_ORIGINS
    ' "${secret_file}" >/dev/null || fail "existing runtime secret is incomplete; use a reviewed rotation or repair runbook"
    echo "Existing runtime secret verified without replacement"
    return
  fi

  openssl rand -base64 48 | tr -d '\n' | tr '/+' '_-' >"${secret_dir}/pg"
  openssl rand -base64 48 | tr -d '\n=' | tr '/+' '_-' >"${secret_dir}/shard"
  openssl rand -base64 32 | tr -d '\n' >"${secret_dir}/manifest"
  openssl rand -hex 32 | tr -d '\n' >"${secret_dir}/redis"
  printf '%s' "${GRAVITON_OIDC_ISSUER}" >"${secret_dir}/issuer"
  printf '%s' "${GRAVITON_OIDC_AUDIENCE}" >"${secret_dir}/audience"
  printf '%s' "${GRAVITON_OIDC_JWKS_URI}" >"${secret_dir}/jwks"
  printf '%s' "${GRAVITON_CORS_ALLOWED_ORIGINS}" >"${secret_dir}/cors"
  chmod 0600 "${secret_dir}"/*

  jq -n \
    --rawfile pg "${secret_dir}/pg" \
    --rawfile shard "${secret_dir}/shard" \
    --rawfile manifest "${secret_dir}/manifest" \
    --rawfile redis "${secret_dir}/redis" \
    --rawfile issuer "${secret_dir}/issuer" \
    --rawfile audience "${secret_dir}/audience" \
    --rawfile jwks "${secret_dir}/jwks" \
    --rawfile cors "${secret_dir}/cors" \
    '{
      PG_PASSWORD: $pg,
      SHARDCAKE_TOKEN: $shard,
      MANIFEST_HMAC_KEY_BASE64: $manifest,
      REDIS_PASSWORD: $redis,
      OIDC_ISSUER: $issuer,
      OIDC_AUDIENCE: $audience,
      OIDC_JWKS_URI: $jwks,
      CORS_ALLOWED_ORIGINS: $cors
    }' >"${secret_file}"

  aws secretsmanager put-secret-value \
    --secret-id "${secret_arn}" \
    --secret-string "file://${secret_file}" \
    --query VersionId \
    --output text >/dev/null
  echo "Runtime secret initialized in Secrets Manager without entering Terraform state"
)

secure_admission_cache() (
  need aws
  need jq

  local secret_arn replication_group_id secret_file request_file auth_enabled state
  secret_arn="$(tf_output_raw runtime_secret_arn)"
  replication_group_id="$(tf_output_raw admission_replication_group_id)"
  secret_file="$(mktemp)"
  request_file="$(mktemp)"
  chmod 0600 "${secret_file}" "${request_file}"
  trap 'rm -f "${secret_file}" "${request_file}"' EXIT

  aws secretsmanager get-secret-value \
    --secret-id "${secret_arn}" \
    --query SecretString \
    --output text >"${secret_file}"
  jq -e '.REDIS_PASSWORD | type == "string" and length >= 16 and length <= 128' "${secret_file}" >/dev/null || \
    fail "runtime secret has no valid REDIS_PASSWORD"

  auth_enabled="$(aws elasticache describe-replication-groups \
    --replication-group-id "${replication_group_id}" \
    --query 'ReplicationGroups[0].AuthTokenEnabled' \
    --output text)"

  if [[ "${auth_enabled}" != "True" && "${auth_enabled}" != "true" ]]; then
    jq \
      --arg id "${replication_group_id}" \
      '{
        ReplicationGroupId: $id,
        AuthToken: .REDIS_PASSWORD,
        AuthTokenUpdateStrategy: "SET",
        ApplyImmediately: true
      }' "${secret_file}" >"${request_file}"
    aws elasticache modify-replication-group \
      --cli-input-json "file://${request_file}" \
      --query ReplicationGroup.ReplicationGroupId \
      --output text >/dev/null
  fi

  aws elasticache wait replication-group-available --replication-group-id "${replication_group_id}"
  state="$(aws elasticache describe-replication-groups \
    --replication-group-id "${replication_group_id}" \
    --output json)"
  jq -e '
    .ReplicationGroups[0] |
    .Status == "available" and
    .AuthTokenEnabled == true and
    .TransitEncryptionEnabled == true and
    .AtRestEncryptionEnabled == true and
    .AutomaticFailover == "enabled" and
    .MultiAZ == "enabled" and
    (.MemberClusters | length) >= 3 and
    (.NodeGroups[0].PrimaryEndpoint.Address | type == "string" and length > 0)
  ' <<<"${state}" >/dev/null || fail "Valkey admission coordinator did not satisfy the production topology contract"
  echo "TLS, AUTH, Multi-AZ Valkey admission coordinator verified"
)

build_bootstrap() {
  need aws
  need docker
  local repository registry tag digest
  repository="$(tf_output_raw bootstrap_repository_url)"
  registry="${repository%%/*}"
  tag="$(git -C "${ROOT}" rev-parse --short=12 HEAD)"

  aws ecr get-login-password | docker login --username AWS --password-stdin "${registry}" >/dev/null
  docker build --platform linux/amd64 -f "${CELL_ROOT}/bootstrap/Dockerfile" -t "${repository}:${tag}" "${ROOT}"
  docker push "${repository}:${tag}" >/dev/null
  digest="$(aws ecr describe-images \
    --repository-name "${repository#*/}" \
    --image-ids imageTag="${tag}" \
    --query 'imageDetails[0].imageDigest' \
    --output text)"
  [[ "${digest}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "ECR did not return an immutable bootstrap digest"
  printf '%s@%s\n' "${repository}" "${digest}"
}

prepare_bootstrap_task() {
  local image="$1"
  [[ -f "${TF_VAR_FILE}" ]] || fail "Terraform variable file not found: ${TF_VAR_FILE}"
  terraform -chdir="${TF_ROOT}" apply -input=false "-var-file=${TF_VAR_FILE}" \
    -var="bootstrap_image=${image}" \
    -var="bootstrap_complete=false"
}

run_bootstrap_task() {
  need aws
  need jq
  local cluster task_definition security_group subnets network task_arn stopped_reason exit_code
  cluster="$(tf_output_raw cluster_name)"
  task_definition="$(tf_output_raw bootstrap_task_definition_arn)"
  security_group="$(tf_output_raw task_security_group_id)"
  subnets="$(terraform -chdir="${TF_ROOT}" output -json private_subnet_ids)"
  network="$(jq -cn --argjson subnets "${subnets}" --arg sg "${security_group}" \
    '{awsvpcConfiguration:{subnets:$subnets,securityGroups:[$sg],assignPublicIp:"DISABLED"}}')"

  task_arn="$(aws ecs run-task \
    --cluster "${cluster}" \
    --task-definition "${task_definition}" \
    --launch-type FARGATE \
    --network-configuration "${network}" \
    --query 'tasks[0].taskArn' \
    --output text)"
  [[ "${task_arn}" == arn:* ]] || fail "ECS did not start the bootstrap task"
  aws ecs wait tasks-stopped --cluster "${cluster}" --tasks "${task_arn}"
  read -r exit_code stopped_reason < <(aws ecs describe-tasks \
    --cluster "${cluster}" \
    --tasks "${task_arn}" \
    --query 'tasks[0].[containers[0].exitCode,stoppedReason]' \
    --output text)
  [[ "${exit_code}" == "0" ]] || fail "bootstrap task failed: ${stopped_reason}"
  echo "Empty PostgreSQL store bootstrapped and runtime role verified"
}

deploy() {
  local image="$1" cluster service
  local services=()
  [[ -f "${TF_VAR_FILE}" ]] || fail "Terraform variable file not found: ${TF_VAR_FILE}"
  terraform -chdir="${TF_ROOT}" apply -input=false "-var-file=${TF_VAR_FILE}" \
    -var="bootstrap_image=${image}" \
    -var="bootstrap_complete=true"
  cluster="$(tf_output_raw cluster_name)"
  while IFS= read -r service; do
    services+=("${service}")
  done < <(terraform -chdir="${TF_ROOT}" output -json node_services | jq -r '.[]')
  aws ecs wait services-stable --cluster "${cluster}" --services manager "${services[@]}"
  echo "Graviton manager and ${#services[@]} stable Shardcake nodes are running"
}

up() {
  local image
  infra
  ensure_runtime_secret
  secure_admission_cache
  image="$(build_bootstrap)"
  prepare_bootstrap_task "${image}"
  run_bootstrap_task
  deploy "${image}"
  echo "Map the reviewed DNS name to $(tf_output_raw alb_dns_name), then run qualify.sh"
}

case "${1:-}" in
  validate) validate ;;
  plan) plan ;;
  infra) infra ;;
  seed) ensure_runtime_secret ;;
  secure-admission) secure_admission_cache ;;
  build-bootstrap) build_bootstrap ;;
  prepare-bootstrap)
    [[ $# -eq 2 ]] || fail "usage: $0 prepare-bootstrap <repository@sha256:digest>"
    prepare_bootstrap_task "$2"
    ;;
  migrate) run_bootstrap_task ;;
  deploy)
    [[ $# -eq 2 ]] || fail "usage: $0 deploy <repository@sha256:digest>"
    deploy "$2"
    ;;
  up) up ;;
  *)
    echo "usage: $0 {validate|plan|infra|seed|secure-admission|build-bootstrap|prepare-bootstrap|migrate|deploy|up}" >&2
    exit 2
    ;;
esac
