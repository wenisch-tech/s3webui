const basePath = (document.querySelector('meta[name="s3webui-base-path"]')?.content || '')
  .replace(/\/+$/, '');

export const appUrl = path =>
  path.startsWith('/') && !path.startsWith('//') ? `${basePath}${path}` : path;
