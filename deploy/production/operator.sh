#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="$deploy_dir/docker-compose.yml"
env_file="${GRAVITON_ENV_FILE:-$deploy_dir/.env}"
project="${GRAVITON_PROJECT:-graviton-production}"

need() {
  command -v "$1" >/dev/null || { echo "$1 is required" >&2; exit 2; }
}

compose_for() {
  local selected_project="$1"
  shift
  docker compose --env-file "$env_file" -p "$selected_project" -f "$compose_file" "$@"
}

compose() {
  compose_for "$project" "$@"
}

require_env_file() {
  [[ -f "$env_file" ]] || { echo "missing $env_file; run $0 init first" >&2; exit 1; }
  local mode
  if [[ "$(uname -s)" == "Darwin" ]]; then
    mode="$(stat -f '%Lp' "$env_file")"
  else
    mode="$(stat -c '%a' "$env_file")"
  fi
  [[ "$mode" == "600" ]] || { echo "$env_file must have mode 0600, found $mode" >&2; exit 1; }
}

random_hex() {
  openssl rand -hex "$1"
}

write_checksums() {
  if command -v sha256sum >/dev/null; then
    find . -type f ! -name SHA256SUMS -exec sha256sum {} \;
  elif command -v shasum >/dev/null; then
    find . -type f ! -name SHA256SUMS -exec shasum -a 256 {} \;
  else
    echo "sha256sum or shasum is required" >&2
    return 2
  fi
}

verify_checksums() {
  if command -v sha256sum >/dev/null; then
    sha256sum -c SHA256SUMS
  elif command -v shasum >/dev/null; then
    shasum -a 256 -c SHA256SUMS
  else
    echo "sha256sum or shasum is required" >&2
    return 2
  fi
}

resolve_image() {
  local requested="${GRAVITON_RELEASE_IMAGE:-ghcr.io/adrielc/graviton:0.6.1}"
  docker pull "$requested" >/dev/null
  if [[ "$requested" == *@sha256:* ]]; then
    printf '%s\n' "$requested"
    return
  fi
  local digest
  digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "$requested")"
  [[ "$digest" == *@sha256:* ]] || { echo "registry did not return an immutable digest for $requested" >&2; exit 1; }
  printf '%s\n' "$digest"
}

init() {
  need docker
  need openssl
  [[ ! -e "$env_file" ]] || { echo "refusing to overwrite $env_file" >&2; exit 1; }
  : "${GRAVITON_OIDC_ISSUER:?Set GRAVITON_OIDC_ISSUER}"
  : "${GRAVITON_OIDC_AUDIENCE:?Set GRAVITON_OIDC_AUDIENCE}"
  : "${GRAVITON_OIDC_JWKS_URI:?Set GRAVITON_OIDC_JWKS_URI}"
  local image postgres_password s3_secret shardcake_token
  image="$(resolve_image)"
  postgres_password="$(random_hex 32)"
  s3_secret="$(random_hex 32)"
  shardcake_token="$(random_hex 32)"
  umask 077
  {
    printf 'GRAVITON_IMAGE=%s\n' "$image"
    printf 'GRAVITON_RELEASE_VERSION=0.6.1\n'
    printf 'GRAVITON_MAINTENANCE_NAMESPACE=graviton-production\n'
    printf 'GRAVITON_POSTGRES_PASSWORD=%s\n' "$postgres_password"
    printf 'GRAVITON_S3_ACCESS_KEY=graviton\n'
    printf 'GRAVITON_S3_SECRET_KEY=%s\n' "$s3_secret"
    printf 'GRAVITON_SHARDCAKE_INTERNAL_TOKEN=%s\n' "$shardcake_token"
    printf 'GRAVITON_SECURITY_OIDC_ISSUER=%s\n' "$GRAVITON_OIDC_ISSUER"
    printf 'GRAVITON_SECURITY_OIDC_AUDIENCE=%s\n' "$GRAVITON_OIDC_AUDIENCE"
    printf 'GRAVITON_SECURITY_OIDC_JWKS_URI=%s\n' "$GRAVITON_OIDC_JWKS_URI"
    printf 'GRAVITON_SECURITY_CORS_ALLOWED_ORIGINS=%s\n' "${GRAVITON_CORS_ALLOWED_ORIGINS:-}"
    printf 'GRAVITON_SECURITY_AUTHORIZATION_BACKEND=token\n'
    printf 'GRAVITON_BIND_ADDRESS=127.0.0.1\n'
    printf 'GRAVITON_NODE1_HTTP_PORT=8081\n'
    printf 'GRAVITON_NODE2_HTTP_PORT=8082\n'
    printf 'GRAVITON_NODE1_GRPC_PORT=9090\n'
    printf 'GRAVITON_NODE2_GRPC_PORT=9091\n'
    printf 'GRAVITON_BACKUP_ROOT=./backups\n'
  } > "$env_file"
  chmod 600 "$env_file"
  echo "created $env_file with immutable image $image"
}

