# Graviton Docs (VitePress)

This directory contains the Graviton documentation site, built with VitePress. It also hosts the compiled Scala.js operations consoles and shared CAS content lab under `docs/public/`.

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
- `./sbt buildContentLab` links `graviton-shared` into `docs/public/content-lab/` for the bounded CAS Playground.
- `./sbt buildDocsAssets` builds the shared content lab, Graviton console, and Scaladoc into `docs/public/`.

## Snippets (mdoc)

Some docs embed Scala snippets that are sourced from `docs/snippets/` and kept in sync via SBT:

- Update snippet sources under `docs/snippets/src/main/scala/...`
- Regenerate rendered snippet blocks: `./sbt syncDocSnippets`
- Verify snippets are up to date: `./sbt docs/mdoc checkDocSnippets`
