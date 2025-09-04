#!/usr/bin/env bash
set -euo pipefail

# -------------------------------------------------------------------
# Usage:
#   ./bootstrap-podman-postgres.sh \
#     [--pg 17] [--name graviton-pg] [--port 5432] \
#     [--password postgres] [--ddl modules/pg/ddl.sql] [--no-docker-host]
#
# Env vars (override defaults):
#   PG_VERSION, PG_NAME, PG_PORT, PG_PASSWORD, DDL_PATH
# -------------------------------------------------------------------

PG_VERSION="${PG_VERSION:-17}"
PG_NAME="${PG_NAME:-graviton-pg}"
PG_PORT="${PG_PORT:-5432}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
DDL_PATH="${DDL_PATH:-}"
SET_DOCKER_HOST=1

# ---------- args ----------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --pg) PG_VERSION="$2"; shift 2 ;;
    --name) PG_NAME="$2"; shift 2 ;;
    --port) PG_PORT="$2"; shift 2 ;;
    --password) PG_PASSWORD="$2"; shift 2 ;;
    --ddl) DDL_PATH="$2"; shift 2 ;;
    --no-docker-host) SET_DOCKER_HOST=0; shift ;;
    -h|--help)
      sed -n '1,60p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

# ---------- util ----------
log() { printf "\033[1;36m[setup]\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33m[warn]\033[0m  %s\n" "$*"; }
die() { printf "\033[1;31m[fail]\033[0m  %s\n" "$*"; exit 1; }

has_cmd() { command -v "$1" >/dev/null 2>&1; }

detect_distro() {
  if [[ -f /etc/os-release ]]; then
    . /etc/os-release
    echo "${ID:-unknown}"
  else
    echo "unknown"
  fi
}

install_pkg() {
  # $1 ... packages
  local distro; distro="$(detect_distro)"
  case "$distro" in
    ubuntu|debian)
      sudo apt-get update -y
      sudo apt-get install -y "$@"
      ;;
    fedora)
      sudo dnf install -y "$@"
      ;;
    rhel|centos|rocky|almalinux)
      sudo yum install -y "$@"
      ;;
    arch)
      sudo pacman -Sy --noconfirm "$@"
      ;;
    alpine)
      sudo apk add --no-cache "$@"
      ;;
    *)
      die "Unsupported distro for auto-install. Please install: $*"
      ;;
  esac
}

# ---------- ensure podman ----------
if ! has_cmd podman; then
  log "Podman not found — installing…"
  # minimal set: podman + netavark + fuse-overlayfs + slirp4netns + passt (pasta)
  case "$(detect_distro)" in
    ubuntu|debian)
      install_pkg podman netavark fuse-overlayfs slirp4netns passt
      ;;
    fedora)
      install_pkg podman netavark fuse-overlayfs slirp4netns passt
      ;;
    arch)
      install_pkg podman netavark fuse-overlayfs slirp4netns passt
      ;;
    alpine)
      install_pkg podman netavark fuse-overlayfs slirp4netns passt
      ;;
    *)
      install_pkg podman
      warn "Could not ensure netavark/slirp4netns/passt automatically."
      ;;
  esac
else
  log "Podman present: $(podman --version)"
fi

# ---------- pick network backend ----------
NETWORK_BACKEND="pasta"
if ! has_cmd pasta; then
  warn "pasta (from 'passt') not found — attempting install…"
  if detect_distro >/dev/null; then
    if ! has_cmd pasta; then
      case "$(detect_distro)" in
        ubuntu|debian|fedora|arch|alpine)
          install_pkg passt || true
          ;;
      esac
    fi
  fi
fi
if ! has_cmd pasta; then
  warn "pasta still unavailable; will try slirp4netns."
  NETWORK_BACKEND="slirp4netns"
  if ! has_cmd slirp4netns; then
    warn "slirp4netns not found — installing…"
    case "$(detect_distro)" in
      ubuntu|debian|fedora|arch|alpine)
        install_pkg slirp4netns || true
        ;;
    esac
  fi
fi

# pasta works only in rootless mode; fall back if running as root
if [[ "$NETWORK_BACKEND" == "pasta" && $(id -u) -eq 0 ]]; then
  warn "pasta networking only supported for rootless Podman; falling back to slirp4netns."
  NETWORK_BACKEND="slirp4netns"
  if ! has_cmd slirp4netns; then
    warn "slirp4netns not found — installing…"
    case "$(detect_distro)" in
      ubuntu|debian|fedora|arch|alpine)
        install_pkg slirp4netns || true
        ;;
    esac
  fi
