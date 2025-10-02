const lightCodeTheme = require('prism-react-renderer/themes/github');
const darkCodeTheme = require('prism-react-renderer/themes/vsDark');

module.exports = {
  title: 'Graviton',
  url: 'https://adrielc.github.io',
  baseUrl: '/graviton/',
  organizationName: 'AdrielC',
  projectName: 'graviton',
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',
  favicon: 'img/favicon.ico',
  i18n: { defaultLocale: 'en', locales: ['en'] },
  themeConfig: {
    colorMode: { defaultMode: 'dark', respectPrefersColorScheme: false },
    navbar: {
      title: 'Graviton',
      items: [
        { to: '/docs/', label: 'Docs', position: 'left' },
        { to: '/api', label: 'API', position: 'left' },
        { to: '/vis', label: 'Visualize', position: 'left' },
        { href: 'https://github.com/AdrielC/graviton', label: 'GitHub', position: 'right' }
      ]
    },
    prism: {
      additionalLanguages: ['scala'],
      theme: lightCodeTheme,
      darkTheme: darkCodeTheme
    }
  },
  presets: [
    [
      'classic',
      {
        docs: {
          routeBasePath: 'docs',
          path: 'src/docs',
          sidebarPath: './sidebars.js'
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css'
        }
      }
    ]
  ]
};