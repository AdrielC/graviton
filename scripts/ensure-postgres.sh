#!/usr/bin/env bash
set -euo pipefail

# ensure-postgres.sh
# Ensures a local PostgreSQL is available. If it's already reachable, prints
# export lines for env vars. Otherwise, starts a container (Docker preferred,
# Podman fallback), waits until ready, then prints export lines.
#
# Usage:
#   ./scripts/ensure-postgres.sh [--engine auto|docker|podman] \
#       [--pg-version 17] [--name graviton-pg] \
#       [--host 127.0.0.1] [--port 5432] \
#       [--db postgres] [--user postgres] [--password postgres]
#
# Typical integration:
#   eval "$(./scripts/ensure-postgres.sh)"
#

ENGINE="auto"                      # auto | docker | podman
PG_VERSION="${PG_VERSION:-17}"
NAME="${PG_NAME:-graviton-pg}"
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_PORT="${PG_PORT:-5432}"
PG_DATABASE="${PG_DATABASE:-postgres}"
# Optional: comma-separated additional databases to ensure
EXTRA_DBS="${PG_DBS:-}"
PG_USERNAME="${PG_USERNAME:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
DDL_PATH="${DDL_PATH:-modules/pg/ddl.sql}"
DO_DDL=${DO_DDL:-1}
# If PG is already reachable (external), optionally apply DDL using local psql
APPLY_EXTERNAL=${APPLY_EXTERNAL:-0}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --engine) ENGINE="${2:?}"; shift 2 ;;
    --pg-version) PG_VERSION="${2:?}"; shift 2 ;;
    --name) NAME="${2:?}"; shift 2 ;;
    --host) PG_HOST="${2:?}"; shift 2 ;;
    --port) PG_PORT="${2:?}"; shift 2 ;;
    --db|--database) PG_DATABASE="${2:?}"; shift 2 ;;
    --dbs) EXTRA_DBS="${2:?}"; shift 2 ;;
    --user|--username) PG_USERNAME="${2:?}"; shift 2 ;;
    --password) PG_PASSWORD="${2:?}"; shift 2 ;;
    --ddl) DDL_PATH="${2:?}"; shift 2 ;;
    --no-ddl) DO_DDL=0; shift ;;
    --apply-external) APPLY_EXTERNAL=1; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

has_cmd() { command -v "$1" >/dev/null 2>&1; }

# TCP check using bash's /dev/tcp; avoid external deps
pg_reachable() {
  local host="$1" port="$2"
  (echo > "/dev/tcp/${host}/${port}" ) >/dev/null 2>&1 || return 1
}

wait_pg() {
  local host="$1" port="$2" tries=0
  until pg_reachable "$host" "$port"; do
    sleep 0.5
    tries=$((tries+1))
    if (( tries > 180 )); then
      echo "Timed out waiting for Postgres at ${host}:${port}" >&2
      return 1
    fi
  done
}

ENGINE_USED=""

start_with_docker() {
  # If already running, do nothing; else start one
  if docker inspect -f '{{.State.Running}}' "$NAME" >/dev/null 2>&1; then
    :
  else
    if docker inspect "$NAME" >/dev/null 2>&1; then
      docker start "$NAME" >/dev/null
    else
      docker run -d --name "$NAME" \
        -e POSTGRES_PASSWORD="$PG_PASSWORD" \
        -e POSTGRES_USER="$PG_USERNAME" \
        -e POSTGRES_DB="$PG_DATABASE" \
        -p ${PG_HOST}:${PG_PORT}:5432 \
        "postgres:${PG_VERSION}" >/dev/null
    fi
  fi
  ENGINE_USED="docker"
}

start_with_podman() {
  # Minimal Podman flow; assumes Podman is usable in this environment.
  if podman inspect -f '{{.State.Running}}' "$NAME" >/dev/null 2>&1; then
    :
  else
    if podman inspect "$NAME" >/dev/null 2>&1; then
      podman start "$NAME" >/dev/null
    else
      podman run -d --name "$NAME" \
        -e POSTGRES_PASSWORD="$PG_PASSWORD" \
        -e POSTGRES_USER="$PG_USERNAME" \
        -e POSTGRES_DB="$PG_DATABASE" \
        -p ${PG_HOST}:${PG_PORT}:5432 \
        "docker.io/library/postgres:${PG_VERSION}" >/dev/null
    fi
  fi
  ENGINE_USED="podman"
}

# Helpers to run commands inside container based on engine used
ctr_exec() {
  if [[ "$ENGINE_USED" == "docker" ]]; then
    docker exec "$@"
  else
    podman exec "$@"
  fi
}

ctr_cp() {
  if [[ "$ENGINE_USED" == "docker" ]]; then
    docker cp "$@"
  else
    podman cp "$@"
  fi
}

