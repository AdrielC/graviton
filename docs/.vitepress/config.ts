import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

const normalizeBase = (value?: string) => {
  const trimmed = value?.trim()
  if (!trimmed) {
    return undefined
  }

  const withLeadingSlash = trimmed.startsWith('/') ? trimmed : `/${trimmed}`
  return withLeadingSlash.endsWith('/') ? withLeadingSlash : `${withLeadingSlash}/`
}

const [repositoryOwner = '', repositoryName = ''] = (process.env.GITHUB_REPOSITORY ?? '').split('/')
const ownerLowerCase = repositoryOwner.trim().toLowerCase()
const nameTrimmed = repositoryName.trim()
const repoLowerCase = nameTrimmed.toLowerCase()
const isUserOrOrgSite = repoLowerCase.length > 0 && repoLowerCase === `${ownerLowerCase}.github.io`

const inferredBase = nameTrimmed.length > 0 ? (isUserOrOrgSite ? '/' : `/${nameTrimmed}/`) : '/'
const base = normalizeBase(process.env.DOCS_BASE) ?? inferredBase

const withBase = (path: string) => {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const trimmedBase = base.endsWith('/') ? base.slice(0, -1) : base
  return `${trimmedBase}${normalizedPath}` || '/'
}

export default withMermaid(defineConfig({
  vite: {
    build: {
      rollupOptions: {
        external: [
          // Treat Scala.js modules as external to avoid bundling issues
          /^\/graviton\/js\/.+\.js$/,
          /^\/js\/.+\.js$/,
          /^\/quasar\/js\/.+\.js$/
        ]
      }
    }
  },
  title: 'Graviton',
  description: 'Content-addressable storage runtime built on ZIO • Modular • Blazingly Fast',
  base,
  cleanUrls: true,
  head: [
    ['link', { rel: 'icon', href: withBase('/logo.svg') }],
    ['meta', { name: 'theme-color', content: '#00ff41' }],
    ['meta', { name: 'og:type', content: 'website' }],
    ['meta', { name: 'og:title', content: 'Graviton • Content-Addressable Storage' }],
    ['meta', { name: 'og:description', content: 'Modular storage runtime with deduplication, streaming, and ZIO power' }],
    ['meta', { name: 'og:image', content: withBase('/logo.svg') }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'Graviton • Content-Addressable Storage' }],
    ['meta', { name: 'keywords', content: 'graviton, zio, scala, storage, content-addressable, deduplication, streaming' }]
  ],
  ignoreDeadLinks: [
    // Design docs (future)
    /^\/design\/.+/,
    // External module links
    /^\.\.\/\.\.\/modules\/.+/,
    // Scala.js modules (dynamically loaded)
    /^\/graviton\/js\/.+/,
    /^\/js\/.+/,
    /^\/quasar\/js\/.+/,
  ],
  markdown: {
    theme: {
      light: 'github-light',
      dark: 'github-dark'
    },
    config: (md) => {
      const original = md.renderer.rules.table_open ?? ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
      md.renderer.rules.table_open = (tokens, idx, options, env, self) => {
        tokens[idx].attrJoin('class', 'vp-doc-table graviton-table')
        return original(tokens, idx, options, env, self)
      }

      const fence = md.renderer.rules.fence ?? ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
      md.renderer.rules.fence = (tokens, idx, options, env, self) => {
        const token = tokens[idx]
        const info = token.info?.trim()
        if (info && info.startsWith('hocon')) {
          token.info = info.replace(/^hocon/, 'ini')
        }
        return fence(tokens, idx, options, env, self)
      }
    }
  },
  mermaid: {
    theme: 'neutral',
    darkTheme: 'forest',
    fontFamily: 'JetBrains Mono, Fira Code, monospace'
  },
  mermaidPlugin: {
    class: 'graviton-mermaid'
  },
  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'Graviton',
    nav: [
      { text: 'Guide', link: '/guide/getting-started' },
      { text: 'Streaming', link: '/guide/binary-streaming' },
      { text: 'Architecture', link: '/architecture' },
      { text: 'API', link: '/api' },
      { text: 'Scala.js', link: '/dev/scalajs' },
      { text: 'Demo', link: '/demo' },
      { text: 'Quasar Demo', link: '/quasar-demo' },
      // Note: VitePress automatically prefixes `base` for internal links.
      // Using `withBase` here double-prefixes on GitHub Pages (e.g. /repo/repo/scaladoc/) → 404.
      { text: 'Scaladoc', link: '/scaladoc/', target: '_blank' }
    ],
    sidebar: [
      {
        text: 'Getting Started',
        items: [
          { text: 'Introduction', link: '/' },
          { text: 'Quick Start', link: '/guide/getting-started' },
          { text: 'Installation', link: '/guide/installation' },
          { text: 'CLI & Server Usage', link: '/guide/cli' }
        ]
      },
      {
        text: 'Core Concepts',
        items: [
          { text: 'Architecture', link: '/architecture' },
          { text: 'Schema & Types', link: '/core/schema' },
          { text: 'Scans & Events', link: '/core/scans' },
          { text: 'Ranges & Boundaries', link: '/core/ranges' }
        ]
      },
      {
        text: 'Ingest Pipeline',
        items: [
          { text: 'End-to-end Upload', link: '/end-to-end-upload' },
          { text: 'Binary Streaming', link: '/guide/binary-streaming' },
          { text: 'Manifests & Frames', link: '/manifests-and-frames' },
          { text: 'Chunking Strategies', link: '/ingest/chunking' }
        ]
      },
      {
        text: 'Runtime',
        items: [
          { text: 'Ports & Policies', link: '/runtime/ports' },
          { text: 'Backends', link: '/runtime/backends' },
          { text: 'Replication', link: '/runtime/replication' }
        ]
      },
      {
        text: 'Modules',
        items: [
          { text: 'Overview', link: '/modules/' },
          { text: 'Backend Adapters', link: '/modules/backend' },
          { text: 'Runtime Module', link: '/modules/runtime' },
          { text: 'Streams Utilities', link: '/modules/streams' },
          { text: 'Protocol Stack', link: '/modules/protocol' },
          { text: 'Scala.js Frontend', link: '/modules/frontend' },
          { text: 'Apache Tika Module', link: '/modules/tika' }
        ]
      },
      {
        text: 'Operations',
        items: [
          { text: 'Datalake Dashboard', link: '/ops/datalake-dashboard' },
          { text: 'Constraints & Metrics', link: '/constraints-and-metrics' },
          { text: 'Postgres Schema (Alpha Overhaul)', link: '/ops/postgres-schema' },
          { text: 'Deployment', link: '/ops/deployment' },
          { text: 'Performance Tuning', link: '/ops/performance' }
        ]
      },
      {
        text: 'API Reference',
        items: [
          { text: 'API Overview', link: '/api' },
          { text: 'gRPC', link: '/api/grpc' },
          { text: 'HTTP', link: '/api/http' },
          { text: 'Quasar HTTP API v1 (Draft)', link: '/api/quasar-http-v1' },
          { text: 'Quasar metadata governance (Draft)', link: '/api/quasar-metadata' },
          { text: 'Legacy repository integration', link: '/api/legacy-repos' },
          { text: 'Scaladoc', link: '/scaladoc/', target: '_blank' }
        ]
      },
      {
        text: 'Development',
        items: [
          { text: 'Contributing', link: '/dev/contributing' },
          { text: 'Testing', link: '/dev/testing' },
          { text: 'Scala.js Playbook', link: '/dev/scalajs' },
          { text: 'Design Docs', link: '/design/' },
          { text: 'Quasar HTTP API v1 (Draft)', link: '/design/quasar-http-api-v1' },
          { text: 'Quasar ↔ Cedar Compat (Draft)', link: '/design/quasar-cedar-compat' },
          { text: 'Patch-based Metadata (Draft)', link: '/design/quasar-metadata-patching' },
          { text: 'Quasar metadata envelope v1.1 (Draft)', link: '/design/quasar-metadata-envelope-v1.1' },
        ]
      },
      {
        text: 'Interactive',
        items: [
          { text: 'Live Demo', link: '/demo' },
          { text: 'Quasar Demo', link: '/quasar-demo' }
        ]
      }
    ],
    editLink: {
      pattern: 'https://github.com/AdrielC/graviton/edit/main/docs/:path'
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/AdrielC/graviton' }
    ],
    search: {
      provider: 'local'
    },
    footer: {
      message: 'Built with ZIO • Powered by Scala 3',
      copyright: 'Content-addressable storage, refined. • MIT License'
    },
    outline: {
      level: [2, 3],
      label: 'On this page'
    },
    docFooter: {
      prev: '← Previous',
      next: 'Next →'
    },
    darkModeSwitchLabel: 'Theme',
    returnToTopLabel: '↑ Back to top',
    sidebarMenuLabel: 'Menu',
    externalLinkIcon: true
  }
}))
