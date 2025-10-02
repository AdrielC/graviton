#!/usr/bin/env bash
set -euo pipefail

# ensure-postgres.sh
# Ensures a local PostgreSQL is available. If it's already reachable, prints
# export lines for env vars. Otherwise, starts a container (Docker preferred,
# Podman fallback, native as last resort), waits until ready, then prints export lines.
#
# Usage:
#   ./scripts/ensure-postgres.sh [--engine auto|docker|podman|native] \
#       [--pg-version 17] [--name graviton-pg] \
#       [--host 127.0.0.1] [--port 5432] \
#       [--db postgres] [--user postgres] [--password postgres] \
#       [--auto-install] [--no-ddl] [--apply-external]
#
# Typical integration:
#   eval "$(./scripts/ensure-postgres.sh)"
#
# Auto-install is enabled automatically when running in:
#   - CI environments (CI=true, ENV=CI)
#   - Agent environments (AGENT=true, ENV=AGENT)
#   - Or explicitly with AUTO_INSTALL_PG=1 or --auto-install
#
# Examples:
#   # In CI (auto-detects and installs if needed)
#   ENV=CI ./scripts/ensure-postgres.sh --engine native
#   
#   # In agent environment
#   AGENT=true ./scripts/ensure-postgres.sh --engine native
#   
#   # Explicit auto-install
#   ./scripts/ensure-postgres.sh --auto-install --engine native
#


ENGINE="auto"                      # auto | docker | podman | native
PG_VERSION="${PG_VERSION:-17}"
NAME="${PG_NAME:-graviton}"
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_PORT="${PG_PORT:-5432}"
PG_DATABASE="${PG_DATABASE:-graviton}"
# Optional: comma-separated additional databases to ensure
EXTRA_DBS="${PG_DBS:-}"
PG_USERNAME="${PG_USERNAME:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
DDL_PATH="${DDL_PATH:-modules/pg/ddl.sql}"
DO_DDL=${DO_DDL:-1}
# If PG is already reachable (external), optionally apply DDL using local psql
APPLY_EXTERNAL=${APPLY_EXTERNAL:-0}
# For native mode: where to store data
PG_DATA_DIR="${PG_DATA_DIR:-${HOME}/.graviton/pgdata}"

# Auto-detect ephemeral environments and enable auto-install
# Checks: CI=true, AGENT=true, ENV=CI, ENV=AGENT, or explicit AUTO_INSTALL_PG=1
if [[ "${CI:-false}" == "true" ]] || \
   [[ "${AGENT:-false}" == "true" ]] || \
   [[ "${ENV:-LOCAL}" == "CI" ]] || \
   [[ "${ENV:-LOCAL}" == "AGENT" ]] || \
   [[ "${AUTO_INSTALL_PG:-0}" == "1" ]]; then
  AUTO_INSTALL_PG=1
else
  AUTO_INSTALL_PG=0
fi

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
    --auto-install) AUTO_INSTALL_PG=1; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

has_cmd() { command -v "$1" >/dev/null 2>&1; }