fi

if [[ "$NETWORK_BACKEND" == "slirp4netns" ]]; then
  # slirp4netns often needs /dev/net/tun; if missing, warn now.
  if [[ ! -e /dev/net/tun ]]; then
    warn "/dev/net/tun is missing — slirp4netns may fail. Prefer pasta (install 'passt')."
  fi
fi
log "Network backend: $NETWORK_BACKEND"

# ---------- rootless-friendly config ----------
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
mkdir -p "$XDG_RUNTIME_DIR/podman" "$HOME/.config/containers"

# containers.conf
cat > "$HOME/.config/containers/containers.conf" <<CFG
[engine]
events_logger = "file"
cgroup_manager = "cgroupfs"
network_backend = "${NETWORK_BACKEND}"
helper_binaries_dir = ["/usr/bin","/usr/local/bin"]
conmon_cgroup = "pod"
CFG

# storage.conf
cat > "$HOME/.config/containers/storage.conf" <<'CFG'
[storage]
driver = "vfs"
runroot = "/tmp/podman-runroot"
graphroot = "/tmp/podman-root"
CFG

# Optional: expose Docker-compatible socket for Testcontainers
if [[ "${SET_DOCKER_HOST}" == "1" ]]; then
  if [[ ! -S "$XDG_RUNTIME_DIR/podman/podman.sock" ]]; then
    log "Starting Podman API (Docker-compatible) socket…"
    nohup podman system service --time=0 "unix://$XDG_RUNTIME_DIR/podman/podman.sock" \
      >/tmp/podapi.log 2>&1 &
    # Give it a moment
    sleep 0.5 || true
  fi
  export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/podman/podman.sock"
  log "DOCKER_HOST=$DOCKER_HOST"
fi

# ---------- run Postgres ----------
IMAGE="docker.io/library/postgres:${PG_VERSION}"

log "Removing old container (if any)…"
podman rm -f "$PG_NAME" >/dev/null 2>&1 || true

# Build network flag
NET_FLAG="--network=${NETWORK_BACKEND}"
if [[ "$NETWORK_BACKEND" == "slirp4netns" ]]; then
  NET_FLAG="--network=slirp4netns:allow_host_loopback=true"
fi

# Some runners block cgroups and seccomp; disable to avoid false fails.
log "Starting Postgres $PG_VERSION as container '$PG_NAME'…"
set -x
podman run --rm -d --name "$PG_NAME" \
  ${NET_FLAG} \
  --cgroups=disabled \
  --userns=keep-id \
  --security-opt seccomp=unconfined \
  --tmpfs /var/lib/postgresql/data:rw,size=1024m \
  -e POSTGRES_PASSWORD="${PG_PASSWORD}" \
  -p 127.0.0.1:${PG_PORT}:5432 \
  "${IMAGE}"
set +x

# ---------- wait ready ----------
log "Waiting for Postgres to become ready on 127.0.0.1:${PG_PORT}…"
tries=0
until podman exec "$PG_NAME" pg_isready -U postgres >/dev/null 2>&1; do
  sleep 0.5
  tries=$((tries+1))
  if (( tries > 180 )); then
    warn "Postgres failed to become ready."
    podman logs "$PG_NAME" || true
    podman inspect "$PG_NAME" --format '{{json .State}}' || true
    die  "Container did not become healthy."
  fi
done

# ---------- optional DDL ----------
if [[ -n "$DDL_PATH" ]]; then
  [[ -f "$DDL_PATH" ]] || die "DDL not found at $DDL_PATH"
  log "Loading DDL from $DDL_PATH…"
  podman cp "$DDL_PATH" "$PG_NAME:/ddl.sql"
  podman exec -u postgres "$PG_NAME" psql -f /ddl.sql >/dev/null
fi

log "✅ Postgres ${PG_VERSION} is running at 127.0.0.1:${PG_PORT} (user=postgres password=${PG_PASSWORD})"
if [[ "${SET_DOCKER_HOST}" == "1" ]]; then
  log "Testcontainers can target Podman via DOCKER_HOST ($DOCKER_HOST)."
fi
