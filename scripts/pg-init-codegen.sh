#!/usr/bin/env bash
set -euo pipefail

# pg-init-codegen.sh
# Bootstraps Postgres (native/docker via ensure-postgres.sh), applies DDL, and runs Scala codegen.

cd "$(dirname "$0")/.."

echo "[pg-init] Bootstrapping Postgres..."
eval "$(./scripts/ensure-postgres.sh --engine native)"

PGHOST=${PG_HOST:-127.0.0.1}
PGPORT=${PG_PORT:-5432}
PGDB=${PG_DATABASE:-graviton}
PGUSER=${PG_USERNAME:-postgres}
PGPASS=${PG_PASSWORD:-postgres}

export PGPASSWORD="$PGPASS"

echo "[pg-init] Ensuring psql is available or using docker..."
if command -v psql >/dev/null 2>&1; then
  echo "[pg-init] Ensuring database $PGDB exists..."
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$PGDB\" WITH ENCODING 'UTF8' TEMPLATE template1" >/dev/null 2>&1 || true
  echo "[pg-init] Applying DDL..."
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 -f modules/pg/ddl.sql
else
  if command -v docker >/dev/null 2>&1; then
    echo "[pg-init] Using docker exec to apply DDL..."
    CONTAINER_ID=$(docker ps --filter 'name=graviton' --filter 'ancestor=postgres' --format '{{.ID}}' | head -n1)
    if [ -z "$CONTAINER_ID" ]; then
      echo "[pg-init] No postgres container detected; attempting to start one via ensure-postgres.sh"
      eval "$(./scripts/ensure-postgres.sh --engine docker)"
      CONTAINER_ID=$(docker ps --filter 'ancestor=postgres' --format '{{.ID}}' | head -n1)
    fi
    docker exec -e PGPASSWORD="$PGPASS" "$CONTAINER_ID" psql -h 127.0.0.1 -p 5432 -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$PGDB\" WITH ENCODING 'UTF8' TEMPLATE template1" >/dev/null 2>&1 || true
    docker exec -e PGPASSWORD="$PGPASS" -i "$CONTAINER_ID" psql -h 127.0.0.1 -p 5432 -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 < modules/pg/ddl.sql
  else
    echo "[pg-init] Neither psql nor docker available. Please install one of them."
    exit 1
  fi
fi

echo "[pg-init] Running Scala codegen..."
TESTCONTAINERS=0 ./sbt pg/runGen

echo "[pg-init] Done. Generated sources in modules/pg/src/main/scala/graviton/pg/generated"