# Auto-install postgres tools if requested and missing
auto_install_postgres() {
  [[ "${AUTO_INSTALL_PG}" != "1" ]] && return 1
  
  echo "Auto-installing PostgreSQL tools..." >&2
  
  # Detect OS and package manager
  if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    if has_cmd brew; then
      echo "Installing postgresql@${PG_VERSION} via Homebrew..." >&2
      brew install "postgresql@${PG_VERSION}" >/dev/null 2>&1 || {
        echo "Homebrew installation failed, trying postgresql (latest)..." >&2
        brew install postgresql >/dev/null 2>&1 || return 1
      }
      # Add to PATH if not already there
      PG_BIN="/opt/homebrew/opt/postgresql@${PG_VERSION}/bin"
      [[ -d "$PG_BIN" ]] && export PATH="$PG_BIN:$PATH"
      return 0
    else
      echo "Homebrew not found. Install from https://brew.sh" >&2
      return 1
    fi
  elif [[ -f /etc/debian_version ]]; then
    # Debian/Ubuntu
    if has_cmd apt-get; then
      echo "Installing postgresql-${PG_VERSION} via apt..." >&2
      sudo apt-get update -qq >/dev/null 2>&1
      sudo apt-get install -y -qq "postgresql-${PG_VERSION}" "postgresql-client-${PG_VERSION}" >/dev/null 2>&1 || {
        echo "Failed to install postgresql-${PG_VERSION}, trying postgresql (default version)..." >&2
        sudo apt-get install -y -qq postgresql postgresql-client >/dev/null 2>&1 || return 1
      }
      # Add to PATH
      PG_BIN="/usr/lib/postgresql/${PG_VERSION}/bin"
      [[ -d "$PG_BIN" ]] && export PATH="$PG_BIN:$PATH"
      return 0
    fi
  elif [[ -f /etc/redhat-release ]]; then
    # RHEL/CentOS/Fedora
    if has_cmd dnf; then
      echo "Installing postgresql${PG_VERSION}-server via dnf..." >&2
      sudo dnf install -y -q "postgresql${PG_VERSION}-server" "postgresql${PG_VERSION}" >/dev/null 2>&1 || {
        echo "Failed to install postgresql${PG_VERSION}, trying postgresql (default)..." >&2
        sudo dnf install -y -q postgresql-server postgresql >/dev/null 2>&1 || return 1
      }
      return 0
    elif has_cmd yum; then
      echo "Installing postgresql${PG_VERSION}-server via yum..." >&2
      sudo yum install -y -q "postgresql${PG_VERSION}-server" "postgresql${PG_VERSION}" >/dev/null 2>&1 || {
        echo "Failed to install postgresql${PG_VERSION}, trying postgresql (default)..." >&2
        sudo yum install -y -q postgresql-server postgresql >/dev/null 2>&1 || return 1
      }
      return 0
    fi
  elif [[ -f /etc/arch-release ]]; then
    # Arch Linux
    if has_cmd pacman; then
      echo "Installing postgresql via pacman..." >&2
      sudo pacman -Sy --noconfirm postgresql >/dev/null 2>&1 || return 1
      return 0
    fi
  fi
  
  echo "Unsupported OS or package manager for auto-install." >&2
  return 1
}

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
      if [[ -n "${ENGINE_USED}" ]]; then
        echo "Check logs: docker logs $NAME (or podman logs $NAME)" >&2
      fi
      return 1
    fi
  done
  # Give postgres a moment to fully start accepting connections
  sleep 0.5
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

start_with_native() {
  # Start Postgres natively using pg_ctl (requires postgres to be installed locally)
  if ! has_cmd pg_ctl || ! has_cmd initdb || ! has_cmd createdb || ! has_cmd psql; then
    if [[ "${AUTO_INSTALL_PG}" == "1" ]]; then
      auto_install_postgres || {
        echo "Auto-installation failed. Install manually:" >&2
        echo "  macOS: brew install postgresql@${PG_VERSION}" >&2
        echo "  Ubuntu/Debian: sudo apt-get install postgresql-${PG_VERSION}" >&2
        echo "  RHEL/Fedora: sudo dnf install postgresql${PG_VERSION}-server" >&2
        return 1
      }
      # Re-check after installation
      if ! has_cmd pg_ctl || ! has_cmd initdb || ! has_cmd createdb || ! has_cmd psql; then
        echo "Postgres tools still not found after installation." >&2
        return 1
      fi
    else
      echo "Native postgres tools (pg_ctl, initdb, createdb, psql) not found." >&2
      echo "Install postgres: brew install postgresql@${PG_VERSION} (macOS) or apt-get install postgresql (Linux)" >&2
      echo "Or run with AUTO_INSTALL_PG=1 or --auto-install flag to auto-install." >&2
      return 1
    fi
  fi

  mkdir -p "$PG_DATA_DIR"
  
  # Initialize data directory if needed
  if [[ ! -f "$PG_DATA_DIR/PG_VERSION" ]]; then
    echo "Initializing postgres data directory at $PG_DATA_DIR..." >&2
    initdb -D "$PG_DATA_DIR" --username="$PG_USERNAME" --pwfile=<(echo "$PG_PASSWORD") >/dev/null 2>&1 || {
      echo "Failed to initialize postgres data directory." >&2
      return 1
    }
  fi

  # Check if already running
  if pg_ctl status -D "$PG_DATA_DIR" >/dev/null 2>&1; then
    : # Already running
  else
    # Start postgres
    pg_ctl -D "$PG_DATA_DIR" -l "$PG_DATA_DIR/logfile" -o "-p $PG_PORT" start >/dev/null 2>&1 || {
      echo "Failed to start native postgres on port $PG_PORT." >&2
      return 1
    }
  fi

  ENGINE_USED="native"
}