validate() {
  require_env_file
  compose config --quiet
  compose run --rm --no-deps config-check
}

up() {
  validate
  compose up -d --wait
  compose ps
  echo "Graviton is ready on the loopback ports recorded in $env_file"
}

status() {
  require_env_file
  compose ps
}

logs() {
  require_env_file
  compose logs --tail=200 -f shardcake-manager graviton-node-1 graviton-node-2
}

backup() {
  require_env_file
  local stamp backup_root snapshot_dir quiesced
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_root="$deploy_dir/backups"
  snapshot_dir="$backup_root/$stamp"
  mkdir -p "$snapshot_dir/blocks"
  quiesced=0
  restart_topology() {
    compose start shardcake-manager graviton-node-1 graviton-node-2 >/dev/null
    compose up -d --wait shardcake-manager graviton-node-1 graviton-node-2 >/dev/null
  }
  resume_after_failure() {
    if [[ "$quiesced" == "1" ]]; then
      restart_topology >/dev/null 2>&1 || true
    fi
  }
  trap resume_after_failure EXIT INT TERM

  compose stop graviton-node-1 graviton-node-2 shardcake-manager >/dev/null
  quiesced=1
  compose exec -T postgres pg_dump -U graviton -d graviton -Fc > "$snapshot_dir/postgres.dump"
  compose --profile operator run --rm --no-deps minio-client \
    'mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc mirror --overwrite local/graviton-blocks /backup/'"$stamp"'/blocks'
  (
    cd "$snapshot_dir"
    write_checksums > SHA256SUMS
  )
  restart_topology
  quiesced=0
  trap - EXIT INT TERM
  echo "created coordinated snapshot $snapshot_dir"
}

restore() {
  require_env_file
  local snapshot_dir
  snapshot_dir="$(cd "$1" && pwd)"
  local target_project="$2"
  [[ -d "$snapshot_dir/blocks" && -f "$snapshot_dir/postgres.dump" && -f "$snapshot_dir/SHA256SUMS" ]] || {
    echo "restore snapshot is incomplete: $snapshot_dir" >&2
    exit 1
  }
  [[ "$target_project" =~ ^[a-z0-9][a-z0-9_-]{2,62}$ ]] || { echo "invalid restore project name" >&2; exit 2; }
  [[ "$target_project" != "$project" ]] || { echo "restore must target an isolated Compose project" >&2; exit 1; }
  if docker volume inspect "${target_project}_postgres-data" >/dev/null 2>&1 || \
     docker volume inspect "${target_project}_minio-data" >/dev/null 2>&1; then
    echo "restore target volumes already exist; choose a new project name" >&2
    exit 1
  fi
  (cd "$snapshot_dir" && verify_checksums)

  export GRAVITON_BACKUP_ROOT="$snapshot_dir/.."
  export GRAVITON_NODE1_HTTP_PORT="${GRAVITON_RESTORE_NODE1_HTTP_PORT:-18081}"
  export GRAVITON_NODE2_HTTP_PORT="${GRAVITON_RESTORE_NODE2_HTTP_PORT:-18082}"
  export GRAVITON_NODE1_GRPC_PORT="${GRAVITON_RESTORE_NODE1_GRPC_PORT:-19090}"
  export GRAVITON_NODE2_GRPC_PORT="${GRAVITON_RESTORE_NODE2_GRPC_PORT:-19091}"
  compose_for "$target_project" up -d --wait postgres minio
  compose_for "$target_project" run --rm minio-init
  compose_for "$target_project" exec -T postgres \
    pg_restore --clean --if-exists --no-owner --no-privileges -U graviton -d graviton < "$snapshot_dir/postgres.dump"
  local stamp
  stamp="$(basename "$snapshot_dir")"
  compose_for "$target_project" --profile operator run --rm --no-deps minio-client \
    'mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc mirror --overwrite /backup/'"$stamp"'/blocks local/graviton-blocks'
  compose_for "$target_project" up -d --wait
  compose_for "$target_project" ps
  echo "restored into isolated project $target_project on HTTP ports $GRAVITON_NODE1_HTTP_PORT and $GRAVITON_NODE2_HTTP_PORT"
}

case "${1:-}" in
  init) init ;;
  validate) validate ;;
  up) up ;;
  status) status ;;
  logs) logs ;;
  backup) backup ;;
  restore)
    [[ $# -eq 3 ]] || { echo "usage: $0 restore <snapshot-directory> <new-project-name>" >&2; exit 2; }
    restore "$2" "$3"
    ;;
  stop)
    require_env_file
    compose stop
    ;;
  *)
    echo "usage: $0 {init|validate|up|status|logs|backup|restore <snapshot> <new-project>|stop}" >&2
    exit 2
    ;;
esac
