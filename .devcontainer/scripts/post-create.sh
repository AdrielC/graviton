#!/usr/bin/env bash
set -euo pipefail

echo "[post-create] Verifying toolchain..."
java -version || true
sbt --version || true
cs version || true

# Warm coursier and sbt caches to speed up first IDE import
SCALA_VERSION=${SCALA_VERSION:-3.7.2}

echo "[post-create] Warming coursier cache for Scala ${SCALA_VERSION}..."
cs fetch org.scala-lang:scala3-compiler_3:${SCALA_VERSION} >/dev/null 2>&1 || true

echo "[post-create] Running sbt update and compile to prefetch dependencies..."
sbt -v -Dsbt.log.noformat=true update
sbt -v -Dsbt.log.noformat=true compile

echo "[post-create] Done."