ensure_db_native() {
  # Just use the unified abstraction - it already handles native mode
  ensure_db_and_user_and_schema
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

# Generic SQL execution abstraction - works for container or native
exec_sql() {
  local db="$1"
  local sql="$2"
  
  if [[ "$ENGINE_USED" == "native" ]]; then
    # Native: use local psql
    export PGPASSWORD="${PG_PASSWORD}"
    psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$db" -c "$sql" >/dev/null 2>&1
  else
    # Container: use container's psql as postgres superuser
    ctr_exec -u postgres "$NAME" psql -d "$db" -c "$sql" >/dev/null 2>&1
  fi
}

exec_sql_file() {
  local db="$1"
  local file="$2"
  
  if [[ "$ENGINE_USED" == "native" ]]; then
    # Native: use local psql with local file
    export PGPASSWORD="${PG_PASSWORD}"
    psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d "$db" -f "$file" >/dev/null 2>&1
  else
    # Container: copy file and execute
    ctr_cp "$file" "${NAME}:/tmp/ddl.sql"
    ctr_exec -u postgres "$NAME" psql -d "$db" -f /tmp/ddl.sql >/dev/null 2>&1
  fi
}

check_role_exists() {
  local role="$1"
  
  if [[ "$ENGINE_USED" == "native" ]]; then
    export PGPASSWORD="${PG_PASSWORD}"
    psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d postgres -tAc \
      "SELECT 1 FROM pg_roles WHERE rolname='${role}'" 2>/dev/null | grep -q 1
  else
    ctr_exec -u postgres "$NAME" bash -lc \
      "psql -d postgres -tAc \"SELECT 1 FROM pg_roles WHERE rolname='${role}'\" | grep -q 1" 2>/dev/null
  fi
}

check_db_exists() {
  local db="$1"
  
  if [[ "$ENGINE_USED" == "native" ]]; then
    export PGPASSWORD="${PG_PASSWORD}"
    psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USERNAME}" -d postgres -tAc \
      "SELECT 1 FROM pg_database WHERE datname='${db}'" 2>/dev/null | grep -q 1
  else
    ctr_exec -u postgres "$NAME" bash -lc \
      "psql -d postgres -tAc \"SELECT 1 FROM pg_database WHERE datname='${db}'\" | grep -q 1" 2>/dev/null
  fi
}

ensure_db_and_user_and_schema() {
  # Ensure role and database exist, grant privileges, and apply DDL
  # Works for both containers and native postgres
  
  # 1) Ensure role
  if ! check_role_exists "${PG_USERNAME}"; then
    exec_sql postgres "CREATE ROLE \"${PG_USERNAME}\" LOGIN PASSWORD '${PG_PASSWORD}'" || true
  fi
  
  # 2) Ensure database
  if ! check_db_exists "${PG_DATABASE}"; then
    exec_sql postgres "CREATE DATABASE \"${PG_DATABASE}\"" || true
  fi
  
  # 3) Grants
  exec_sql postgres "GRANT ALL PRIVILEGES ON DATABASE \"${PG_DATABASE}\" TO \"${PG_USERNAME}\"" || true
  
  # 4) Ensure any extra databases
  if [[ -n "${EXTRA_DBS}" ]]; then
    IFS=',' read -r -a _dbs <<< "${EXTRA_DBS}"
    for _db in "${_dbs[@]}"; do
      _db_trimmed="${_db// /}"
      [[ -z "${_db_trimmed}" ]] && continue
      
      if ! check_db_exists "${_db_trimmed}"; then
        exec_sql postgres "CREATE DATABASE \"${_db_trimmed}\"" || true
      fi
      exec_sql postgres "GRANT ALL PRIVILEGES ON DATABASE \"${_db_trimmed}\" TO \"${PG_USERNAME}\"" || true
    done
  fi
  
  # 5) Apply DDL if requested and available to primary and extras
  if [[ "${DO_DDL}" == "1" && -f "${DDL_PATH}" ]]; then
    exec_sql_file "${PG_DATABASE}" "${DDL_PATH}" || true
    
    if [[ -n "${EXTRA_DBS}" ]]; then
      IFS=',' read -r -a _dbs <<< "${EXTRA_DBS}"
      for _db in "${_dbs[@]}"; do
        _db_trimmed="${_db// /}"
        [[ -z "${_db_trimmed}" ]] && continue
        exec_sql_file "${_db_trimmed}" "${DDL_PATH}" || true
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
      elif has_cmd pg_ctl; then
        start_with_native
      else
        echo "Neither Docker, Podman, nor native Postgres tools are available." >&2
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
    native)
      start_with_native || exit 1
      ;;
    *)
      echo "Unknown engine: $ENGINE" >&2; exit 2
      ;;
  esac

  # 3) Wait until reachable
  wait_pg "$PG_HOST" "$PG_PORT"
  # 3b) Ensure schema if we just (re)started a managed container or native
  if [[ "$ENGINE_USED" == "native" ]]; then
    ensure_db_native
  elif [[ -n "$ENGINE_USED" ]]; then
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


