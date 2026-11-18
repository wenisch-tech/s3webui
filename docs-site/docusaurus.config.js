// @ts-check
import { themes as prismThemes } from 'prism-react-renderer';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'S3 Web UI',
  tagline: 'A browser interface for S3-compatible object storage',
  favicon: 'img/favicon.png',
  // Served at https://wenisch-tech.github.io/s3webui/; override through the environment for other hosts.
  url: process.env.DOCS_URL || 'https://wenisch-tech.github.io',
  baseUrl: process.env.DOCS_BASE_URL || '/s3webui/',
  organizationName: 'wenisch-tech',
  projectName: 's3webui',
  onBrokenLinks: 'throw',
  onBrokenAnchors: 'warn',
  trailingSlash: true,

  // The pages are plain CommonMark; only files that opt in via .mdx are parsed as MDX.
  markdown: {
    format: 'detect',
    mermaid: true,
    hooks: { onBrokenMarkdownLinks: 'throw' },
  },
  themes: [
    '@docusaurus/theme-mermaid',
    [
      '@easyops-cn/docusaurus-search-local',
      { hashed: true, docsDir: '../docs', indexBlog: false, docsRouteBasePath: '/', highlightSearchTermsOnTargetPage: true },
    ],
  ],

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          path: '../docs',
          routeBasePath: '/',
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/wenisch-tech/s3webui/edit/main/docs/',
        },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
      }),
    ],
  ],

  themeConfig: /** @type {import('@docusaurus/preset-classic').ThemeConfig} */ ({
    image: 'img/social-card.png',
    colorMode: { defaultMode: 'light', respectPrefersColorScheme: true },
    navbar: {
      title: 'S3 Web UI',
      logo: { alt: 'S3 Web UI', src: 'img/logo.png' },
      items: [
        { type: 'docSidebar', sidebarId: 'docs', position: 'left', label: 'Documentation' },
        { href: 'https://artifacthub.io/packages/helm/jfwenisch/s3webui', label: 'Helm chart', position: 'right' },
        { href: 'https://github.com/wenisch-tech/s3webui', label: 'GitHub', position: 'right' },
      ],
    },
    footer: {
      style: 'dark',
      copyright:
        'S3 Web UI is open-source software made in Germany, licensed under AGPL-3.0.<br/>' +
        '<a href="https://github.com/wenisch-tech/s3webui">GitHub</a> · ' +
        '<a href="https://github.com/wenisch-tech/s3webui/issues/new">Report a bug</a>',
    },
    docs: { sidebar: { hideable: true, autoCollapseCategories: true } },
    tableOfContents: { minHeadingLevel: 2, maxHeadingLevel: 3 },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['bash', 'yaml', 'java', 'properties', 'json', 'docker'],
    },
    mermaid: { theme: { light: 'neutral', dark: 'dark' } },
  }),
};

export default config;
