import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Graviton',
  description: 'Content-addressable storage runtime built on ZIO',
  cleanUrls: true,
  themeConfig: {
    nav: [
      { text: 'Architecture', link: '/architecture' },
      { text: 'Upload Flow', link: '/end-to-end-upload' },
      { text: 'API', link: '/api' }
    ],
    sidebar: [
      {
        text: 'Overview',
        items: [
          { text: 'Introduction', link: '/' },
          { text: 'Architecture', link: '/architecture' }
        ]
      },
      {
        text: 'Ingest Pipeline',
        items: [
          { text: 'End-to-end Upload', link: '/end-to-end-upload' },
          { text: 'Manifests & Frames', link: '/manifests-and-frames' }
        ]
      },
      {
        text: 'Operations',
        items: [
          { text: 'Constraints & Metrics', link: '/constraints-and-metrics' },
          { text: 'API', link: '/api' }
        ]
      }
    ],
    editLink: {
      pattern: 'https://github.com/graviton-data/graviton/edit/main/docs/:path'
    }
  }
})
