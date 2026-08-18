# Graviton Docs (VitePress)

This directory contains the Graviton documentation site, built with VitePress. It also hosts the compiled Scala.js demo bundles under `docs/public/`.

## Run locally

```bash
cd docs
npm install

# Start dev server (hot reload)
npm run docs:dev

# Build for production
npm run docs:build

# Preview production build
npm run docs:preview
```

## Demo assets

- `./sbt buildFrontend` writes the Graviton demo bundle into `docs/public/js/`.
- `./sbt buildQuasarFrontend` writes the Quasar demo bundle into `docs/public/quasar/js/`.
- `./sbt buildDocsAssets` builds both demo bundles and Scaladoc into `docs/public/`.

## Snippets (mdoc)

Some docs embed Scala snippets that are sourced from `docs/snippets/` and kept in sync via SBT:

- Update snippet sources under `docs/snippets/src/main/scala/...`
- Regenerate rendered snippet blocks: `./sbt syncDocSnippets`
- Verify snippets are up to date: `./sbt docs/mdoc checkDocSnippets`
