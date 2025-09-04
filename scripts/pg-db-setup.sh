#!/usr/bin/env bash
set -euo pipefail

# Configure Podman for rootless Postgres using the pasta backend and VFS storage.
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
mkdir -p "$XDG_RUNTIME_DIR/podman" ~/.config/containers

cat > ~/.config/containers/containers.conf <<'CFG'
[engine]
events_logger = "file"
cgroup_manager = "cgroupfs"
network_backend = "pasta"
helper_binaries_dir = ["/usr/bin","/usr/local/bin"]

[network]
cni_plugin_dirs = []
CFG

cat > ~/.config/containers/storage.conf <<'CFG'
[storage]
driver = "vfs"
runroot = "/tmp/podman-runroot"
graphroot = "/tmp/podman-root"
CFG

# Start Podman API socket for Docker-compatible clients.
podman system service --time=0 "unix://$XDG_RUNTIME_DIR/podman/podman.sock" >/tmp/podapi.log 2>&1 &
export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/podman/podman.sock"

# Launch PostgreSQL 17 container and initialize schema.
podman rm -f graviton-pg >/dev/null 2>&1 || true
podman run --rm -d --name graviton-pg \
  --network pasta \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  docker.io/library/postgres:17

until podman exec graviton-pg pg_isready -U postgres >/dev/null 2>&1; do
  sleep 0.5
done

podman cp modules/pg/ddl.sql graviton-pg:/ddl.sql
podman exec -u postgres graviton-pg psql -f /ddl.sql >/dev/null

echo "Postgres is ready on localhost:5432"