ensure_db_and_user_and_schema() {
  # Ensure role and database exist, grant privileges, and apply DDL if requested
  # Use container's psql as postgres superuser
  # 1) Ensure role
  ctr_exec -u postgres "$NAME" bash -lc "psql -d postgres -tAc \"SELECT 1 FROM pg_roles WHERE rolname='${PG_USERNAME}'\" | grep -q 1 || psql -d postgres -c \"CREATE ROLE ${PG_USERNAME} LOGIN PASSWORD '${PG_PASSWORD}'\"" >/dev/null
  # 2) Ensure database
  ctr_exec -u postgres "$NAME" bash -lc "psql -d postgres -tAc \"SELECT 1 FROM pg_database WHERE datname='${PG_DATABASE}'\" | grep -q 1 || psql -d postgres -c \"CREATE DATABASE ${PG_DATABASE}\"" >/dev/null
  # 3) Grants
  ctr_exec -u postgres "$NAME" psql -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE ${PG_DATABASE} TO ${PG_USERNAME}" >/dev/null
  # 4) Ensure any extra databases
  if [[ -n "${EXTRA_DBS}" ]]; then
    IFS=',' read -r -a _dbs <<< "${EXTRA_DBS}"
    for _db in "${_dbs[@]}"; do
      _db_trimmed="${_db// /}"
      [[ -z "${_db_trimmed}" ]] && continue
      ctr_exec -u postgres "$NAME" bash -lc "psql -d postgres -tAc \"SELECT 1 FROM pg_database WHERE datname='${_db_trimmed}'\" | grep -q 1 || psql -d postgres -c \"CREATE DATABASE ${_db_trimmed}\"" >/dev/null
      ctr_exec -u postgres "$NAME" psql -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE ${_db_trimmed} TO ${PG_USERNAME}" >/dev/null
    done
  fi
  # 5) Apply DDL if requested and available to primary and extras
  if [[ "${DO_DDL}" == "1" && -f "${DDL_PATH}" ]]; then
    ctr_cp "${DDL_PATH}" "${NAME}:/ddl.sql"
    ctr_exec -u postgres "$NAME" psql -d "${PG_DATABASE}" -f /ddl.sql >/dev/null
    if [[ -n "${EXTRA_DBS}" ]]; then
      IFS=',' read -r -a _dbs <<< "${EXTRA_DBS}"
      for _db in "${_dbs[@]}"; do
        _db_trimmed="${_db// /}"
        [[ -z "${_db_trimmed}" ]] && continue
        ctr_exec -u postgres "$NAME" psql -d "${_db_trimmed}" -f /ddl.sql >/dev/null || true
      done
    fi
  fi
}

