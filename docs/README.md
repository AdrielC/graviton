# Graviton Docs (VitePress)

This directory contains the Graviton documentation site, built with VitePress. It also hosts compiled Scala.js console bundles under `docs/public/`.

## Run locally

```bash
cd docs
npm ci

# Start dev server (hot reload)
npm run docs:dev

# Build for production
npm run docs:build

# Preview production build
npm run docs:preview
```

## Console assets

- `./sbt buildFrontend` writes the Graviton operations console into `docs/public/js/`.
- `./sbt buildQuasarFrontend` writes the Quasar console into `docs/public/quasar/js/`.
- `./sbt buildDocsAssets` builds both console bundles and Scaladoc into `docs/public/`.

## Snippets (mdoc)

Some docs embed Scala snippets that are sourced from `docs/snippets/` and kept in sync via SBT:

- Update snippet sources under `docs/snippets/src/main/scala/...`
- Regenerate rendered snippet blocks: `./sbt syncDocSnippets`
- Verify snippets are up to date: `./sbt docs/mdoc checkDocSnippets`
