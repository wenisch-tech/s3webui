/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docs: [
    'README',
    {
      type: 'category', label: 'Install', collapsed: true,
      link: { type: 'doc', id: 'Installation/README' },
      items: [
        'Installation/Docker',
        'Installation/Helm',
        'Installation/First-Steps',
        'Installation/Configuration',
        'Installation/Reverse-Proxy',
      ],
    },
    {
      type: 'category', label: 'Use', collapsed: true,
      link: { type: 'doc', id: 'Usage/README' },
      items: ['Usage/User-Guide', 'Usage/Storage-Workflows', 'Usage/Bucket-Configuration', 'Usage/Audit-History'],
    },
    {
      type: 'category', label: 'Administer', collapsed: true,
      link: { type: 'doc', id: 'Administration/README' },
      items: [
        'Administration/Administration',
        'Administration/S3-Key-Management',
        'Administration/Local-Users',
        'Administration/Runtime-Options',
        {
          type: 'category', label: 'Sign-in and OIDC', collapsed: true,
          link: { type: 'doc', id: 'Administration/Authentication' },
          items: [
            'Administration/OIDC-Concepts',
            'Administration/OIDC-Setup',
            'Administration/OIDC-Roles-And-Groups',
            'Administration/OIDC-Troubleshooting',
            'Administration/Open-Access',
          ],
        },
        {
          type: 'category', label: 'Storage IAM', collapsed: true,
          link: { type: 'doc', id: 'Administration/IAM-Management' },
          items: ['Administration/IAM-Users-Groups', 'Administration/IAM-Policies'],
        },
        'Administration/API',
      ],
    },
  ],
};
export default sidebars;
