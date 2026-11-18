// @ts-check
import { themes as prismThemes } from 'prism-react-renderer';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'S3 Web UI',
  tagline: 'A browser interface for S3-compatible object storage',
  favicon: 'img/favicon.png',
  // Served from the root of its host; override through the environment for a sub-path or another host.
  url: process.env.DOCS_URL || 'https://s3webui.wenisch.tech',
  baseUrl: process.env.DOCS_BASE_URL || '/',
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
  plugins: ['docusaurus-plugin-image-zoom'],
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
    colorMode: { defaultMode: 'light', respectPrefersColorScheme: false },
    navbar: {
      title: 'S3 Web UI',
      logo: { alt: 'S3 Web UI', src: 'img/logo.png' },
      items: [
        { type: 'docSidebar', sidebarId: 'docs', position: 'left', label: 'Documentation' },
        {
          href: 'https://artifacthub.io/packages/helm/jfwenisch/s3webui', position: 'right',
          className: 'navbar-icon navbar-icon--helm', 'aria-label': 'Helm chart on Artifact Hub', title: 'Helm chart on Artifact Hub',
        },
        {
          href: 'https://github.com/wenisch-tech/s3webui', position: 'right',
          className: 'navbar-icon navbar-icon--github', 'aria-label': 'GitHub repository', title: 'GitHub repository',
        },
      ],
    },
    footer: {
      style: 'dark',
      copyright:
        'S3 Web UI is open-source software made in Germany, licensed under AGPL-3.0.<br/>' +
        '<a href="https://github.com/wenisch-tech/s3webui">GitHub</a> · ' +
        '<a href="https://github.com/wenisch-tech/s3webui/issues/new">Report a bug</a>',
    },
    // Click an image to enlarge it.
    zoom: {
      selector: '.markdown img:not(.no-zoom)',
      background: { light: 'rgba(7, 19, 28, 0.82)', dark: 'rgba(7, 19, 28, 0.9)' },
      config: { margin: 24 },
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
