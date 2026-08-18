#!/usr/bin/env bash
# Bootstrap a rootless Podman-managed PostgreSQL for local dev & CI,
# and (optionally) expose a Docker-compatible socket for Testcontainers.
#
# Usage:
#   scripts/bootstrap-podman-postgres.sh [--pg 18] [--port 5432] [--name graviton-pg] \
#       [--ddl modules/pg/ddl.sql] [--export-docker-host] [--no-ddl] [--fix-rootless]
#
# Defaults: PG_VERSION=18, PORT=5432, NAME=graviton-pg, DDL=modules/pg/ddl.sql, EXPORT_DOCKER_HOST=on
#
# What it does:
#   - Installs Podman (and pasta/slirp4netns) if missing (Ubuntu/Debian/Fedora/Arch/Alpine).
#   - Configures rootless-friendly Podman (VFS storage, chosen network backend).
#   - Starts the Podman API socket at $XDG_RUNTIME_DIR/podman/podman.sock (Docker-compatible).
#   - Runs docker.io/library/postgres:$PG_VERSION with tmpfs data, waits healthy, loads DDL.
#
set -euo pipefail

############ helpers ############
log()  { printf "\033[1;34m[INFO]\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33m[WARN]\033[0m %s\n" "$*"; }
die()  { printf "\033[1;31m[FAIL]\033[0m %s\n" "$*\n" >&2; exit 1; }

# global
FIX_ROOTLESS=0

# parse args early
for arg in "$@"; do
  [[ "$arg" == "--fix-rootless" ]] && FIX_ROOTLESS=1
done

# target user (works when run via sudo)
TARGET_UID=${SUDO_UID:-$(id -u)}
TARGET_USER=${SUDO_USER:-$(id -un)}

has_cmd() { command -v "$1" >/dev/null 2>&1; }

detect_distro() {
  if [[ -r /etc/os-release ]]; then
    . /etc/os-release
    echo "${ID:-unknown}"
  else
    echo "unknown"
  fi
}

install_pkg() {
  # install_pkg <packages...>
  local distro; distro="$(detect_distro)"
  case "$distro" in
    ubuntu|debian)
      # Some environments block plain HTTP traffic which Ubuntu's default
      # apt sources still use.  Rewrite any http:// entries to https:// so the
      # package install can proceed even when only HTTPS is allowed.
      if grep -Rq '^URIs: http://.*ubuntu.com' /etc/apt/sources.list.d 2>/dev/null; then
        warn "Switching APT sources to HTTPS"
        if has_cmd sudo; then
          sudo find /etc/apt/sources.list.d \( -name '*.sources' -o -name '*.list' \) \
            -exec sed -i 's|http://|https://|g' {} +
        else
          find /etc/apt/sources.list.d \( -name '*.sources' -o -name '*.list' \) \
            -exec sed -i 's|http://|https://|g' {} +
        fi
      fi
      if has_cmd sudo; then sudo apt-get update -y; sudo apt-get install -y "$@"
      else apt-get update -y; apt-get install -y "$@"; fi
      ;;
    fedora)
      if has_cmd sudo; then sudo dnf install -y "$@"
      else dnf install -y "$@"; fi
      ;;
    arch)
      if has_cmd sudo; then sudo pacman -Sy --noconfirm "$@"
      else pacman -Sy --noconfirm "$@"; fi
      ;;
    alpine)
      if has_cmd sudo; then sudo apk add --no-cache "$@"
      else apk add --no-cache "$@"; fi
      ;;
    *)
      warn "Unknown distro; please install packages manually: $*"
      return 1
      ;;
  esac
}

############ args ############
PG_VERSION="${PG_VERSION:-18}"
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_PORT="${PG_PORT:-5432}"
PG_NAME="${PG_NAME:-graviton-pg}"
DDL_PATH_DEFAULT="modules/pg/ddl.sql"
DDL_PATH="$DDL_PATH_DEFAULT"
DO_EXPORT_DOCKER_HOST=1
DO_DDL=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pg)   PG_VERSION="${2:?}"; shift 2 ;;
    --host) PG_HOST="${2:?}"; shift 2 ;;
    --port) PG_PORT="${2:?}"; shift 2 ;;
    --name) PG_NAME="${2:?}"; shift 2 ;;
    --ddl)  DDL_PATH="${2:?}"; shift 2 ;;
    --no-ddl) DO_DDL=0; shift ;;
    --export-docker-host) DO_EXPORT_DOCKER_HOST=1; shift ;;
    --no-export-docker-host) DO_EXPORT_DOCKER_HOST=0; shift ;;
    --fix-rootless) shift ;; # already handled
    *) die "Unknown arg: $1" ;;
  esac
