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
  description: 'Documentation for Graviton, a ZIO-based content-addressable storage runtime.',
  base,
  cleanUrls: true,
  head: [
    ['link', { rel: 'icon', href: withBase('/logo.svg') }],
    ['meta', { name: 'theme-color', content: '#1d4ed8' }],
    ['meta', { name: 'og:type', content: 'website' }],
    ['meta', { name: 'og:title', content: 'Graviton Documentation' }],
    ['meta', { name: 'og:description', content: 'Guides, architecture notes, and API docs for the Graviton storage runtime.' }],
    ['meta', { name: 'og:image', content: withBase('/logo.svg') }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'Graviton Documentation' }]
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
      { text: 'Architecture', link: '/architecture' },
      { text: 'API', link: '/api' },
      { text: 'Scala.js', link: '/dev/scalajs' },
      { text: 'Demo', link: '/demo' },
      { text: 'Scaladoc', link: '/scaladoc/index.html', target: '_blank' }
    ],
    sidebar: [
      {
        text: 'Getting Started',
        items: [
          { text: 'Introduction', link: '/' },
          { text: 'Quick Start', link: '/guide/getting-started' },
          { text: 'Installation', link: '/guide/installation' }
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
          { text: 'Scala.js Frontend', link: '/modules/frontend' }
        ]
      },
      {
        text: 'Operations',
        items: [
          { text: 'Constraints & Metrics', link: '/constraints-and-metrics' },
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
          { text: 'Scaladoc', link: '/scaladoc/index.html', target: '_blank' }
        ]
      },
      {
        text: 'Development',
        items: [
          { text: 'Contributing', link: '/dev/contributing' },
          { text: 'Testing', link: '/dev/testing' },
          { text: 'Scala.js Playbook', link: '/dev/scalajs' },
          { text: 'Design Docs', link: '/design/' }
        ]
      },
      {
        text: 'Interactive',
        items: [
          { text: 'Live Demo', link: '/demo' }
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
      message: 'Built with ZIO and Scala 3',
      copyright: 'MIT License'
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