# Apply DDL to an already-running external Postgres using local psql.
# Requires psql to be available and PG_USERNAME/PG_PASSWORD to be valid for target DBs.
apply_external_schema() {
  [[ "${DO_DDL}" == "1" && -f "${DDL_PATH}" ]] || return 0

  # Resolve DDL path to absolute for container volume mount
  resolve_abs() {
    # Usage: resolve_abs <path>
    local p="$1"
    if command -v realpath >/dev/null 2>&1; then
      realpath "$p"
    elif command -v python3 >/dev/null 2>&1; then
      python3 - "$p" <<'PY'
import os, sys
print(os.path.abspath(sys.argv[1]))
PY
    else
      echo "$(cd "$(dirname "$p")" && pwd -P)/$(basename "$p")"
    fi
  }
  DDL_ABS=$(resolve_abs "${DDL_PATH}")

  run_psql_local() {
    export PGPASSWORD="${PG_PASSWORD}"
    psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -f "${DDL_PATH}" >/dev/null
  }

  run_psql_docker() {
    local host_for_ctr="${PG_HOST}"
    if [[ "${PG_HOST}" == "127.0.0.1" || "${PG_HOST}" == "localhost" ]]; then
      host_for_ctr="host.docker.internal"
    fi
    docker run --rm -e PGPASSWORD="${PG_PASSWORD}" -v "${DDL_ABS}:/ddl.sql:ro" "postgres:${PG_VERSION}" \
      psql -h "${host_for_ctr}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -f /ddl.sql >/dev/null
  }

  run_psql_podman() {
    local host_for_ctr="${PG_HOST}"
    if [[ "${PG_HOST}" == "127.0.0.1" || "${PG_HOST}" == "localhost" ]]; then
      host_for_ctr="host.containers.internal"
    fi
    podman run --rm -e PGPASSWORD="${PG_PASSWORD}" -v "${DDL_ABS}:/ddl.sql:ro" "docker.io/library/postgres:${PG_VERSION}" \
      psql -h "${host_for_ctr}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -f /ddl.sql >/dev/null
  }

  run_psql() {
    local db="$1"
    if command -v psql >/dev/null 2>&1; then
      run_psql_local "$db"
    elif has_cmd docker && docker info >/dev/null 2>&1; then
      run_psql_docker "$db"
    elif has_cmd podman; then
      run_psql_podman "$db"
    else
      echo "Neither psql, docker, nor podman available to apply external schema." >&2
      return 1
    fi
  }

  # Execute SQL (-c) via local/docker/podman psql
  run_psql_cmd_local() {
    export PGPASSWORD="${PG_PASSWORD}"
    psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -v ON_ERROR_STOP=1 -c "$2" >/dev/null
  }
  run_psql_cmd_docker() {
    local host_for_ctr="${PG_HOST}"
    if [[ "${PG_HOST}" == "127.0.0.1" || "${PG_HOST}" == "localhost" ]]; then
      host_for_ctr="host.docker.internal"
    fi
    docker run --rm -e PGPASSWORD="${PG_PASSWORD}" "postgres:${PG_VERSION}" \
      psql -h "${host_for_ctr}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -v ON_ERROR_STOP=1 -c "$2" >/dev/null
  }
  run_psql_cmd_podman() {
    local host_for_ctr="${PG_HOST}"
    if [[ "${PG_HOST}" == "127.0.0.1" || "${PG_HOST}" == "localhost" ]]; then
      host_for_ctr="host.containers.internal"
    fi

    ## if no podman, call bootstrap-podman-postgres.sh
    if ! has_cmd podman; then
      ./scripts/bootstrap-podman-postgres.sh --fix-rootless --pg-version "${PG_VERSION}" --port "${PG_PORT}" --name "${NAME}" --ddl "${DDL_PATH}" --export-docker-host
    fi

    podman run --rm -e PGPASSWORD="${PG_PASSWORD}" "docker.io/library/postgres:${PG_VERSION}" \
      psql -h "${host_for_ctr}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -v ON_ERROR_STOP=1 -c "$2" >/dev/null
  }
  run_psql_cmd() {
    local db="$1" sql="$2"
    if command -v psql >/dev/null 2>&1; then
      run_psql_cmd_local "$db" "$sql"
    elif has_cmd docker && docker info >/dev/null 2>&1; then
      run_psql_cmd_docker "$db" "$sql"
    elif has_cmd podman; then
      run_psql_cmd_podman "$db" "$sql"
    else
      return 1
    fi
  }

  # Scalar (-tAc) helpers to test existence
  run_psql_scalar_local() {
    export PGPASSWORD="${PG_PASSWORD}"
    psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -tAc "$2" 2>/dev/null
  }
  run_psql_scalar_docker() {
    local host_for_ctr="${PG_HOST}"
    if [[ "${PG_HOST}" == "127.0.0.1" || "${PG_HOST}" == "localhost" ]]; then
      host_for_ctr="host.docker.internal"
    fi
    docker run --rm -e PGPASSWORD="${PG_PASSWORD}" "postgres:${PG_VERSION}" \
      psql -h "${host_for_ctr}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -tAc "$2" 2>/dev/null
  }
  run_psql_scalar_podman() {
    local host_for_ctr="${PG_HOST}"
    if [[ "${PG_HOST}" == "127.0.0.1" || "${PG_HOST}" == "localhost" ]]; then
      host_for_ctr="host.containers.internal"
    fi
    podman run --rm -e PGPASSWORD="${PG_PASSWORD}" "docker.io/library/postgres:${PG_VERSION}" \
      psql -h "${host_for_ctr}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -tAc "$2" 2>/dev/null
  }
  run_psql_scalar() {
    local db="$1" sql="$2"
    if command -v psql >/dev/null 2>&1; then
      run_psql_scalar_local "$db" "$sql"
    elif has_cmd docker && docker info >/dev/null 2>&1; then
      run_psql_scalar_docker "$db" "$sql"
    elif has_cmd podman; then
      run_psql_scalar_podman "$db" "$sql"
    else
      echo ""; return 0
    fi
  }

  # Ensure role and databases (if permissions allow)
  if [[ "$(run_psql_scalar postgres "SELECT 1 FROM pg_roles WHERE rolname='${PG_USERNAME}'")" != "1" ]]; then
    run_psql_cmd postgres "CREATE ROLE \"${PG_USERNAME}\" LOGIN PASSWORD '${PG_PASSWORD}'" || true
  fi
  if [[ "$(run_psql_scalar postgres "SELECT 1 FROM pg_database WHERE datname='${PG_DATABASE}'")" != "1" ]]; then
    run_psql_cmd postgres "CREATE DATABASE \"${PG_DATABASE}\"" || true
  fi
  run_psql_cmd postgres "GRANT ALL PRIVILEGES ON DATABASE \"${PG_DATABASE}\" TO \"${PG_USERNAME}\"" || true
  if [[ -n "${EXTRA_DBS}" ]]; then
    IFS=',' read -r -a _dbs <<< "${EXTRA_DBS}"
    for _db in "${_dbs[@]}"; do
      _db_trimmed="${_db// /}"
      [[ -z "${_db_trimmed}" ]] && continue
      if [[ "$(run_psql_scalar postgres "SELECT 1 FROM pg_database WHERE datname='${_db_trimmed}'")" != "1" ]]; then
        run_psql_cmd postgres "CREATE DATABASE \"${_db_trimmed}\"" || true
      fi
      run_psql_cmd postgres "GRANT ALL PRIVILEGES ON DATABASE \"${_db_trimmed}\" TO \"${PG_USERNAME}\"" || true
    done
  fi

  # Execute an arbitrary SQL command against a DB using local/docker/podman psql
  run_psql_cmd_local() {
    export PGPASSWORD="${PG_PASSWORD}"
    psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -v ON_ERROR_STOP=1 -c "$2" >/dev/null
  }
  run_psql_cmd_docker() {
    local host_for_ctr="${PG_HOST}"
    if [[ "${PG_HOST}" == "127.0.0.1" || "${PG_HOST}" == "localhost" ]]; then
      host_for_ctr="host.docker.internal"
    fi
    docker run --rm -e PGPASSWORD="${PG_PASSWORD}" "postgres:${PG_VERSION}" \
      psql -h "${host_for_ctr}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -v ON_ERROR_STOP=1 -c "$2" >/dev/null
  }
  run_psql_cmd_podman() {
    local host_for_ctr="${PG_HOST}"
    if [[ "${PG_HOST}" == "127.0.0.1" || "${PG_HOST}" == "localhost" ]]; then
      host_for_ctr="host.containers.internal"
    fi
    podman run --rm -e PGPASSWORD="${PG_PASSWORD}" "docker.io/library/postgres:${PG_VERSION}" \
      psql -h "${host_for_ctr}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$1" -v ON_ERROR_STOP=1 -c "$2" >/dev/null
  }
  run_psql_cmd() {
    local db="$1" sql="$2"
    if command -v psql >/dev/null 2>&1; then
      run_psql_cmd_local "$db" "$sql"
    elif has_cmd docker && docker info >/dev/null 2>&1; then
      run_psql_cmd_docker "$db" "$sql"
    elif has_cmd podman; then
      run_psql_cmd_podman "$db" "$sql"
    else
      return 1
    fi
  }

  # Remove legacy DO $$ blocks and unquoted GRANTs (replaced above with scalar + quoted statements)

  # Primary DB
  run_psql "${PG_DATABASE}" || {
    echo "Failed to apply DDL to ${PG_DATABASE} on ${PG_HOST}:${PG_PORT}" >&2
    return 1
  }
  # Extras
  if [[ -n "${EXTRA_DBS}" ]]; then
    IFS=',' read -r -a _dbs <<< "${EXTRA_DBS}"
    for _db in "${_dbs[@]}"; do
      _db_trimmed="${_db// /}"
      [[ -z "${_db_trimmed}" ]] && continue
      run_psql "${_db_trimmed}" || true
    done
  fi
}