done

############ ensure podman ############
if ! has_cmd podman; then
  log "Podman not found — installing…"
  case "$(detect_distro)" in
    ubuntu|debian) install_pkg podman podman-docker fuse-overlayfs slirp4netns || true ;;
    fedora)        install_pkg podman fuse-overlayfs slirp4netns || true ;;
    arch)          install_pkg podman slirp4netns || true ;;
    alpine)        install_pkg podman slirp4netns || true ;;
    *)             die "Podman is required; please install it and re-run." ;;
  esac
fi
has_cmd podman || die "Podman still not available after install."
log "Podman: $(podman --version)"

# optional helpers
if ! has_cmd pasta; then
  warn "pasta (from 'passt') not found — attempting install…"
  case "$(detect_distro)" in
    ubuntu|debian|fedora|arch|alpine) install_pkg passt || true ;;
  esac
fi
if ! has_cmd slirp4netns; then
  warn "slirp4netns not found — attempting install…"
  case "$(detect_distro)" in
    ubuntu|debian|fedora|arch|alpine) install_pkg slirp4netns || true ;;
  esac
fi

# ---------- sanity checks for rootless Podman ----------
if [[ $TARGET_UID -ne 0 ]]; then
  log "Running as $TARGET_USER (uid=$TARGET_UID) → verifying userns support for rootless Podman…"

  # check sysctl
  if sysctl user.max_user_namespaces >/dev/null 2>&1; then
    ns_max=$(sysctl -n user.max_user_namespaces 2>/dev/null || echo 0)
    if [[ "$ns_max" -le 0 ]]; then
      die "user namespaces are disabled (user.max_user_namespaces=$ns_max). Enable them to run rootless Podman."
    fi
  else
    warn "sysctl user.max_user_namespaces not found — assuming namespaces may be restricted."
  fi

  # check subuid/subgid entries
  user="$TARGET_USER"
  if ! grep -q "^${user}:" /etc/subuid; then
    if [[ $FIX_ROOTLESS -eq 1 ]]; then
      if [[ $(id -u) -ne 0 ]]; then
        die "--fix-rootless must be run as root to edit /etc/subuid"
      fi
      echo "${user}:100000:65536" >> /etc/subuid
      log "Added /etc/subuid entry for $user → ${user}:100000:65536"
    else
      die "No /etc/subuid entry for $user — run with --fix-rootless or add manually."
    fi
  fi
  if ! grep -q "^${user}:" /etc/subgid; then
    if [[ $FIX_ROOTLESS -eq 1 ]]; then
      if [[ $(id -u) -ne 0 ]]; then
        die "--fix-rootless must be run as root to edit /etc/subgid"
      fi
      echo "${user}:100000:65536" >> /etc/subgid
      log "Added /etc/subgid entry for $user → ${user}:100000:65536"
    else
      die "No /etc/subgid entry for $user — run with --fix-rootless or add manually."
    fi
  fi

  # ensure setuid on newuidmap/newgidmap
  for bin in /usr/bin/newuidmap /usr/bin/newgidmap; do
    if [[ ! -e "$bin" ]]; then
      die "$bin missing — install the 'uidmap' package."
    fi
    if [[ ! -u "$bin" ]]; then
      if [[ $FIX_ROOTLESS -eq 1 ]]; then
        if [[ $(id -u) -ne 0 ]]; then
          die "--fix-rootless must be run as root to chmod $bin"
        fi
        chmod u+s "$bin"
        log "Set setuid on $bin"
      else
        die "$bin not setuid — run with --fix-rootless or fix manually."
      fi
    fi
  done

  log "Rootless prerequisites OK: namespaces enabled, uid/gid maps present."
else
  warn "Running as root → rootless 'pasta' networking not available. Will fall back to slirp4netns (needs /dev/net/tun)."
fi

############ choose network backend ############
NETWORK_BACKEND="pasta"
if [[ $(id -u) -eq 0 ]]; then
  warn "Running as root: rootless 'pasta' not supported → falling back to slirp4netns."
  NETWORK_BACKEND="slirp4netns"
elif ! has_cmd pasta; then
  warn "pasta not found → falling back to slirp4netns."
  NETWORK_BACKEND="slirp4netns"
