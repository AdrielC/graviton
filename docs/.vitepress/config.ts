import { defineConfig } from 'vitepress'

export default defineConfig({
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
  description: 'Content-addressable storage runtime built on ZIO • Modular • Blazingly Fast',
  base: '/graviton/',
  cleanUrls: true,
  head: [
    ['link', { rel: 'icon', href: '/graviton/logo.svg' }],
    ['meta', { name: 'theme-color', content: '#00ff41' }],
    ['meta', { name: 'og:type', content: 'website' }],
    ['meta', { name: 'og:title', content: 'Graviton • Content-Addressable Storage' }],
    ['meta', { name: 'og:description', content: 'Modular storage runtime with deduplication, streaming, and ZIO power' }],
    ['meta', { name: 'og:image', content: '/graviton/logo.svg' }],
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
      // Mermaid diagrams will be rendered automatically
    }
  },
  mermaid: {
    // Mermaid config
  },
  themeConfig: {
    logo: '/logo.svg',
    siteTitle: '⚡ Graviton',
    nav: [
      { text: '🚀 Guide', link: '/guide/getting-started' },
      { text: '🏗️ Architecture', link: '/architecture' },
      { text: '🔌 API', link: '/api' },
      { text: '🎮 Demo', link: '/demo' },
      { text: '📚 Scaladoc', link: '/scaladoc/index.html', target: '_blank' }
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
          { text: 'Design Docs', link: '/design/' }
        ]
      },
      {
        text: 'Interactive',
        items: [
          { text: '🎮 Live Demo', link: '/demo' }
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
      message: '⚡ Built with ZIO • Powered by Scala 3',
      copyright: '🌌 Content-addressable storage, refined. • MIT License'
    },
    outline: {
      level: [2, 3],
      label: '📑 On this page'
    },
    docFooter: {
      prev: '← Previous',
      next: 'Next →'
    },
    darkModeSwitchLabel: '🌓 Theme',
    returnToTopLabel: '↑ Back to top',
    sidebarMenuLabel: '📚 Menu',
    externalLinkIcon: true
  }
})
