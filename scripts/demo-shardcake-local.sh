#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/local-shardcake/docker-compose.yml"
action="${1:-up}"

compose() {
  docker compose -f "$compose_file" "$@"
}

case "$action" in
  up)
    cd "$repo_root"
    TESTCONTAINERS=0 ./sbt -Dsbt.server.autostart=false -Dsbt.supershell=false "server/assembly"
    compose up -d --build
    for _ in $(seq 1 90); do
      if curl --fail --silent http://127.0.0.1:58081/api/health/ready >/dev/null \
        && curl --fail --silent http://127.0.0.1:58082/api/health/ready >/dev/null; then
        printf '%s\n' "Graviton is ready: http://127.0.0.1:58081/console"
        exit 0
      fi
      sleep 2
    done
    compose ps
    compose logs --tail=120 shardcake-manager graviton-node-1 graviton-node-2
    printf '%s\n' "Graviton did not become ready within 180 seconds." >&2
    exit 1
    ;;
  status)
    compose ps
    ;;
  logs)
    compose logs --tail=200 -f shardcake-manager graviton-node-1 graviton-node-2
    ;;
  stop)
    compose stop
    ;;
  *)
    printf '%s\n' "usage: $0 {up|status|logs|stop}" >&2
    exit 2
    ;;
esac
