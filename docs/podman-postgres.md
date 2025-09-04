# Running Postgres with Podman in a Rootless Environment

heck yeah — here’s the short, readable story of how your agent wrestled Podman into running Postgres in that dev box, plus a tiny “do-this-again” script you can paste anywhere.

## What was busted
- The environment is rootless and locked down:
  - OverlayFS + cgroups writes = ❌
  - slirp4netns wanted /dev/net/tun = ❌
  - So `podman run … -p 5432:5432` kept dying (setns/TUN/cgroup errors).
  - Testcontainers’ Ryuk sidecar also needs namespaces → also ❌ in rootless Podman.

## What we changed to make Postgres run under Podman
1. Installed the bits Podman needs (already present in your env, but this is the checklist):
   - `podman`, `fuse-overlayfs`, `slirp4netns`, `newuidmap`, `newgidmap`
2. Rootless-safe storage + cgroups
   - Overlay was unhappy; we force a boring, compatible setup:
   - `driver = "vfs"` (no overlayfs)
   - `cgroup_manager = "cgroupfs"` (works when systemd/cgroup sandbox is read-only)
3. Use pasta instead of slirp4netns
   - `pasta` (from `passt`) doesn’t need `/dev/net/tun`, so rootless networking “just works” and `-p 5432:5432` actually binds.
4. Start the Podman API socket (handy for Docker-compat tools, optional for `podman run`):
   - Create `$XDG_RUNTIME_DIR/podman/…` and point `DOCKER_HOST` at it.
5. Run Postgres 17 with the pasta network and the VFS storage.

## The minimal working config
Create the config files once:

```bash
# Rootless runtime dir
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
mkdir -p "$XDG_RUNTIME_DIR/podman"

# ~/.config/containers/containers.conf
mkdir -p ~/.config/containers
cat > ~/.config/containers/containers.conf <<'CFG'
[engine]
events_logger = "file"
cgroup_manager = "cgroupfs"
# the money line: avoid /dev/net/tun requirement
network_backend = "pasta"
helper_binaries_dir = ["/usr/bin","/usr/local/bin"]

[network]
cni_plugin_dirs = []   # disable CNI; we’re on netavark+pasta
CFG

# ~/.config/containers/storage.conf
cat > ~/.config/containers/storage.conf <<'CFG'
[storage]
driver = "vfs"
runroot = "/tmp/podman-runroot"
graphroot = "/tmp/podman-root"
CFG
```

(If pasta isn’t installed, `apt-get install -y passt` on Ubuntu will provide it.)

Start the Podman API (optional, but nice if tooling expects `DOCKER_HOST`):

```bash
podman system service --time=0 "unix://$XDG_RUNTIME_DIR/podman/podman.sock" >/tmp/podapi.log 2>&1 &
export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/podman/podman.sock"
```

Run Postgres 17 (this is the part that finally worked)

```bash
# Pull and run on the pasta backend; publish works in rootless
podman run --rm -d --name graviton-pg \
  --network pasta \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  docker.io/library/postgres:17

# Wait until it’s ready
until podman exec graviton-pg pg_isready -U postgres >/dev/null 2>&1; do sleep 0.5; done

# (optional) initialize schema
podman cp modules/pg/ddl.sql graviton-pg:/ddl.sql
podman exec -u postgres graviton-pg psql -f /ddl.sql
```

At this point:
- `psql -h 127.0.0.1 -U postgres -c 'select version();'` → shows PostgreSQL 17 ✅
- Your `sbt 'pg/dbcodegen'` can point at `jdbc:postgresql://localhost:5432/postgres`.

## If you still want Testcontainers in that env
- Ryuk is the blocker under rootless Podman. Two pragmatic toggles per-job:

```bash
# only in the Podman job
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_CHECKS_DISABLE=true
export TESTCONTAINERS_HOST_OVERRIDE=localhost
export TESTCONTAINERS_REUSE_ENABLE=true
```

- Or just don’t export `TESTCONTAINERS=1` in that job and let the suite skip TC tests.

## One-file rerun script (copy/paste)

```bash
#!/usr/bin/env bash
set -euo pipefail

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

podman system service --time=0 "unix://$XDG_RUNTIME_DIR/podman/podman.sock" >/tmp/podapi.log 2>&1 &
export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/podman/podman.sock"

podman rm -f graviton-pg >/dev/null 2>&1 || true
podman run --rm -d --name graviton-pg \
  --network pasta \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  docker.io/library/postgres:17

until podman exec graviton-pg pg_isready -U postgres >/dev/null 2>&1; do sleep 0.5; done
echo "Postgres 17 is up on localhost:5432 (user=postgres password=postgres)"
```

That’s the exact recipe that dodges `/dev/net/tun`, overlayfs, and cgroup write headaches in that sandbox — and keeps you on Podman without needing a privileged daemon.