# 1) If PG is already reachable, print exports and exit
if pg_reachable "$PG_HOST" "$PG_PORT"; then
  if [[ "${APPLY_EXTERNAL}" == "1" ]]; then
    apply_external_schema || true
  fi
else
  # 2) Decide engine and start container if possible
  case "$ENGINE" in
    auto)
      if has_cmd docker && docker info >/dev/null 2>&1; then
        start_with_docker
      elif has_cmd podman; then
        start_with_podman
      else
        echo "Neither Docker nor Podman are available to start Postgres." >&2
        exit 1
      fi
      ;;
    docker)
      if has_cmd docker && docker info >/dev/null 2>&1; then
        start_with_docker
      else
        echo "Docker not available." >&2; exit 1
      fi
      ;;
    podman)
      if has_cmd podman; then
        start_with_podman
      else
        echo "Podman not available." >&2; exit 1
      fi
      ;;
    *)
      echo "Unknown engine: $ENGINE" >&2; exit 2
      ;;
  esac

  # 3) Wait until reachable
  wait_pg "$PG_HOST" "$PG_PORT"
  # 3b) Ensure schema if we just (re)started a managed container
  if [[ -n "$ENGINE_USED" ]]; then
    ensure_db_and_user_and_schema
  fi
fi

# 4) Print export lines for caller to eval/source
cat <<ENV
export PG_HOST=${PG_HOST}
export PG_PORT=${PG_PORT}
export PG_DATABASE=${PG_DATABASE}
export PG_USERNAME=${PG_USERNAME}
export PG_PASSWORD=${PG_PASSWORD}
export DATABASE_URL=postgres://${PG_USERNAME}:${PG_PASSWORD}@${PG_HOST}:${PG_PORT}/${PG_DATABASE}
ENV


