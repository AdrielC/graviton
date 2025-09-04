#!/usr/bin/env bash
set -euo pipefail

# Start a temporary PostgreSQL container, run db codegen, then clean up.
# Usage: pg-codegen.sh [postgres-version]

PG_VERSION=${1:-16}
CONTAINER_NAME="graviton-pg-codegen"

# Start Postgres container
if command -v docker >/dev/null 2>&1; then
  docker run --rm -d --name "$CONTAINER_NAME" -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:${PG_VERSION}
  cleanup() { docker stop "$CONTAINER_NAME" >/dev/null 2>&1; }
  trap cleanup EXIT
  # Wait until Postgres is ready
  until docker exec "$CONTAINER_NAME" pg_isready -U postgres >/dev/null 2>&1; do
    sleep 0.5
  done
  # Run db code generation
  sbt pg/dbcodegen
else
  echo "docker command not found; please install Docker to run codegen" >&2
  exit 1
fi
