#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

fail() {
  printf 'Product boundary check failed: %s\n' "$1" >&2
  exit 1
}

document_layer_name='qua''sar'
retired_blob_path='/api/''blobs'

if git grep -n -i "${document_layer_name}" -- \
  build.sbt project modules dbcodegen docs deploy scripts .github README.md PRODUCT.md BUILD_AND_TEST.md; then
  fail 'the downstream document layer leaked into the Graviton source, schema, docs, deployment, or release surface'
fi

if git grep -n "${retired_blob_path}" -- \
  build.sbt project modules dbcodegen docs deploy scripts .github README.md PRODUCT.md BUILD_AND_TEST.md; then
  fail 'the retired unversioned HTTP compatibility surface is still present'
fi

if find modules -path '*/target' -prune -o -type f -path '*/legacy/*' -print -quit | grep -q .; then
  fail 'legacy import code is still shipped in a public module'
fi

if find docs/guide -maxdepth 1 -type f -name 'migration-*.md' -print -quit | grep -q .; then
  fail 'historical migration pages are still published for the clean pre-1.0 line'
fi

printf 'Graviton product boundary check passed.\n'
