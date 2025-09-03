#!/usr/bin/env bash
set -euo pipefail

echo "[post-start] Container started. JAVA_HOME=${JAVA_HOME:-}"

# Optionally, print sbt JVM info for quick diagnostics
sbt -Dsbt.log.noformat=true "show javaHome" || true
