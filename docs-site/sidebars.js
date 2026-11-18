/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docs: [
    'README',
    {
      type: 'category', label: 'Installation', collapsed: true,
      link: { type: 'doc', id: 'Installation/README' },
      items: ['Installation/Getting-Started', 'Installation/Deployment', 'Installation/Helm-Chart'],
    },
    {
      type: 'category', label: 'Usage', collapsed: true,
      link: { type: 'doc', id: 'Usage/README' },
      items: ['Usage/User-Guide', 'Usage/Storage-Workflows', 'Usage/Bucket-Configuration', 'Usage/Audit-History'],
    },
    {
      type: 'category', label: 'Administration', collapsed: true,
      link: { type: 'doc', id: 'Administration/README' },
      items: [
        'Administration/Administration',
        'Administration/S3-Key-Management',
        'Administration/Authentication',
        'Administration/IAM-Management',
        'Administration/API',
      ],
    },
  ],
};
export default sidebars;
