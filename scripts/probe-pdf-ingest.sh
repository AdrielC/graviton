#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 path/to/document.pdf" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PDF_PATH="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
PROOF_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/graviton-pdf-proof.XXXXXX")"

cleanup() { find "${PROOF_ROOT}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

if [[ ! -f "${PDF_PATH}" ]]; then
  echo "PDF does not exist: ${PDF_PATH}" >&2
  exit 1
fi

cd "${REPO_ROOT}"
./sbt -batch "pdf/Test/runMain graviton.pdf.PdfIngestProbe \"${PDF_PATH}\" \"${PROOF_ROOT}/cas\""