fi
if [[ "$NETWORK_BACKEND" == "slirp4netns" ]] && [[ ! -e /dev/net/tun ]]; then
  warn "/dev/net/tun is missing — slirp4netns may fail in this sandbox."
fi
log "Network backend: $NETWORK_BACKEND"

############ rootless config ############
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
mkdir -p "$XDG_RUNTIME_DIR/podman" "$HOME/.config/containers"

cat > "$HOME/.config/containers/containers.conf" <<CFG
[engine]
events_logger = "file"
cgroup_manager = "cgroupfs"
network_backend = "${NETWORK_BACKEND}"
helper_binaries_dir = ["/usr/bin","/usr/local/bin"]
conmon_cgroup = "pod"
CFG

cat > "$HOME/.config/containers/storage.conf" <<'CFG'
[storage]
driver = "vfs"
runroot = "/tmp/podman-runroot"
graphroot = "/tmp/podman-root"
CFG

############ podman API socket ############
SOCK="unix://$XDG_RUNTIME_DIR/podman/podman.sock"
if [[ ! -S "$XDG_RUNTIME_DIR/podman/podman.sock" ]]; then
  log "Starting Podman API socket at $SOCK …"
  # shellcheck disable=SC2086
  nohup podman system service --time=0 "$SOCK" >/tmp/podapi.log 2>&1 &
  sleep 0.2
fi
[[ -S "$XDG_RUNTIME_DIR/podman/podman.sock" ]] || warn "Podman socket not present yet; Testcontainers may still work via CLI."
if [[ "$DO_EXPORT_DOCKER_HOST" -eq 1 ]]; then
  export DOCKER_HOST="$SOCK"
  log "Exported DOCKER_HOST=$DOCKER_HOST"
fi

############ postgres container ############
REPOSITORY="docker.io/library"
PG_IMAGE="postgres"
IMAGE="${REPOSITORY}/${PG_IMAGE}:${PG_VERSION}"
PG_USERNAME=${PG_USERNAME:-postgres}
PG_PASSWORD=${PG_PASSWORD:-postgres}
PG_HOST=${PG_HOST:-127.0.0.1}
PG_DATABASE=${PG_DATABASE:-postgres}

log "Removing old container (if any)…"
docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
docker image exists "$IMAGE" || docker pull "$IMAGE" >/dev/null

NET_FLAG="--network=${NETWORK_BACKEND}"
USERNS_FLAG=()
if [[ $(id -u) -ne 0 ]]; then
  USERNS_FLAG=(--userns=keep-id)
fi

log "Starting Postgres $PG_VERSION as '$PG_NAME' on ${PG_HOST}:${PG_PORT}…"
set -x
docker run --rm -d --name "$PG_NAME" \
  ${NET_FLAG} \
  "${USERNS_FLAG[@]}" \
  --cgroups=disabled \
  --security-opt seccomp=unconfined \
  --tmpfs /var/lib/postgresql/data:rw,size=1024m \
  -e POSTGRES_PASSWORD="${PG_PASSWORD}" \
  -e POSTGRES_USER="${PG_USERNAME}" \
  -e POSTGRES_DB="${PG_DATABASE}" \
  -p ${PG_HOST}:${PG_PORT}:5432 \
  "$IMAGE"
set +x

############ wait ready & init schema ############
log "Waiting for Postgres to become ready…"
tries=0
until docker exec "$PG_NAME" pg_isready -U postgres >/dev/null 2>&1; do
  sleep 0.5
  tries=$((tries+1))
  if (( tries > 180 )); then
    warn "Postgres failed to become ready."
    docker logs "$PG_NAME" || true
    docker inspect "$PG_NAME" --format '{{json .State}}' || true
    die "Container did not become healthy."
  fi
 done

if [[ "$DO_DDL" -eq 1 ]]; then
  if [[ -f "$DDL_PATH" ]]; then
    log "Loading DDL from $DDL_PATH…"
    docker cp "$DDL_PATH" "$PG_NAME:/ddl.sql"
    docker exec -u postgres "$PG_NAME" psql -f /ddl.sql >/dev/null
  else
    warn "DDL file not found at $DDL_PATH — skipping."
  fi
fi

log "✅ Postgres ${PG_VERSION} is running at ${PG_HOST}:${PG_PORT} (user=$PG_USERNAME password=${PG_PASSWORD})"
if [[ "$DO_EXPORT_DOCKER_HOST" -eq 1 ]]; then
  log "Testcontainers can target Podman via DOCKER_HOST ($DOCKER_HOST)."
fi

