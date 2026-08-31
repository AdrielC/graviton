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
          /^\/js\/.+\.js$/
        ]
      }
    }
  },
  title: 'Graviton',
  description: 'Typed, streaming content-addressable storage for Scala and ZIO',
  base,
  cleanUrls: true,
  head: [
    ['link', { rel: 'icon', href: withBase('/logo.svg') }],
    ['meta', { name: 'theme-color', content: '#f5f8f7', media: '(prefers-color-scheme: light)' }],
    ['meta', { name: 'theme-color', content: '#0b1110', media: '(prefers-color-scheme: dark)' }],
    ['meta', { name: 'og:type', content: 'website' }],
    ['meta', { name: 'og:title', content: 'Graviton • Content-Addressable Storage' }],
    ['meta', { name: 'og:description', content: 'Durable local CAS, streaming APIs, typed manifests, and pluggable backends for Scala' }],
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
    fontFamily: 'JetBrains Mono, Fira Code, monospace',
    // Mermaid otherwise appends a giant diagnostic SVG directly to <body>
    // when a client-side render fails. Static syntax remains enforced by
    // scripts/check-mermaid.mjs, while readers keep a usable document.
    suppressErrorRendering: true
  },
  mermaidPlugin: {
    class: 'graviton-mermaid'
  },
  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'Graviton',
    nav: [
      { text: 'Quickstart', link: '/guide/getting-started' },
      {
        text: 'Learn',
        items: [
          { text: 'Architecture', link: '/architecture' },
          { text: 'Streaming', link: '/guide/binary-streaming' },
          { text: 'Chunking', link: '/ingest/chunking' },
          { text: 'Storage Backends', link: '/guide/storage-backends' }
        ]
      },
      {
        text: 'Operate',
        items: [
          { text: 'Run Locally', link: '/guide/run-locally' },
          { text: 'Configuration', link: '/guide/configuration-reference' },
          { text: 'Deployment', link: '/ops/deployment' },
          { text: 'Production Readiness', link: '/ops/production-readiness' }
        ]
      },
      {
        text: 'Reference',
        items: [
          { text: 'Scala SDK', link: '/guide/scala-sdk' },
          { text: 'HTTP API', link: '/api/http' },
          { text: 'gRPC API', link: '/api/grpc' },
          // VitePress prefixes the configured base for internal links.
          { text: 'Scaladoc', link: '/scaladoc/', target: '_blank' }
        ]
      },
      { text: 'CAS Playground', link: '/cas-playground' }
    ],
    sidebar: [
      {
        text: 'Start',
        items: [
          { text: 'Overview', link: '/' },
          { text: 'Quickstart', link: '/guide/getting-started' },
          { text: 'Run Locally', link: '/guide/run-locally' },
          { text: 'Install', link: '/guide/installation' },
          { text: 'Configure', link: '/guide/configuration-reference' },
          { text: 'Storage Backends', link: '/guide/storage-backends' },
          { text: 'Scala SDK', link: '/guide/scala-sdk' },
          { text: 'CLI & Server', link: '/guide/cli' },
          { text: 'Troubleshooting', link: '/guide/troubleshooting' }
        ]
      },
      {
        text: 'Understand',
        collapsed: true,
        items: [
          { text: 'Architecture', link: '/architecture' },
          { text: 'Scope & Boundary', link: '/scope' },
          { text: 'Content IDs & Types', link: '/core/schema' },
          { text: 'Streaming Algebra', link: '/core/transducers' },
          { text: 'Binary Streaming', link: '/guide/binary-streaming' },
          { text: 'Chunking', link: '/ingest/chunking' },
          { text: 'Manifests & Frames', link: '/manifests-and-frames' },
          { text: 'Scans & Events', link: '/core/scans' },
          { text: 'Ranges & Boundaries', link: '/core/ranges' }
        ]
      },
      {
        text: 'Explore',
        collapsed: true,
        items: [
          { text: 'CAS Playground', link: '/cas-playground' },
          { text: 'Pipeline Explorer', link: '/pipeline-explorer' },
          { text: 'Connect Your Server', link: '/demo' }
        ]
      },
      {
        text: 'Operate',
        collapsed: true,
        items: [
          { text: 'Production Readiness', link: '/ops/production-readiness' },
          { text: 'Deployment', link: '/ops/deployment' },
          { text: 'Backup, Restore & GC', link: '/ops/backup-restore' },
          { text: 'Constraints & Metrics', link: '/constraints-and-metrics' },
          { text: 'Performance', link: '/ops/performance' },
          { text: 'Multi-Tenant Storage', link: '/runtime/multi-tenancy' },
          { text: 'PostgreSQL Storage', link: '/ops/postgres-storage' },
          { text: 'Runtime Backends', link: '/runtime/backends' },
          { text: 'Replication', link: '/runtime/replication' },
          { text: 'Ports & Policies', link: '/runtime/ports' }
        ]
      },
      {
        text: 'Reference',
        collapsed: true,
        items: [
          { text: 'API Overview', link: '/api' },
          { text: 'HTTP API', link: '/api/http' },
          { text: 'gRPC API', link: '/api/grpc' },
          { text: 'Scala SDK', link: '/guide/scala-sdk' },
          { text: 'Scaladoc', link: '/scaladoc/', target: '_blank' }
        ]
      },
      {
        text: 'Modules',
        collapsed: true,
        items: [
          { text: 'Overview', link: '/modules/' },
          { text: 'Backend Adapters', link: '/modules/backend' },
          { text: 'Runtime', link: '/modules/runtime' },
          { text: 'Stream Utilities', link: '/modules/streams' },
          { text: 'PDF-aware Ingest', link: '/modules/pdf' },
          { text: 'Shardcake Locality', link: '/modules/shardcake' },
          { text: 'Distributed Admission', link: '/modules/distributed-admission' },
          { text: 'Protocol', link: '/modules/protocol' },
          { text: 'Scala.js Frontend', link: '/modules/frontend' },
          { text: 'Apache Tika', link: '/modules/tika' }
        ]
      },
      {
        text: 'Project',
        collapsed: true,
        items: [
          { text: 'Contributing', link: '/dev/contributing' },
          { text: 'Testing', link: '/dev/testing' },
          { text: 'Backend Laws', link: '/dev/backend-laws' },
          { text: 'Scala.js Development', link: '/dev/scalajs' },
          { text: 'Design Documents', link: '/design/' },
          {
            text: 'Architecture Decisions',
            collapsed: true,
            items: [
              { text: 'Content Identity & GC', link: '/adr/0001-content-identity-deletion-and-gc' },
              { text: 'Compatibility & Releases', link: '/adr/0002-compatibility-and-releases' },
              { text: 'Deployment Profiles', link: '/adr/0003-deployment-profiles' },
              { text: 'Maintenance Coordination', link: '/adr/0004-maintenance-coordination' }
            ]
          }
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
      message: 'Content-addressed storage for Scala 3 and ZIO',
      copyright: 'Apache-2.0'
    },
    outline: {
      level: [2, 3],
      label: 'On this page'
    },
    docFooter: {
      prev: 'Previous',
      next: 'Next'
    },
    darkModeSwitchLabel: 'Theme',
    returnToTopLabel: 'Back to top',
    sidebarMenuLabel: 'Menu',
    externalLinkIcon: true
  }
}))
