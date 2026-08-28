#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

fail() {
  printf 'Product boundary check failed: %s\n' "$1" >&2
  exit 1
}

if rg -n 'buildQuasarFrontend|docs/public/quasar' build.sbt docs/README.md docs/.vitepress/config.ts; then
  fail 'the public documentation build still links the internal document frontend'
fi

if find docs/public -type f -iname '*quasar*' -print -quit | grep -q .; then
  fail 'an internal document-layer asset remains in docs/public'
fi

if rg -n "link: '/(quasar|api/quasar|design/quasar)|link: '/ops/postgres-schema" docs/.vitepress/config.ts; then
  fail 'public navigation links to an internal document-layer page'
fi

for project in quasarCore quasarHttp quasarLegacy quasarFrontend; do
  block="$(awk -v start="lazy val ${project} =" '
    index($0, start) == 1 { found = 1 }
    found && index($0, "lazy val ") == 1 && index($0, start) != 1 { exit }
    found { print }
  ' build.sbt)"
  [[ -n "${block}" ]] || fail "${project} is missing from build.sbt"
  grep -Fq 'publish / skip := true' <<<"${block}" || fail "${project} is publishable"
done

if rg -n 'graviton-quasar|quasar-(core|http|legacy|frontend)' \
  scripts/audit-published-artifacts.sh .github/workflows/release.yml; then
  fail 'release automation includes an internal document-layer artifact'
fi

printf 'Graviton product boundary check passed.\n'
