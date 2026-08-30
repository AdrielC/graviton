#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/three-domain/docker-compose.yml"
action="${1:-up}"

compose() {
  docker compose -f "$compose_file" "$@"
}

case "$action" in
  up)
    cd "$repo_root"
    TESTCONTAINERS=0 ./sbt -Dsbt.server.autostart=false -Dsbt.supershell=false "server/assembly"
    compose up -d --build
    for _ in $(seq 1 120); do
      if curl --fail --silent http://127.0.0.1:58181/api/health/ready >/dev/null \
        && curl --fail --silent http://127.0.0.1:59090/-/ready >/dev/null; then
        printf '%s\n' "Graviton:   http://127.0.0.1:58181/console"
        printf '%s\n' "Prometheus: http://127.0.0.1:59090"
        printf '%s\n' "Grafana:    http://127.0.0.1:59300/d/graviton-slo"
        exit 0
      fi
      sleep 2
    done
    compose ps
    compose logs --tail=160 graviton prometheus grafana minio-a minio-b minio-c
    printf '%s\n' "Three-domain Graviton did not become ready within 240 seconds." >&2
    exit 1
    ;;
  status) compose ps ;;
  logs) compose logs --tail=200 -f graviton prometheus grafana ;;
  stop) compose stop ;;
  destroy)
    printf '%s\n' "Refusing implicit data deletion. Run 'docker compose -f $compose_file down -v' explicitly for this qualification topology." >&2
    exit 2
    ;;
  *)
    printf '%s\n' "usage: $0 {up|status|logs|stop}" >&2
    exit 2
    ;;
esac
