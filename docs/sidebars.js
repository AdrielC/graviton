// @ts-check

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docs: [
    'index',
    {
      type: 'category',
      label: 'Getting Started',
      collapsed: false,
      items: [
        'getting-started/installation',
        'getting-started/quick-start',
        'getting-started/backends',
      ],
    },
    {
      type: 'category',
      label: 'Core Concepts',
      items: [
        'glossary',
        'concepts/index',
        'architecture',
        'storage-api-overview',
        'binary-store',
        'cas-first-blob-store',
      ],
    },
    {
      type: 'category',
      label: 'Guides',
      items: [
        'logging',
        'metrics',
        'scan',
        'chunking',
      ],
    },
    {
      type: 'category',
      label: 'Examples',
      items: [
        'examples/index',
        'examples/cli',
        'examples/http',
      ],
    },
    {
      type: 'category',
      label: 'Reference',
      items: [
        'design-goals',
        'use-cases',
        'roadmap',
        'file-descriptor-schema',
        'orthogonal-ranges',
        { type: 'link', label: 'API Reference (ScalaDoc)', href: '/graviton/api' },
      ],
    },
  ],
};

module.exports = sidebars;
