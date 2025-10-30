import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Graviton',
  description: 'Content-addressable storage runtime built on ZIO',
  base: '/graviton/',
  cleanUrls: true,
  ignoreDeadLinks: [
    // Design docs (future)
    /^\/design\/.+/,
    // External module links
    /^\.\.\/\.\.\/modules\/.+/,
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
    nav: [
      { text: 'Guide', link: '/guide/getting-started' },
      { text: 'Architecture', link: '/architecture' },
      { text: 'API', link: '/api' },
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
      message: 'Built with ZIO',
      copyright: 'Content-addressable storage, refined.'
    }
  }
})
